#!/usr/bin/env python3
"""答案级评测门禁（round48 票 202 / ADR 0083）——0 token、不连模型、不连检索。

它守什么：

1. 60 条案例都有对应的 cassette（缺卡即红）；
2. cassette 里的请求与**当前**案例 / 知识库 / 提示词重建出来的请求逐字节一致
   （改了条款、提示词或案例而没重录，这里红）；
3. 答案正文没有漂移（answer 摘要与基线一致）；
4. 事实判分结论没有漂移（required 命中、forbidden 命中与基线一致）；
5. 判分器自己的红绿对照通过。

它不守什么：不证明模型漂移、不覆盖检索排序 / 店铺人格 / 多轮工具第二跳 / 工具真实执行。
这些边界写在票 202 的 Handoff 与 docs/EVIDENCE.md。

用法：
  python scripts/verify_answer_eval.py
  python scripts/verify_answer_eval.py --update-baseline   # 复核新卡后才准跑
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from answer_cases import (CASSETTE_DIR, DEFAULT_MODEL, REPO, build_request, canonical,
                          cassette_key, cassette_path, load_cases, load_tool_descriptors,
                          prompt_text, prompt_version, sha256)
from answer_facts import judge, selfcheck

BASELINE_NAME = "baseline.json"


def cassette_files(root: Path) -> list[Path]:
    return sorted(path for path in root.glob("*/*/*.json")
                  if path.name not in (BASELINE_NAME, "index.json"))


def pin_python_key_against_java() -> list[str]:
    """Python 的 key 实现必须与 Java 录出来的卡逐字节一致；不一致就等于两套钥匙。"""
    errors = []
    root = REPO / "eval" / "llm-cassettes"
    for path in cassette_files(root):
        try:
            cassette = json.loads(path.read_text(encoding="utf-8"))
        except Exception as failure:  # noqa: BLE001
            errors.append(f"{path.name}: 无法解析：{failure}")
            continue
        recomputed = cassette_key(cassette["model"], cassette["promptVersion"], cassette["request"])
        if recomputed != cassette.get("key") or path.stem != recomputed:
            errors.append(f"{path.name}: Python key {recomputed[:12]} 与 Java 卡 "
                          f"{str(cassette.get('key'))[:12]} 不一致")
    return errors


def pin_tool_fixture() -> list[str]:
    """动作题的 tool schema 取自已入仓的 201 动作卡；两边漂了就是 fixture 悄悄过期。"""
    fixture = load_tool_descriptors()
    for path in cassette_files(REPO / "eval" / "llm-cassettes"):
        cassette = json.loads(path.read_text(encoding="utf-8"))
        if cassette["request"]["tools"]:
            if cassette["request"]["tools"] != fixture:
                return [f"eval/answer-tool-descriptors.json 与 201 动作卡 {path.name} 的 tools 不一致"]
            return []
    return ["201 目录里找不到带 tools 的动作卡，无法钉住 tool schema fixture"]


def main() -> int:
    parser = argparse.ArgumentParser(description="答案级评测门禁（0 token）")
    parser.add_argument("--update-baseline", action="store_true",
                        help="显式换代：重写 baseline.json（只在复核过新答案后使用）")
    args = parser.parse_args()

    problems = selfcheck()
    if problems:
        print("FAIL 事实判分自检：", file=sys.stderr)
        for problem in problems:
            print("  - " + problem, file=sys.stderr)
        return 1

    errors = pin_python_key_against_java() + pin_tool_fixture()
    cases = load_cases()
    version = prompt_version()
    system_prompt = prompt_text(version)
    index_path = CASSETTE_DIR / "index.json"
    index = json.loads(index_path.read_text(encoding="utf-8")) if index_path.is_file() else {}
    model = index.get("model") or DEFAULT_MODEL
    baseline_path = CASSETTE_DIR / BASELINE_NAME
    if not args.update_baseline and not baseline_path.is_file():
        print(f"FAIL 缺基线：{baseline_path}；先复核 cassette 再 --update-baseline", file=sys.stderr)
        return 1
    baseline = {}
    if baseline_path.is_file():
        baseline = json.loads(baseline_path.read_text(encoding="utf-8")).get("cases", {})

    entries: dict[str, dict] = {}
    for case in cases:
        request = build_request(case, system_prompt)
        key = cassette_key(model, version, request)
        target = cassette_path(CASSETTE_DIR, model, version, key)
        if not target.is_file():
            errors.append(f"{case['id']}: 缺 cassette {target.relative_to(REPO).as_posix()}"
                          f"（key={key[:12]}…，{case['query']}）")
            continue
        cassette = json.loads(target.read_text(encoding="utf-8"))
        rel = target.relative_to(CASSETTE_DIR).as_posix()
        for field, expected in (("key", key), ("promptVersion", version)):
            if cassette.get(field) != expected:
                errors.append(f"{case['id']}: cassette.{field}={cassette.get(field)!r} 期望 {expected!r}")
        if cassette.get("source") not in ("dev", "local"):
            errors.append(f"{case['id']}: source={cassette.get('source')!r} 不是真实模型录制")
        if cassette.get("request") != request:
            errors.append(f"{case['id']}: cassette 请求与当前案例/条款/提示词不一致"
                          f"（请求摘要 {sha256(canonical(cassette.get('request')))[:12]} → "
                          f"{sha256(canonical(request))[:12]}），必须重录")
            continue
        response = cassette.get("response") or {}
        answer = response.get("content") or ""
        facts = judge(case, answer)
        entries[case["id"]] = {
            "cassette": rel,
            "key": key,
            "model": cassette.get("model"),
            "promptVersion": version,
            "source": cassette.get("source"),
            "request_sha256": sha256(canonical(request)),
            "answer_sha256": sha256(answer),
            "facts": facts,
        }

    if not errors and not args.update_baseline:
        for case_id, entry in entries.items():
            old = baseline.get(case_id)
            if old is None:
                errors.append(f"{case_id}: 基线里没有这条案例（新录的答案必须显式更新基线）")
                continue
            for field in ("key", "model", "promptVersion", "source", "request_sha256", "answer_sha256"):
                if old.get(field) != entry[field]:
                    errors.append(f"{case_id}: {field} 变了（请求或答案被改动）")
            if old.get("facts") != entry["facts"]:
                errors.append(f"{case_id}: 事实判分变了：{old.get('facts')} -> {entry['facts']}")
        for case_id in baseline:
            if case_id not in entries:
                errors.append(f"{case_id}: 基线里的案例在本次评测中不存在（删案例必须同步更新基线）")

    policy = [entry for case_id, entry in entries.items() if case_id.startswith("ANS-POL-")]
    action = [entry for case_id, entry in entries.items() if case_id.startswith("ANS-ACT-")]
    ok = [case_id for case_id, entry in entries.items() if entry["facts"]["ok"]]

    def rate(group):
        passed = sum(1 for entry in group if entry["facts"]["ok"])
        return f"{passed}/{len(group)}"

    print(f"答案案例 {len(entries)} 条（政策 {rate(policy)} 通过，动作 {rate(action)} 通过，"
          f"合计 {len(ok)}/{len(entries)}）；0 token")

    if args.update_baseline:
        payload = {"version": 1,
                   "note": "由 scripts/verify_answer_eval.py --update-baseline 生成；差异必须人工复核",
                   "cases": entries}
        with baseline_path.open("w", encoding="utf-8", newline="\n") as out:
            out.write(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
        print(f"已写基线：{baseline_path}")
        _print_failures(entries, verbose=False)
        return 0

    if errors:
        print("FAIL 答案级评测门禁：", file=sys.stderr)
        for problem in errors:
            print("  - " + problem, file=sys.stderr)
        print("确认差异是预期的之后，显式运行 --update-baseline 并在 Handoff 记录原因。", file=sys.stderr)
        return 1

    _print_failures(entries, verbose=True)
    print("PASS 答案级评测门禁：60 条 cassette、请求、答案与事实判分均未漂移")
    return 0


def _print_failures(entries: dict[str, dict], verbose: bool) -> None:
    failures = [(case_id, entry) for case_id, entry in entries.items() if not entry["facts"]["ok"]]
    if not failures:
        return
    print(f"读数：{len(failures)} 条未通过（基线如实记录，不删案例、不放宽 forbidden）")
    if not verbose:
        return
    for case_id, entry in failures:
        facts = entry["facts"]
        detail = []
        if facts["missing"]:
            detail.append("缺 " + " / ".join(facts["missing"]))
        if facts["forbidden_hits"]:
            detail.append("命中禁用 " + " / ".join(facts["forbidden_hits"]))
        if facts["answer_empty"]:
            detail.append("空答案")
        print(f"  - {case_id}: {'；'.join(detail)}")


if __name__ == "__main__":
    raise SystemExit(main())
