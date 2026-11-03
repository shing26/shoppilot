#!/usr/bin/env python3
"""LLM 录放门禁（round48 票 201 / ADR 0083）——0 token、不连模型、不连检索。

它守什么：cassette 的形状、key/路径归因、与入仓基线的逐字节一致性，以及用**既有
`judge()`** 重算的判定结论。任何一条变化都必须显式更新基线（`--update-baseline`），
不会自动放行。

它不守什么：**不证明模型漂移**。卡里录的是当时那个模型的回答；模型换了、供应商降智了，
本脚本仍然绿。模型能力仍靠周期性活体评测（180 gold / UX golden），边界写在
`docs/EVIDENCE.md` 与票 201 的 Handoff。

用法：
  python scripts/verify_llm_replay.py                     # CI 门禁
  python scripts/verify_llm_replay.py --update-baseline   # 显式换代：重录/复核之后才准跑
  python scripts/verify_llm_replay.py --dir eval/llm-cassettes
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_DIR = REPO / "eval" / "llm-cassettes"
BASELINE_NAME = "baseline.json"
INDEX_NAME = "index.json"
HEX64 = re.compile(r"^[0-9a-f]{64}$")
MODEL_SEGMENT = re.compile(r"[^A-Za-z0-9._-]")


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def canonical(value) -> str:
    """稳定的 JSON 文本：对象键排序、紧凑分隔符。只用于本脚本的摘要，不与 Java key 互算。"""
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def tool_names() -> set[str]:
    source = (REPO / "shoppilot-tool-api" / "src" / "main" / "java" / "com" / "shoppilot"
              / "tool" / "ToolName.java")
    if not source.exists():
        return {"queryOrderDetail", "queryLogistics", "modifyDeliveryAddress", "applyRefund"}
    return set(re.findall(r'^\s*[A-Z_]+\("([A-Za-z]+)"', source.read_text(encoding="utf-8"), re.M))


def load_judge():
    """复用 run_tool_eval 的唯一判据；导入失败必须红，不能静默少跑一条门禁。"""
    sys.path.insert(0, str(REPO / "scripts"))
    import run_tool_eval  # noqa: PLC0415 —— 惰性导入，保持本脚本 --help 轻量
    return run_tool_eval.judge


def cassette_files(root: Path) -> list[Path]:
    return sorted(p for p in root.glob("*/*/*.json") if p.name != BASELINE_NAME)


def validate_shape(path: Path, cassette: dict, known_tools: set[str]) -> list[str]:
    errors: list[str] = []
    required = {"key", "model", "promptVersion", "recordedAt", "source", "request", "response"}
    missing = required - set(cassette)
    if missing:
        return [f"缺字段 {sorted(missing)}"]
    key = str(cassette["key"])
    if not HEX64.match(key):
        errors.append(f"key 不是 64 位小写十六进制：{key}")
    if path.stem != key:
        errors.append(f"文件名与 key 不一致：{path.stem} != {key}")
    model_seg, version_seg = path.parent.parent.name, path.parent.name
    if MODEL_SEGMENT.sub("-", str(cassette["model"])) != model_seg:
        errors.append(f"目录模型段不一致：{model_seg} != {cassette['model']}")
    if str(cassette["promptVersion"]) != version_seg:
        errors.append(f"目录 promptVersion 不一致：{version_seg} != {cassette['promptVersion']}")
    if cassette["source"] not in ("dev", "local"):
        errors.append(f"source 必须是 dev/local，实际 {cassette['source']!r}（Mock 录制不能冒充真实模型）")

    request = cassette["request"]
    if not isinstance(request, dict):
        return errors + ["request 不是对象"]
    messages = request.get("messages")
    if not isinstance(messages, list) or not messages:
        errors.append("request.messages 为空")
    else:
        for message in messages:
            if not isinstance(message, dict) or message.get("role") not in (
                    "system", "user", "assistant", "tool"):
                errors.append(f"非法 message：{message!r}")
                break
    for field, types in (("tools", list), ("temperature", (int, float)), ("maxTokens", int)):
        if field not in request or not isinstance(request[field], types):
            errors.append(f"request.{field} 缺失或类型不对")
    if request.get("tools") and not isinstance(request["tools"], list):
        errors.append("request.tools 不是数组")

    response = cassette["response"]
    if not isinstance(response, dict):
        return errors + ["response 不是对象"]
    content = response.get("content")
    if content is not None and not isinstance(content, str):
        errors.append("response.content 不是字符串或 null")
    calls = response.get("toolCalls")
    if not isinstance(calls, list):
        errors.append("response.toolCalls 不是数组")
        calls = []
    for call in calls:
        if not isinstance(call, dict) or not call.get("name"):
            errors.append(f"非法 toolCall：{call!r}")
            continue
        if call["name"] not in known_tools:
            errors.append(f"未知工具名：{call['name']}")
        if not isinstance(call.get("arguments"), dict):
            errors.append(f"toolCall.arguments 不是对象：{call['name']}")
    if not calls and not (content or "").strip():
        errors.append("response 既无正文也无工具调用（形状不合法）")
    for field in ("promptTokens", "completionTokens"):
        if not isinstance(response.get(field), int) or response[field] < 0:
            errors.append(f"response.{field} 不是非负整数")
    return errors


def observed(cassette: dict) -> dict:
    calls = cassette["response"].get("toolCalls") or []
    return {
        "chain": [{"tool": call.get("name"), "args": call.get("arguments") or {},
                   "status": "", "fabricated": False} for call in calls],
        "answer": cassette["response"].get("content") or "",
        "slot_asked": False,
    }


def load_index(root: Path) -> dict[str, dict]:
    path = root / INDEX_NAME
    if not path.is_file():
        return {}
    index = json.loads(path.read_text(encoding="utf-8"))
    return {entry["key"]: entry for entry in index.get("entries", []) if entry.get("key")}


def main() -> int:
    parser = argparse.ArgumentParser(description="LLM 录放门禁（0 token）")
    parser.add_argument("--dir", default=str(DEFAULT_DIR), help="cassette 根目录")
    parser.add_argument("--update-baseline", action="store_true",
                        help="显式换代：重写 baseline.json（只在复核过新 cassette 后使用）")
    args = parser.parse_args()

    root = Path(args.dir)
    if not root.is_absolute():
        root = (REPO / root).resolve()
    files = cassette_files(root)
    if not files:
        print(f"FAIL 没有 cassette：{root}（门禁不许空跑）", file=sys.stderr)
        return 1

    known_tools = tool_names()
    judge = load_judge()
    index = load_index(root)
    baseline_path = root / BASELINE_NAME
    if not args.update_baseline and not baseline_path.is_file():
        print(f"FAIL 缺基线：{baseline_path}；先复核 cassette 再 --update-baseline", file=sys.stderr)
        return 1
    baseline = {}
    if baseline_path.is_file():
        baseline = json.loads(baseline_path.read_text(encoding="utf-8")).get("entries", {})

    errors: list[str] = []
    entries: dict[str, dict] = {}
    for path in files:
        rel = path.relative_to(root).as_posix()
        try:
            cassette = json.loads(path.read_text(encoding="utf-8"))
        except Exception as failure:  # noqa: BLE001 —— 损坏即红，错误原样带出
            errors.append(f"{rel}: 无法解析：{failure}")
            continue
        shape = validate_shape(path, cassette, known_tools)
        if shape:
            errors.extend(f"{rel}: {problem}" for problem in shape)
            continue

        entry = index.get(cassette["key"], {})
        expect = entry.get("expect") or {"tool": None}
        judged = judge(expect, observed(cassette))
        entries[rel] = {
            "key": cassette["key"],
            "model": cassette["model"],
            "promptVersion": cassette["promptVersion"],
            "source": cassette["source"],
            "request_sha256": sha256(canonical(cassette["request"])),
            "response_sha256": sha256(canonical(cassette["response"])),
            "judged": judged,
        }

    if not errors and not args.update_baseline:
        for rel, entry in entries.items():
            old = baseline.get(rel)
            if old is None:
                errors.append(f"{rel}: 基线里没有这张卡（新录的卡必须显式更新基线）")
                continue
            for field in ("key", "model", "promptVersion", "source"):
                if old.get(field) != entry[field]:
                    errors.append(f"{rel}: {field} 变了：{old.get(field)} -> {entry[field]}")
            for field in ("request_sha256", "response_sha256"):
                if old.get(field) != entry[field]:
                    errors.append(f"{rel}: {field} 变了（请求或响应被改动）")
            if old.get("judged") != entry["judged"]:
                errors.append(f"{rel}: judge() 结论变了：{old.get('judged')} -> {entry['judged']}")
        for rel in baseline:
            if rel not in entries:
                errors.append(f"{rel}: 基线里的卡在目录中不存在（删除 cassette 必须同步更新基线）")

    tool_ok = sum(1 for entry in entries.values() if entry["judged"].get("tool_ok"))
    checks_ok = sum(1 for entry in entries.values() if entry["judged"].get("checks_ok"))
    print(f"cassette {len(entries)} 张（{len({e['model'] for e in entries.values()})} 个模型）；"
          f"judge tool_ok {tool_ok}/{len(entries)}，checks_ok {checks_ok}/{len(entries)}；0 token")
    if args.update_baseline:
        payload = {"version": 1,
                   "note": "由 scripts/verify_llm_replay.py --update-baseline 生成；差异必须人工复核",
                   "entries": entries}
        # 行尾钉死 LF：基线是入仓的回归基准，不该随生成机器（Windows 默认 CRLF）整份翻 diff。
        with baseline_path.open("w", encoding="utf-8", newline="\n") as out:
            out.write(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
        print(f"已写基线：{baseline_path}")
        return 0
    if errors:
        print("FAIL 录放门禁：", file=sys.stderr)
        for problem in errors:
            print(f"  - {problem}", file=sys.stderr)
        print("确认差异是预期的之后，显式运行 --update-baseline 并在 Handoff 记录原因。", file=sys.stderr)
        return 1
    print("PASS 录放门禁：cassette、key/路径、基线与 judge() 结论均未漂移")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
