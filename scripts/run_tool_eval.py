"""Tool Calling 标注评测（ticket 16、ADR 0001）。

两个子指标分开报，绝不合并成一个数字：
  选对工具  —— 期望工具出现在实际调用链里（链式场景允许先查订单再查物流）
  填对参数  —— 期望参数键全部出现且值相等（归一化后比较；reason/amountFen 这类自由文本
               只查存在性，口径写在 looseArgs 里）
另记两条红线：
  猜槽位  —— 缺槽位样本里模型自己编了订单号，单独计数，算错不算聪明
  结构化追问 —— slot_ask 事件是否真的发出（小模型常用自然语言追问，两者分开报）

跑前打印 token 预估并受日预算熔断约束（dev 模式）。local 模式零 API 费用，
但报告里必须标注"非验收口径"：验收数字只能在 dev 模式下取。

用法:
  python scripts/run_tool_eval.py --limit 10      # 冒烟
  python scripts/run_tool_eval.py                 # 全量 180 条
  python scripts/run_tool_eval.py --dry-run       # 只估不跑
"""

import argparse
import concurrent.futures
import csv
import datetime as dt
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CASES = REPO / "eval" / "tool-cases.jsonl"
RESULTS = REPO / "eval" / "results"

TOOL_API_NAMES = {
    "QUERY_ORDER_DETAIL": "queryOrderDetail",
    "QUERY_LOGISTICS": "queryLogistics",
    "MODIFY_DELIVERY_ADDRESS": "modifyDeliveryAddress",
    "APPLY_REFUND": "applyRefund",
}
ASK_MARKERS = re.compile(r"(请问|请提供|麻烦提供|请补充|方便提供|告诉我|哪个订单|哪一单|订单号是|哪个单|请问是)")
ACCEPT_TOOL = 0.95
# 每轮跑测换一个会话前缀：否则上一轮的 slot_ask 计数会漏到这一轮，
# 第二次跑同一条缺槽位样本会直接 ESCALATE，数字看起来像模型变笨了
RUN_ID = dt.datetime.now().strftime("%m%d%H%M%S")
HARD_FAIL_INTENT = 0.80


def http_json(url, payload=None, headers=None, timeout=240):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(url, data=body, method="POST" if body else "GET")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    if body:
        request.add_header("Content-Type", "application/json; charset=utf-8")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read().decode("utf-8")
    return json.loads(raw) if raw else {}


def normalize_value(value):
    return "" if value is None else re.sub(r"\s+", "", str(value))


def mock_token(base, tenant, customer):
    return http_json(f"{base}/auth/mock-token", {"tenantId": tenant, "customerId": customer})["token"]


def parse_trace(result):
    """把 trace 里的 TOOL_EXEC 步骤解析成调用链。

    步骤形态：`TOOL=STATUS modelArgs={...}` / `TOOL missing=[..] modelArgs={...}` /
    `fabricated-orderNo 已拦截 modelArgs={...}`。modelArgs 是模型自己抽的参数，
    不是工具返回体——评测要量的是前者。
    """
    chain = []
    for step in result.get("trace") or []:
        if step.get("state") != "TOOL_EXEC":
            continue
        detail = (step.get("detail") or "").strip()
        head, _, tail = detail.partition(" modelArgs=")
        args = {}
        if tail:
            try:
                args = json.loads(tail.strip())
            except json.JSONDecodeError:
                args = {}
        if head.startswith("fabricated-orderNo"):
            chain.append({"tool": None, "status": "FABRICATED", "args": args, "fabricated": True})
            continue
        head = head[len("gateway-derived "):] if head.startswith("gateway-derived ") else head
        tool_part, _, status = head.partition("=")
        enum_name = tool_part.strip().split()[0] if tool_part.strip() else ""
        chain.append({
            "tool": TOOL_API_NAMES.get(enum_name, enum_name.lower()),
            "status": status.strip().split(" ")[0] if status else "",
            "args": args,
            "fabricated": False,
        })
    return chain


