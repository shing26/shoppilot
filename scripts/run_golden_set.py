#!/usr/bin/env python3
"""UX golden set：20–30 条买家侧可复跑判据。

不是替代 eval/cases-part1-3 的 180 条 gold，而是把「买家可感知的完整旅程」钉成
一组小而稳定的回归：意图、工具结果、引用、降级工单、跨店隔离、缓存命中、可读答案。

用法：
  python scripts/run_golden_set.py --base http://127.0.0.1:18082
  python scripts/run_golden_set.py --base http://127.0.0.1:8082 --limit 5 --tag smoke

只有标准库；不依赖 pytest / locust。输出写到 eval/results/golden-<时间>-<tag>.{json,md}。
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CASES = REPO / "eval" / "golden-ux-30.jsonl"
RESULTS = REPO / "eval" / "results"
PACE_SECONDS = 0.35


def env_value(key: str) -> str:
    value = os.environ.get(key, "").strip()
    if value:
        return value
    env = REPO / ".env"
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.strip().startswith(key + "="):
                return line.split("=", 1)[1].strip()
    return ""


def http(method: str, base: str, path: str, body=None, token: str = "", ops: str = ""):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if ops:
        headers["X-Ops-Token"] = ops
    request = urllib.request.Request(base.rstrip("/") + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            raw = response.read().decode("utf-8", "replace")
            return response.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as failure:
        raw = failure.read().decode("utf-8", "replace")
        try:
            return failure.code, json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            return failure.code, {"message": raw}
    except Exception as failure:  # noqa: BLE001 - 网络失败要进报告，不中断整批
        return 0, {"message": str(failure)}


def login(base: str, tenant: str, password: str) -> str:
    status, body = http("POST", base, "/auth/login",
                        {"tenantId": tenant, "username": "buyer", "password": password})
    if status != 200 or not isinstance(body, dict) or not body.get("token"):
        raise RuntimeError(f"login failed tenant={tenant} status={status} body={body}")
    return body["token"]


def chat(base: str, token: str, query: str, conversation: str):
    return http("POST", base, "/api/v1/support/chat",
                {"query": query, "idempotencyToken": conversation}, token=token)


def set_fault(base: str, token: str, ops: str, mode: str):
    return http("PUT", base, "/api/v1/support/ops/llm-fault", {"mode": mode}, token=token, ops=ops)


def flush_cache(base: str, token: str, ops: str):
    return http("POST", base, "/api/v1/support/ops/cache/flush", token=token, ops=ops)


def plan_steps(result):
    plan = result.get("plan") or []
    return plan if isinstance(plan, list) else []


def tool_status(result, expected_tool: str):
    wanted = expected_tool.upper()
    for step in plan_steps(result):
        tool = str(step.get("tool") or "")
        if tool.upper() == wanted or tool.upper() == expected_tool:
            return str(step.get("status") or "")
    return ""


def score_case(case, result, second=None):
    expect = case.get("expect") or {}
    checks = {}
    answer = str(result.get("answer") or "")
    failures = []

    expected_intent = expect.get("intent")
    if expected_intent:
        checks["intent"] = result.get("intent") == expected_intent

    expected_tool = expect.get("tool")
    if expected_tool:
        checks["tool"] = bool(tool_status(result, expected_tool))
    expected_tool_status = expect.get("toolStatus")
    if expected_tool_status and expected_tool:
        checks["toolStatus"] = tool_status(result, expected_tool) == expected_tool_status

    if expect.get("noTool"):
        checks["noTool"] = not plan_steps(result)

    for keyword in expect.get("answerContains", []):
        checks["answerContains:" + keyword] = keyword in answer
    for keyword in expect.get("answerNotContains", []):
        checks["answerNotContains:" + keyword] = keyword not in answer
    if expect.get("nonEmptyAnswer"):
        checks["nonEmptyAnswer"] = bool(answer.strip())

    min_citations = expect.get("minCitations")
    if min_citations:
        checks["citations"] = len(result.get("citationDetails") or []) >= int(min_citations)

    expected_fallback = expect.get("fallback")
    if expected_fallback:
        checks["fallback"] = str(result.get("fallbackReason") or "") == expected_fallback
    if expect.get("ticket"):
        checks["ticket"] = bool(result.get("ticketId"))
    if expect.get("noTicket"):
        checks["noTicket"] = not bool(result.get("ticketId"))

    expected_cache = expect.get("cache")
    if expected_cache:
        subject = second or result
        checks["cache"] = str(subject.get("cacheLayer") or "").upper() in [c.upper() for c in expected_cache]

    for keyword in expect.get("answerNotLeak", []):
        subject = second or result
        checks["answerNotLeak:" + keyword] = keyword not in str(subject.get("answer") or "")

    for name, passed in checks.items():
        if passed is False:
            failures.append(name)
    return checks, failures


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8082")
    parser.add_argument("--cases", default=str(CASES))
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--tag", default="demo")
    args = parser.parse_args(argv)

    password = env_value("SHOPPILOT_IDENTITY_DEMO_PASSWORD")
    ops = env_value("SHOPPILOT_OPS_TOKEN")
    if not password:
        print("missing SHOPPILOT_IDENTITY_DEMO_PASSWORD；先跑 python scripts/bootstrap_env.py", file=sys.stderr)
        return 2

    cases = [json.loads(line) for line in Path(args.cases).read_text(encoding="utf-8").splitlines() if line.strip()]
    if args.limit:
        cases = cases[:args.limit]

    tokens = {}
    rows = []
    started = time.time()
    try:
        for index, case in enumerate(cases, start=1):
            tenant = case.get("tenant", "T001")
            if tenant not in tokens:
                tokens[tenant] = login(args.base, tenant, password)
            token = tokens[tenant]
            fault = case.get("fault")
            case_started = time.perf_counter()
            try:
                if fault:
                    if not ops:
                        raise RuntimeError("缺 SHOPPILOT_OPS_TOKEN，无法注入故障")
                    flush_cache(args.base, token, ops)
                    set_fault(args.base, token, ops, fault)
                time.sleep(PACE_SECONDS)
                status, body = chat(args.base, token, case["query"], f"golden-{case['id']}-{time.time_ns()}")
                second = None
                if case.get("repeat"):
                    time.sleep(1.2)
                    status2, second = chat(args.base, token, case["query"], f"golden-{case['id']}-second-{time.time_ns()}")
                    if status2 == 200:
                        body = second
            finally:
                if fault and ops:
                    set_fault(args.base, token, ops, "none")

            if status != 200:
                checks, failures = {}, [f"http:{status}:{body}"]
                result = {}
            else:
                checks, failures = score_case(case, body, second)
                result = body
            rows.append({
                "id": case["id"],
                "category": case.get("category", "未分类"),
                "tenant": tenant,
                "query": case["query"],
                "status": status,
                "checks": checks,
                "failures": failures,
                "passed": not failures,
                "intent": result.get("intent"),
                "cacheLayer": (second or result).get("cacheLayer") if isinstance((second or result), dict) else None,
                "fallbackReason": result.get("fallbackReason"),
                "ticketId": result.get("ticketId"),
                "answer": str(result.get("answer") or "")[:300],
                "latencyMs": int((time.perf_counter() - case_started) * 1000),
            })
            mark = "PASS" if not failures else "FAIL"
            print(f"{mark} {case['id']} [{rows[-1]['category']}] {case['query']}  {','.join(failures)}")
            time.sleep(PACE_SECONDS)
    except KeyboardInterrupt:
        print("interrupted", file=sys.stderr)

    elapsed = time.time() - started
    passed = sum(1 for row in rows if row["passed"])
    by_category = {}
    for row in rows:
        bucket = by_category.setdefault(row["category"], {"pass": 0, "total": 0})
        bucket["total"] += 1
        bucket["pass"] += 1 if row["passed"] else 0

    RESULTS.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    tag = ("-" + args.tag) if args.tag else ""
    json_path = RESULTS / f"golden-ux-{stamp}{tag}.json"
    md_path = RESULTS / f"golden-ux-{stamp}{tag}.md"
    payload = {
        "generatedAt": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
        "base": args.base,
        "mode": env_value("SHOPPILOT_LLM_MODE") or "unknown",
        "cases": len(rows),
        "passed": passed,
        "failed": len(rows) - passed,
        "elapsedSeconds": round(elapsed, 1),
        "byCategory": by_category,
        "rows": rows,
    }
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    lines = [
        "# UX golden set",
        "",
        f"- 运行：{payload['generatedAt']}",
        f"- 网关：{args.base} · 模式：{payload['mode']}",
        f"- 结果：**{passed}/{len(rows)} PASS**（{len(rows) - passed} FAIL，{elapsed:.1f}s）",
        "",
        "| 类别 | 通过 | 总数 |",
        "| --- | --- | --- |",
    ]
    for category, bucket in by_category.items():
        lines.append(f"| {category} | {bucket['pass']} | {bucket['total']} |")
    lines += ["", "| ID | 类别 | 问题 | 结果 | 失败项 |", "| --- | --- | --- | --- | --- |"]
    for row in rows:
        result = "PASS" if row["passed"] else "FAIL"
        failures = ", ".join(row["failures"]) or "—"
        query = row["query"].replace("|", "\\|")
        lines.append(f"| {row['id']} | {row['category']} | {query} | {result} | {failures} |")
    lines += ["", "> 判据是行为面：intent / 工具结果 / 引用 / 缓存 / 降级工单 / 跨店隔离 / 答案关键词。", ""]
    md_path.write_text("\n".join(lines), encoding="utf-8")

    print(f"\n{passed}/{len(rows)} PASS · {elapsed:.1f}s")
    print(f"json: {json_path}")
    print(f"md:   {md_path}")
    return 0 if passed == len(rows) else 1


if __name__ == "__main__":
    raise SystemExit(main())
