#!/usr/bin/env python3
"""录制答案级评测的模型答复（round48 票 202 / ADR 0083）。

一次案例一次往返，写进 `eval/answer-cassettes/<model>/<promptVersion>/<key>.json`。
cassette 与 201 同格式：key / model / promptVersion / recordedAt / source / request / response。
不会写 201 的 `eval/llm-cassettes/`，两个门禁各管各的目录。

录制要花钱、要网络，所以不在 `verify` 里跑，也不进 CI：
  python scripts/record_answer_cassettes.py                 # 缺卡的才录
  python scripts/record_answer_cassettes.py --only ANS-POL-RET-01
  python scripts/record_answer_cassettes.py --overwrite     # 显式重录

录完先人工看读数，确认差异是预期的，再显式换代：
  python scripts/verify_answer_eval.py --update-baseline
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from answer_cases import (CASSETTE_DIR, DEFAULT_MODEL, TEMPERATURE, build_request, cassette_key,
                          cassette_path, load_cases, prompt_text, prompt_version)

REPO = Path(__file__).resolve().parent.parent
RETRIES = 3

# 显式空 ProxyHandler：Windows 注册表里的系统代理会被 urllib 自动拾取，
# 而本机 127.0.0.1:31181 并没有代理在听——Java 客户端不读它，两边行为会分叉。
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def load_env() -> dict[str, str]:
    values = dict(os.environ)
    for candidate in (REPO / ".env", Path(".env")):
        if not candidate.is_file():
            continue
        for line in candidate.read_text(encoding="utf-8").splitlines():
            trimmed = line.strip()
            if not trimmed or trimmed.startswith("#") or "=" not in trimmed:
                continue
            key, value = trimmed.split("=", 1)
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            values.setdefault(key.strip(), value)
    return values


def wire_messages(messages: list[dict]) -> list[dict]:
    wire = []
    for message in messages:
        role = message["role"]
        if role == "assistant" and message["toolCalls"]:
            wire.append({"role": "assistant", "content": message["content"],
                         "tool_calls": [{"id": call["id"], "type": "function",
                                         "function": {"name": call["name"],
                                                      "arguments": json.dumps(call["arguments"],
                                                                              ensure_ascii=False)}}
                                        for call in message["toolCalls"]]})
        elif role == "tool":
            wire.append({"role": "tool", "tool_call_id": message["toolCallId"],
                         "content": message["content"]})
        else:
            wire.append({"role": role, "content": message["content"]})
    return wire


def call_model(base_url: str, api_key: str, model: str, request: dict) -> dict:
    payload = {"model": model, "messages": wire_messages(request["messages"]),
               "temperature": request["temperature"], "max_tokens": request["maxTokens"]}
    if request["tools"]:
        payload["tools"] = request["tools"]
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    endpoint = base_url.rstrip("/") + "/chat/completions"
    last_error = None
    for attempt in range(1, RETRIES + 1):
        http_request = urllib.request.Request(endpoint, data=body, method="POST", headers={
            "Content-Type": "application/json", "Authorization": f"Bearer {api_key}"})
        try:
            with OPENER.open(http_request, timeout=60) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as failure:
            last_error = failure
            if failure.code not in (429, 500, 502, 503, 504) or attempt == RETRIES:
                detail = failure.read().decode("utf-8", "replace")[:300]
                raise RuntimeError(f"HTTP {failure.code}: {detail}") from failure
        except urllib.error.URLError as failure:
            last_error = failure
            if attempt == RETRIES:
                raise RuntimeError(f"网络错误：{failure}") from failure
        time.sleep(2 * attempt)
    raise RuntimeError(f"重试耗尽：{last_error}")


def reply_from(data: dict) -> dict:
    choice = (data.get("choices") or [{}])[0]
    message = choice.get("message") or {}
    calls = []
    for call in message.get("tool_calls") or []:
        function = call.get("function") or {}
        try:
            arguments = json.loads(function.get("arguments") or "{}")
        except json.JSONDecodeError:
            arguments = {"_raw": function.get("arguments")}
        calls.append({"id": call.get("id") or "", "name": function.get("name") or "",
                      "arguments": arguments})
    usage = data.get("usage") or {}
    return {"content": message.get("content"),
            "toolCalls": calls,
            "promptTokens": int(usage.get("prompt_tokens") or 0),
            "completionTokens": int(usage.get("completion_tokens") or 0)}


def main() -> int:
    parser = argparse.ArgumentParser(description="录制答案级 cassette（真实模型，会计费）")
    parser.add_argument("--only", action="append", default=[], help="只录指定案例 id，可重复")
    parser.add_argument("--overwrite", action="store_true", help="已存在的卡也重录")
    args = parser.parse_args()

    env = load_env()
    api_key = env.get("SHOPPILOT_LLM_API_KEY", "").strip()
    if not api_key or api_key.startswith("sk-replace"):
        print("FAIL 缺 SHOPPILOT_LLM_API_KEY（在 .env 或环境变量里）；录制需要真实模型", file=sys.stderr)
        return 2
    base_url = env.get("SHOPPILOT_LLM_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
    model = env.get("SHOPPILOT_LLM_MODEL", DEFAULT_MODEL)
    source = env.get("SHOPPILOT_LLM_MODE", "dev").strip().lower()
    if source not in ("dev", "local"):
        print(f"FAIL source={source!r} 不是 dev/local：Mock/演示输出不能进答案基线", file=sys.stderr)
        return 2

    version = prompt_version()
    system_prompt = prompt_text(version)
    cases = load_cases()
    if args.only:
        wanted = set(args.only)
        cases = [case for case in cases if case["id"] in wanted]
        missing = wanted - {case["id"] for case in cases}
        if missing:
            print(f"FAIL 未知案例：{sorted(missing)}", file=sys.stderr)
            return 2

    recorded = skipped = 0
    prompt_tokens = completion_tokens = 0
    for index, case in enumerate(cases, start=1):
        request = build_request(case, system_prompt)
        key = cassette_key(model, version, request)
        target = cassette_path(CASSETTE_DIR, model, version, key)
        if target.is_file() and not args.overwrite:
            skipped += 1
            print(f"[{index}/{len(cases)}] {case['id']} 已有卡，跳过")
            continue
        data = call_model(base_url, api_key, model, request)
        reply = reply_from(data)
        cassette = {"key": key, "model": model, "promptVersion": version,
                    "recordedAt": datetime.now(timezone.utc).isoformat(),
                    "source": source, "request": request, "response": reply}
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(cassette, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                             encoding="utf-8", newline="\n")
        temporary.replace(target)
        recorded += 1
        prompt_tokens += reply["promptTokens"]
        completion_tokens += reply["completionTokens"]
        tools = ",".join(call["name"] for call in reply["toolCalls"]) or "-"
        print(f"[{index}/{len(cases)}] {case['id']} 录到 {reply['promptTokens']}+"
              f"{reply['completionTokens']} token，tools={tools}，answer={len(reply['content'] or '')} 字")
        time.sleep(0.3)

    index_payload = {"generatedAt": datetime.now(timezone.utc).isoformat(), "model": model,
                     "promptVersion": version, "source": source,
                     "cases": [{"id": case["id"],
                                "cassette": cassette_path(CASSETTE_DIR, model, version,
                                                          cassette_key(model, version,
                                                                       build_request(case, system_prompt)))
                                .relative_to(CASSETTE_DIR).as_posix()}
                               for case in load_cases()]}
    CASSETTE_DIR.mkdir(parents=True, exist_ok=True)
    (CASSETTE_DIR / "index.json").write_text(
        json.dumps(index_payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8", newline="\n")
    print(f"完成：新录 {recorded} 条，跳过 {skipped} 条；消耗 {prompt_tokens}+{completion_tokens} token")
    print("下一步：python scripts/verify_answer_eval.py（先看读数）；确认后再 --update-baseline")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