def score_case(case, result):
    expect = case["expect"]
    chain = parse_trace(result)
    expected_tool = expect.get("tool")
    names = [link["tool"] for link in chain if link["tool"]]
    tool_ok = (expected_tool in names) if expected_tool else (not names)

    link = next((l for l in chain if l["tool"] == expected_tool), None) if expected_tool else None
    actual_args = link["args"] if link else {}

    loose = set(expect.get("looseArgs") or [])
    expected_args = expect.get("args") or {}
    args_detail = []
    for key, want in expected_args.items():
        got = actual_args.get(key)
        if key in loose:
            if not normalize_value(got):
                args_detail.append(f"{key}: 期望非空（自由文本，只查存在性）")
        elif normalize_value(got) != normalize_value(want):
            args_detail.append(f"{key}: want={want} got={got}")
    args_scored = bool(expected_tool) and bool(expected_args)
    args_ok = (args_scored and not args_detail) if expected_tool else ""

    fabricated = []
    for key in expect.get("mustNotContainArgs") or []:
        value = next((l["args"].get(key) for l in chain if normalize_value(l["args"].get(key))), None)
        if value is not None:
            fabricated.append(f"{key}={value}")
    if any(l["fabricated"] for l in chain):
        # 网关自己拦下的编造订单号，同样是"猜槽位"，不能因为没打出去就不算
        fabricated.append("model_invented_orderNo")

    slot_actual = bool(result.get("slotAsked"))
    asked_in_prose = bool(ASK_MARKERS.search(result.get("answer") or ""))
    slot_structured_ok = slot_actual == bool(expect.get("slotAsk"))
    # 缺槽位样本的硬性质是"不许编"，追问走结构化还是自然语言分开记
    asked_ok = (not fabricated) and (slot_actual or asked_in_prose or not expect.get("slotAsk"))

    intent_actual = result.get("intent")
    escalate_ok = True
    if expect.get("escalate"):
        escalate_ok = intent_actual == "ESCALATE" or bool(result.get("fallbackReason"))
    admission_ok = True
    if expect.get("cacheAdmissible") is False:
        admission_ok = result.get("cacheLayer") in (None, "NONE")
    status_ok = True
    if expect.get("expectStatus") and link:
        status_ok = (link["status"] or "").upper() == expect["expectStatus"]

    checks_ok = all([tool_ok, args_ok is not False, slot_structured_ok, not fabricated,
                     escalate_ok, admission_ok, status_ok])
    return {
        "tool_ok": tool_ok,
        "args_scored": args_scored,
        "args_ok": args_ok,
        "slot_structured_ok": slot_structured_ok,
        "asked_ok": asked_ok,
        "fabricated": ";".join(fabricated),
        "escalate_ok": escalate_ok,
        "admission_ok": admission_ok,
        "status_ok": status_ok,
        "checks_ok": checks_ok,
        "actual_tool": ",".join(names),
        "actual_args": json.dumps(actual_args, ensure_ascii=False),
        "args_detail": ";".join(args_detail),
        "tool_status": (link["status"] if link else ""),
        "slot_actual": slot_actual,
        "asked_in_prose": asked_in_prose,
    }


def run_case(base, case, retries=3):
    tenant = case.get("tenant", "T001")
    customer = case.get("customer", "C001")
    token = mock_token(base, tenant, customer)
    payload = {"query": case["query"]}
    attempt, rate_limited = 0, 0
    while True:
        attempt += 1
        started = time.perf_counter()
        try:
            result = http_json(f"{base}/api/v1/support/chat", payload,
                               {"Authorization": f"Bearer {token}",
                                "X-Conversation-Id": f"eval-{RUN_ID}-{case['id']}-{attempt}"})
            return result, time.perf_counter() - started, rate_limited, None
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", "replace")
            if error.code == 429 and attempt <= retries:
                rate_limited += 1
                time.sleep(1.5 * attempt)
                continue
            return None, time.perf_counter() - started, rate_limited, f"HTTP {error.code} {body[:200]}"
        except (urllib.error.URLError, OSError) as error:  # 含连接重置与读超时
            if attempt <= retries:
                time.sleep(1.5 * attempt)
                continue
            return None, time.perf_counter() - started, rate_limited, f"{type(error).__name__}: {error}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8082")
    parser.add_argument("--limit", type=int, default=0, help="只跑前 N 条，冒烟用")
    parser.add_argument("--only-intent", default="", help="只跑某个意图，定位问题用")
    parser.add_argument("--concurrency", type=int, default=2)
    parser.add_argument("--dry-run", action="store_true", help="只打印预估与口径，不发请求")
    parser.add_argument("--force", action="store_true", help="跳过预算熔断预检")
    parser.add_argument("--ops-token", default="dev-ops-token")
    parser.add_argument("--tag", default="", help="输出文件名后缀，区分同模式不同端点（如 dev-localcompat）")
    args = parser.parse_args()

    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines() if line.strip()]
    if args.only_intent:
        cases = [c for c in cases if c["intent"] == args.only_intent]
    if args.limit:
        # 用例集是按意图分组的（每意图 18 条），直接取前 N 条只会覆盖一两个意图，冒烟就白跑了。
        # 轮流从每个意图取，N 条里十个意图都有份。
        by_intent = {}
        for case in cases:
            by_intent.setdefault(case["intent"], []).append(case)
        picked = []
        rank = 0
        while len(picked) < args.limit and any(rank < len(bucket) for bucket in by_intent.values()):
            for intent in sorted(by_intent):
                bucket = by_intent[intent]
                if rank < len(bucket) and len(picked) < args.limit:
                    picked.append(bucket[rank])
            rank += 1
        cases = picked

    headers = {"X-Ops-Token": args.ops_token,
               "Authorization": "Bearer " + mock_token(args.base, "T001", "C001")}
    circuit = http_json(f"{args.base}/api/v1/support/ops/circuit", headers=headers)
    mode, model = circuit.get("llmMode"), circuit.get("llmModel")
    llm_base = circuit.get("llmBaseUrl") or "unknown"
    used, budget = int(circuit.get("tokensUsedToday") or 0), int(circuit.get("dailyTokenBudget") or 0)

    per_case_tokens = estimate_per_case_tokens(cases)
    projected = per_case_tokens * len(cases)
    print(f"模式={mode} 模型={model} 端点={llm_base} 样本={len(cases)} 并发={args.concurrency}")
    print(f"单条平均消耗预估 {per_case_tokens} tokens -> 本轮预计 {projected} tokens")
    if mode == "dev" and budget > 0:
        remaining = max(0, budget - used)
        print(f"日预算 {used}/{budget}，剩余 {remaining}")
        if projected > remaining and not args.force:
            print("FAIL  预估消耗超过剩余预算，已按 ADR 0012 熔断。加 --force 可强行放行。")
            return 2
    if mode != "dev":
        print("提示：当前不是 dev 模式，本轮数字只验证评测链路与给出本地基线，不作为验收口径。")
    if args.dry_run:
        print("--dry-run：不发评测请求。")
        return 0

    # 写操作会改订单状态，跑之前把四张演示固定单复位，否则后面的用例是在测状态机不是测模型
    try:
        http_json(f"{args.base}/api/v1/support/ops/demo/reset", payload={}, headers=headers)
        print("已复位演示固定单（90001-90004）")
    except (urllib.error.URLError, urllib.error.HTTPError) as reset_failed:
        print(f"WARN  演示固定单复位失败，写类用例可能受状态污染：{reset_failed}")

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    RESULTS.mkdir(parents=True, exist_ok=True)
    rows, done = [], 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.concurrency)) as pool:
        futures = {pool.submit(run_case, args.base, case): case for case in cases}
        for future in concurrent.futures.as_completed(futures):
            case = futures[future]
            result, elapsed, retries, error = future.result()
            done += 1
            base_row = {"id": case["id"], "intent": case["intent"], "kind": case["kind"],
                        "query": case["query"], "tenant": case.get("tenant", "T001"),
                        "customer": case.get("customer", "C001"),
                        "expect_tool": case["expect"].get("tool") or "",
                        "expect_args": json.dumps(case["expect"].get("args") or {}, ensure_ascii=False),
                        "expect_slot": case["expect"].get("slotAsk")}
            if result is None:
                rows.append({**base_row, "error": error, "tool_ok": False, "args_ok": "",
                             "args_scored": False, "slot_structured_ok": False, "asked_ok": False,
                             "fabricated": "", "escalate_ok": False, "admission_ok": True,
                             "status_ok": False, "checks_ok": False, "actual_tool": "",
                             "actual_args": "", "args_detail": "", "tool_status": "",
                             "prompt_tokens": 0, "completion_tokens": 0,
                             "latency_ms": round(elapsed * 1000), "rate_limit_retries": retries,
                             "fallback": "", "intent_actual": "", "triage_layer": "",
                             "cache_layer": "", "slot_actual": False, "asked_in_prose": False})
            else:
                scored = score_case(case, result)
                rows.append({**base_row, "error": "",
                             "prompt_tokens": result.get("promptTokens", 0),
                             "completion_tokens": result.get("completionTokens", 0),
                             "latency_ms": round(elapsed * 1000), "rate_limit_retries": retries,
                             "fallback": result.get("fallbackReason") or "",
                             "intent_actual": result.get("intent") or "",
                             "triage_layer": result.get("triageLayer") or "",
                             "cache_layer": result.get("cacheLayer") or "", **scored})
            if done % 20 == 0 or done == len(cases):
                print(f"  {done}/{len(cases)}")

    rows.sort(key=lambda r: r["id"])
    # 文件名里带上端点类别：dev 指向本地 OpenAI 兼容端点与指向 DashScope 是两组完全不同的证据，
    # 只靠 meta 里的 mode 字段区分，翻 results 目录时会把两者混为一谈。
    slug = f"{mode}-{args.tag}" if args.tag else mode
    detail_path = RESULTS / f"tool-eval-{stamp}-{slug}.csv"
    with detail_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    summary, failures = summarize(rows, mode)
    summary_path = RESULTS / f"tool-eval-{stamp}-{slug}-summary.csv"
    with summary_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(summary[0].keys()))
        writer.writeheader()
        writer.writerows(summary)

    print(f"\n{'intent':16} {'n':>4} {'选对工具':>9} {'填对参数':>9} {'结构化追问':>10} {'猜槽位':>7} {'综合':>7}")
    for row in summary:
        print(f"{row['intent']:16} {row['cases']:>4} {row['tool_accuracy']:>10} {row['args_accuracy']:>10} "
              f"{row['slotask_accuracy']:>11} {row['fabricated_cases']:>8} {row['overall_accuracy']:>8}")
    total_prompt = sum(r["prompt_tokens"] for r in rows)
    total_completion = sum(r["completion_tokens"] for r in rows)
    errors = sum(1 for r in rows if r["error"])
    print(f"\n合计 {len(rows)} 条，请求失败 {errors} 条，prompt {total_prompt} / completion {total_completion} tokens")
    print(f"明细 {detail_path.relative_to(REPO)}；汇总 {summary_path.relative_to(REPO)}")
    RESULTS.joinpath(f"tool-eval-{stamp}-{slug}-meta.json").write_text(json.dumps({
        "mode": mode, "model": model, "llmBaseUrl": llm_base,
        "cases": len(rows), "limit": args.limit,
        "promptTokens": total_prompt, "completionTokens": total_completion,
        "estimatedPerCaseTokens": per_case_tokens, "dailyTokenBudget": budget,
        "tokensUsedBeforeRun": used, "concurrency": args.concurrency,
        "startedAt": stamp, "requestErrors": errors,
        "summary": {r["intent"]: r for r in summary},
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    for failure in failures:
        print("FAIL  " + failure)
    # 验收门禁靠这行 ASCII 标记判断"真的跑到底了"：这台机器上退出码为 0 不代表跑完
    print(f"EVAL DONE cases={len(rows)} errors={errors} mode={mode} limit={args.limit}")
    return 1 if failures else 0


def estimate_per_case_tokens(cases):
    """优先用上一次真实跑测的均值；没有历史就按 900 保守估。"""
    history = sorted(RESULTS.glob("tool-eval-*-meta.json")) if RESULTS.exists() else []
    for meta in reversed(history):
        try:
            payload = json.loads(meta.read_text(encoding="utf-8"))
            total = payload.get("promptTokens", 0) + payload.get("completionTokens", 0)
            if total and payload.get("cases"):
                return round(total / payload["cases"])
        except (json.JSONDecodeError, OSError):
            continue
    return 900


def summarize(rows, mode):
    by_intent = {}
    for row in rows:
        by_intent.setdefault(row["intent"], []).append(row)
    summary, failures = [], []
    for intent, items in sorted(by_intent.items()):
        n = len(items)
        tool_hits = sum(1 for r in items if r["tool_ok"])
        argable = [r for r in items if r["args_scored"]]
        arg_hits = sum(1 for r in argable if r["args_ok"] is True)
        slot_hits = sum(1 for r in items if r["slot_structured_ok"])
        fabricated = sum(1 for r in items if r["fabricated"])
        overall = sum(1 for r in items if r["checks_ok"])
        tool_rate = tool_hits / n
        args_rate = arg_hits / len(argable) if argable else None
        summary.append({
            "intent": intent, "cases": n,
            "tool_accuracy": f"{tool_rate:.1%}",
            "args_accuracy": (f"{args_rate:.1%}" if args_rate is not None else "n/a"),
            "slotask_accuracy": f"{slot_hits / n:.1%}",
            "fabricated_cases": fabricated,
            "overall_accuracy": f"{overall / n:.1%}",
        })
        if mode == "dev":
            if tool_rate < HARD_FAIL_INTENT:
                failures.append(f"{intent} 选对工具 {tool_rate:.1%} < {HARD_FAIL_INTENT:.0%}，判不通过")
            elif tool_rate < ACCEPT_TOOL:
                failures.append(f"{intent} 选对工具 {tool_rate:.1%} < 承诺线 {ACCEPT_TOOL:.0%}")
            if args_rate is not None and args_rate < HARD_FAIL_INTENT:
                failures.append(f"{intent} 填对参数 {args_rate:.1%} < {HARD_FAIL_INTENT:.0%}，判不通过")
            elif args_rate is not None and args_rate < ACCEPT_TOOL:
                failures.append(f"{intent} 填对参数 {args_rate:.1%} < 承诺线 {ACCEPT_TOOL:.0%}")
    return summary, failures


if __name__ == "__main__":
    sys.exit(main())
