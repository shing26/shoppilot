"""round35 票 148：自主性评测集 part10（动态规划 4 轮/2 步 + 反思三态 + 超限收口）。

依据：ADR 0069 §五、票 140 的裁定（30 条 = 15-10-5、`kind: autonomy`、单独成列）、
票 139 的三态判据、票 138 的反思规则六条。**不进 `GOLD_CASE_FILES`**、`build_eval_set.py` 与
`verify_eval_judge.py` 的 `PARTS` 不动（那份只扫 part1-3），也不并进 `eval_suites.py`
（那是 CI 门禁的断言载体；ADR 0050 要求新增判据独立成模块，`eval_task.py` 同形）。

**判据列**（单独成列、**不给总分**、不并入四列/任何现有百分比）：

  * **三态**：`plan[].reflection` / `plan[].attempt` + trace 的 `reflection=` 行 +
    `shoppilot_reflection_total{outcome}` 增量 —— 触发 / 不触发 / 次数用尽。
    三者都读不到时记 `None` = **未观测**（既不进分子也不进分母，与 gold 的"未观测断言"列同一条纪律）。
  * **步数**：`plan[]` 长度（≤4，工具轮与计划步同源）。超限形态下 `plan[]` 为空
    ——`fallback()` 出口不带走 plan（`AgentStateMachine` 的 fallback 构造）——改用 **trace 的工具枪数**
    判"确实打了 4 枪"，这同时是"计划步数与工具轮同源"这条口径的机器读法。
  * **出口**：`fallbackReason ∈ {TOOL_UNAVAILABLE, TOOL_ROUNDS_EXHAUSTED}`；写挂起超限与读超限
    出口相同、路径不同，由 trace 的 `tool-rounds-exhausted write-pending=` 行区分。

**取证列**（**不进判据**、只进明细）：`billed_tokens` / `latency_ms`。成本读
`/api/v1/support/ops/circuit` 的 `tokensUsedToday` 增量（= Redis `shoppilot:llm:tokens:<day>`，
与 `shoppilot_llm_tokens_total` 同源），**不读响应里的 token 字段**——票 140 实测那里有三处缺口
（预算检查分支只报最后一跳、fallback/审批路径报 0、缓存命中报 0），登记为 R12。

**故障诱导**（用例自带的 `fault` 字段，逐例声明、跑完必清零）：

  * `{"mode": "persistent", "failRate": 1.0}` —— 整例期间挂着：判"次数用尽"与"写工具不反思"。
  * `{"mode": "window", "failRate": 1.0}` —— 只烧第一枪：watcher 线程盯着
    `shoppilot_tool_unavailable_total`，一抬头立刻清零，反思重试于是落在干净窗口里
    ——**"读工具③失败 → 换路成功"这一档只有这样才可达**（biz-mock 的故障面是全局 delayMs/failRate，
    没有按工具或一次性的开关）。
  * 缺省 = 不注入：闸类用例靠演示固定单的业务状态确定性复现（跨店单号 → `NOT_FOUND`、
    未发货单查物流 → `STATE_NOT_ALLOWED`）。
  * 窗口没能立起来（计数读不到、或请求压根没打工具）记 `induction=...` 且三态判 `None` = 未观测，
    **不伪装成 PASS 也不伪装成 FAIL**（本仓"未达成照登"的同一口径）。

用例措辞**唯一**（动作类查询不进缓存，同措辞重跑会全额计费、把成本读数压低）：`validate_cases`
把这条也变成机器检查，连带把 15-10-5 / 8-7 / 5-3-2 / 3-2 的分区形状一起钉住——改集子的形状必须
连判据一起改，不许悄悄漂。

用法：
  python scripts/eval_autonomy.py             # 夹具自检（0 token、无网关），CI 那一步用它
  python scripts/run_tool_eval.py --autonomy  # 活体跑批（复用 run_tool_eval 的 HTTP 助手）
"""

import csv
import datetime as dt
import json
import threading
import time
from pathlib import Path

from eval_suites import PACE_SECONDS, executed_tools

REPO = Path(__file__).resolve().parent.parent
RESULTS = REPO / "eval" / "results"
CASES = REPO / "eval" / "cases-part10-autonomy.jsonl"

KIND = "autonomy"
ZONES = ("plan", "reflection", "rounds")
STATES = ("triggered", "none", "exhausted")

# 分区形状（票 140 的裁定）：改这里等于改集子的规模与配比，必须连 ADR/spec 一起改。
PARTITION = {
    "total": 30,
    "plan": {"total": 15, "steps": {2: 7, 4: 8}},
    "reflection": {"total": 10, "states": {"triggered": 5, "none": 3, "exhausted": 2}},
    "rounds": {"total": 5, "writePending": {True: 2, False: 3}},
}

REFLECTION_METRIC = "shoppilot_reflection_total"
UNAVAILABLE_METRIC = "shoppilot_tool_unavailable_total"
# 反思重试要落在干净窗口里：第一枪失败到重试之间隔着一次模型重规划（dev 档 1–3s），
# 所以 watcher 的轮询间隔取 50ms、总预算 30s——窗口立不起来的唯一现实原因是计数读不到。
WINDOW_POLL_SECONDS = 0.05
WINDOW_BUDGET_SECONDS = 30.0
# 诱导没能立起来的两种形态：三态随之记未观测（诱导是环境前置，不是被测行为）。
UNINDUCED = ("window-missed", "fault-set-failed")


def load_cases(path=CASES):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def validate_cases(cases):
    """结构 + 分区形状 + 措辞唯一，全部 0 token、无网关可跑。"""
    problems = []
    seen_ids, seen_queries = set(), {}
    for index, case in enumerate(cases, start=1):
        cid = case.get("id") or f"<第 {index} 行无 id>"
        if cid in seen_ids:
            problems.append(f"{cid}：id 重复")
        seen_ids.add(cid)
        if case.get("kind") != KIND:
            problems.append(f"{cid}：kind 必须是 {KIND}（{case.get('kind')!r}）")
        zone = case.get("zone")
        if zone not in ZONES:
            problems.append(f"{cid}：zone 未知或缺失（{zone!r}，可选 {'/'.join(ZONES)}）")
            continue
        query = case.get("query") or ""
        if not query:
            problems.append(f"{cid}：缺 query")
        elif query in seen_queries:
            problems.append(f"{cid}：措辞与 {seen_queries[query]} 逐字相同"
                            "——动作类查询不进缓存，同措辞重跑会全额计费并把成本读数压低")
        else:
            seen_queries[query] = cid
        expect = case.get("expect") or {}
        if expect.get("state") not in STATES:
            problems.append(f"{cid}：每条用例都要声明 expect.state ∈ {'/'.join(STATES)}"
                            f"（三态是这一列的核心判据；{zone} 区应为 none）")
        if zone in ("plan", "rounds") and expect.get("state") != "none":
            problems.append(f"{cid}：{zone} 区不注入故障，三态必须是 none（{expect.get('state')!r}）")
        if zone == "plan" and expect.get("steps") not in (2, 4):
            problems.append(f"{cid}：plan 区必须声明 expect.steps ∈ {{2, 4}}（{expect.get('steps')!r}）")
        if zone == "reflection":
            if expect.get("state") == "triggered" and not expect.get("attempt"):
                problems.append(f"{cid}：触发档必须声明 expect.attempt（反思重试是同一工具的第几次）")
            if expect.get("state") == "exhausted" and expect.get("exit") != "TOOL_UNAVAILABLE":
                problems.append(f"{cid}：用尽档的出口必须是 TOOL_UNAVAILABLE（138 的『原语义收口』）")
        if zone == "rounds":
            if expect.get("exit") != "TOOL_ROUNDS_EXHAUSTED":
                problems.append(f"{cid}：超限区必须声明 expect.exit=TOOL_ROUNDS_EXHAUSTED")
            if "writePending" not in expect:
                problems.append(f"{cid}：超限区必须声明 expect.writePending（读超限与写挂起超限的路径不同）")
        fault = case.get("fault")
        if fault and fault.get("mode") not in ("persistent", "window"):
            problems.append(f"{cid}：fault.mode 未知（{fault.get('mode')!r}）")

    # 分区形状：15 / 10 / 5，内部配比逐项对（票 140）
    if len(cases) != PARTITION["total"]:
        problems.append(f"集子规模 {len(cases)} ≠ {PARTITION['total']}")
    for zone, shape in PARTITION.items():
        if not isinstance(shape, dict):
            continue
        of_zone = [c for c in cases if c.get("zone") == zone]
        if len(of_zone) != shape["total"]:
            problems.append(f"{zone} 分区 {len(of_zone)} 条 ≠ {shape['total']}")
        if zone == "plan":
            for steps, want in shape["steps"].items():
                got = sum(1 for c in of_zone if (c.get("expect") or {}).get("steps") == steps)
                if got != want:
                    problems.append(f"plan 分区 {steps} 步 {got} 条 ≠ {want}")
        if zone == "reflection":
            for state, want in shape["states"].items():
                got = sum(1 for c in of_zone if (c.get("expect") or {}).get("state") == state)
                if got != want:
                    problems.append(f"reflection 分区 {state} {got} 条 ≠ {want}")
        if zone == "rounds":
            for pending, want in shape["writePending"].items():
                got = sum(1 for c in of_zone if (c.get("expect") or {}).get("writePending") is pending)
                if got != want:
                    problems.append(f"rounds 分区 writePending={pending} {got} 条 ≠ {want}")
    return problems


def _details(result):
    return [str(s.get("detail") or "") for s in (result.get("trace") or [])]


def _plan_steps(result):
    plan = result.get("plan")
    return [s for s in plan if isinstance(s, dict)] if isinstance(plan, list) else None


def _reflection_marks(steps, details):
    """反思的两处机器痕迹：`plan[]` 的 `reflection=true` 步，与 trace 的小写头行 `reflection=<tool>`。

    失败的那一枪不进 `plan[]`，所以两处都要读：反思后又失败（次数用尽）时 plan 是空的，
    只有 trace 与 counter 记得住这次反思发生过。
    """
    plan_marks = [s for s in (steps or []) if s.get("reflection") is True]
    trace_marks = [d for d in details if d.startswith("reflection=")]
    return plan_marks, trace_marks


def _observed_state(steps, details, counters):
    """三态判定（票 139）：触发 / 不触发 / 次数用尽；判不出来就是 None = 未观测。"""
    plan_marks, trace_marks = _reflection_marks(steps, details)
    triggered_delta = counters.get("triggered")
    exhausted_delta = counters.get("exhausted")
    known_counters = not (triggered_delta is None and exhausted_delta is None)
    if (exhausted_delta or 0) >= 1:
        return "exhausted"
    if (triggered_delta or 0) >= 1 or plan_marks or trace_marks:
        return "triggered"
    if not known_counters:
        # counter 读不到、痕迹也没有：可能是"真的没触发"，也可能是"用尽但没留痕"——不许猜。
        return None
    return "none" if steps is not None else None


def score_case(case, result, counters=None):
    """纯函数判分：夹具可直接喂合成 result/counters 驱动它。

    返回里 True/False 是判定，None 是"这条链路上观测不到"（不出现在分子也不出现在分母）。
    """
    counters = counters or {}
    zone = case.get("zone")
    if zone not in ZONES:
        raise ValueError("未知 zone: " + str(zone))
    expect = case.get("expect") or {}
    checks = {}
    steps = _plan_steps(result)
    details = _details(result)
    rounds = len(executed_tools(details))
    fallback = str(result.get("fallbackReason") or "")
    plan_marks, trace_marks = _reflection_marks(steps, details)
    state = _observed_state(steps, details, counters)

    checks["state"] = state
    checks["plan_steps"] = len(steps) if steps is not None else None
    checks["tool_rounds"] = rounds

    if "state" in expect:
        checks["state_ok"] = None if state is None else (state == expect["state"])
    if "steps" in expect:
        checks["steps_ok"] = None if steps is None else (len(steps) == expect["steps"])
    if "stepsAtMost" in expect:
        checks["steps_ok"] = None if steps is None else (1 <= len(steps) <= expect["stepsAtMost"])
    if "reflection" in expect:
        triggered = bool(plan_marks) or bool(trace_marks) or (counters.get("triggered") or 0) >= 1
        checks["reflection_ok"] = triggered == bool(expect["reflection"])
    if "attempt" in expect:
        # attempt 是"同一工具的第几次出现"（1-based）；反思重试 = 第 2 次。
        checks["attempt_ok"] = None if not plan_marks else all(
            mark.get("attempt") == expect["attempt"] for mark in plan_marks)
    if "gate" in expect:
        checks["gate_ok"] = None if steps is None else any(
            str(step.get("status")) == expect["gate"] for step in steps)
    if "exit" in expect:
        checks["exit_ok"] = fallback == (expect["exit"] or "")
    if expect.get("ticket"):
        checks["ticket_ok"] = bool(result.get("ticketId"))
    if "writePending" in expect:
        pending = any(d.startswith("tool-rounds-exhausted write-pending=") for d in details)
        checks["writePending_ok"] = pending == bool(expect["writePending"])
    if "rounds" in expect:
        checks["rounds_ok"] = rounds == expect["rounds"]
    return checks


def summarize(rows):
    """聚合：**单独一列、不给总分**。三态分布与未观测条数一起报（未观测不是 False，也不静默计入）。"""
    states = [row.get("state") for row in rows]
    judged = [value for row in rows for key, value in row.items() if key.endswith("_ok")]
    return {
        "cases": len(rows),
        "state_triggered": sum(1 for s in states if s == "triggered"),
        "state_none": sum(1 for s in states if s == "none"),
        "state_exhausted": sum(1 for s in states if s == "exhausted"),
        "state_unobserved": sum(1 for s in states if s is None),
        "judged": len(judged),
        "failed": sum(1 for value in judged if value is False),
        "unobserved": sum(1 for value in judged if value is None),
    }


def _fx(name, case, result, expected, counters=None):
    return {"name": name, "case": case, "result": result, "expected": expected, "counters": counters or {},
            "raises": expected is None}


# 夹具：喂合成 case/result/counters 驱动 score_case，不发 HTTP、不读用例文件、0 token 可复跑。
# expected 里的 None 是承重的——判不出来的三态必须老实返回 None，写成 True/False 等于把它
# 静默计入分子或分母，那正是 ticket 34 警告过的假绿。
FIXTURES = [
    _fx("4 轮链：四枪读完、无反思、无降级 → 全绿",
        {"zone": "plan", "expect": {"state": "none", "steps": 4, "reflection": False, "exit": None}},
        {"plan": [{"tool": "queryOrderDetail", "status": "OK", "attempt": 1, "reflection": False},
                  {"tool": "queryLogistics", "status": "OK", "attempt": 1, "reflection": False},
                  {"tool": "queryOrderDetail", "status": "OK", "attempt": 1, "reflection": False},
                  {"tool": "queryLogistics", "status": "OK", "attempt": 1, "reflection": False}],
         "fallbackReason": None},
        {"state": "none", "state_ok": True, "steps_ok": True, "reflection_ok": True, "exit_ok": True},
        {"triggered": 0, "exhausted": 0}),
    _fx("反证：只打了 3 枪就收尾 → 步数判负（4 轮链不是四枪就是没办到）",
        {"zone": "plan", "expect": {"steps": 4, "reflection": False, "exit": None}},
        {"plan": [{"tool": "queryOrderDetail", "status": "OK", "attempt": 1, "reflection": False},
                  {"tool": "queryOrderDetail", "status": "OK", "attempt": 1, "reflection": False},
                  {"tool": "queryLogistics", "status": "OK", "attempt": 1, "reflection": False}],
         "fallbackReason": None},
        {"steps_ok": False},
        {"triggered": 0, "exhausted": 0}),
    _fx("反证：声称无出口而实际落了降级 → 出口判负",
        {"zone": "plan", "expect": {"steps": 2, "reflection": False, "exit": None}},
        {"plan": [{"tool": "queryOrderDetail", "status": "OK", "attempt": 1, "reflection": False}],
         "fallbackReason": "TOOL_UNAVAILABLE", "ticketId": "T-1"},
        {"exit_ok": False},
        {"triggered": 0, "exhausted": 0}),
    _fx("反思触发：第二枪带 reflection=true 且 attempt=2（失败的那一枪不进 plan）",
        {"zone": "reflection", "expect": {"state": "triggered", "attempt": 2, "stepsAtMost": 4, "exit": None}},
        {"plan": [{"tool": "queryOrderDetail", "status": "OK", "attempt": 2, "reflection": True}],
         "fallbackReason": None,
         "trace": [{"detail": "QUERY_ORDER_DETAIL=UNAVAILABLE modelArgs={}"},
                   {"detail": "reflection=queryOrderDetail attempt=2"},
                   {"detail": "QUERY_ORDER_DETAIL=OK modelArgs={}"}]},
        {"state_ok": True, "steps_ok": True, "attempt_ok": True, "exit_ok": True, "tool_rounds": 2},
        {"triggered": 1, "exhausted": 0}),
    _fx("反证：声称触发而 plan 无反思标记、counter 无增量 → 三态判负",
        {"zone": "reflection", "expect": {"state": "triggered"}},
        {"plan": [{"tool": "queryOrderDetail", "status": "OK", "attempt": 1, "reflection": False}],
         "fallbackReason": None, "trace": []},
        {"state_ok": False},
        {"triggered": 0, "exhausted": 0}),
    _fx("次数用尽：counter exhausted +1、出口 TOOL_UNAVAILABLE、落了工单、plan 为空（两枪都失败）",
        {"zone": "reflection", "expect": {"state": "exhausted", "steps": 0, "exit": "TOOL_UNAVAILABLE",
                                          "ticket": True}},
        {"plan": [], "fallbackReason": "TOOL_UNAVAILABLE", "ticketId": "T-9",
         "trace": [{"detail": "QUERY_ORDER_DETAIL=UNAVAILABLE modelArgs={}"},
                   {"detail": "reflection=queryOrderDetail attempt=2"},
                   {"detail": "QUERY_ORDER_DETAIL=UNAVAILABLE modelArgs={}"}]},
        {"state_ok": True, "steps_ok": True, "exit_ok": True, "ticket_ok": True},
        {"triggered": 1, "exhausted": 1}),
    _fx("反证：判成用尽却没落工单 → 工单判负（138 的『原语义收口』落的是工单不是旁路）",
        {"zone": "reflection", "expect": {"state": "exhausted", "steps": 0, "exit": "TOOL_UNAVAILABLE",
                                          "ticket": True}},
        {"plan": [], "fallbackReason": "TOOL_UNAVAILABLE", "ticketId": None, "trace": []},
        {"ticket_ok": False},
        {"triggered": 1, "exhausted": 1}),
    _fx("闸·归属拒绝：NOT_FOUND 那一枪进 plan[]、无反思、无降级（①闸走原出口）",
        {"zone": "reflection", "expect": {"state": "none", "steps": 1, "gate": "NOT_FOUND", "exit": None}},
        {"plan": [{"tool": "queryOrderDetail", "status": "NOT_FOUND", "attempt": 1, "reflection": False}],
         "fallbackReason": None,
         "trace": [{"detail": "QUERY_ORDER_DETAIL=NOT_FOUND modelArgs={}"},
                   {"detail": "plan-aborted status=NOT_FOUND"}]},
        {"state_ok": True, "steps_ok": True, "gate_ok": True, "exit_ok": True},
        {"triggered": 0, "exhausted": 0}),
    _fx("反证：闸种不许混（期望 NOT_FOUND 实为 STATE_NOT_ALLOWED）→ 判负",
        {"zone": "reflection", "expect": {"state": "none", "steps": 1, "gate": "NOT_FOUND"}},
        {"plan": [{"tool": "queryLogistics", "status": "STATE_NOT_ALLOWED", "attempt": 1, "reflection": False}],
         "fallbackReason": None, "trace": [{"detail": "QUERY_LOGISTICS=STATE_NOT_ALLOWED modelArgs={}"}]},
        {"gate_ok": False},
        {"triggered": 0, "exhausted": 0}),
    _fx("反证：counter 读不到且无反思痕迹 → 三态记未观测（不是 False）",
        {"zone": "reflection", "expect": {"state": "exhausted", "steps": 0, "exit": "TOOL_UNAVAILABLE"}},
        {"plan": [], "fallbackReason": "TOOL_UNAVAILABLE", "ticketId": "T-9", "trace": []},
        {"state": None, "state_ok": None},
        {}),
    _fx("读超限：plan 为空（fallback 不带走 plan）而 trace 记着四枪 → 用枪数判形态",
        {"zone": "rounds", "expect": {"state": "none", "exit": "TOOL_ROUNDS_EXHAUSTED", "ticket": True,
                                      "rounds": 4, "writePending": False, "reflection": False}},
        {"plan": [], "fallbackReason": "TOOL_ROUNDS_EXHAUSTED", "ticketId": "T-7",
         "trace": [{"detail": "QUERY_ORDER_DETAIL=OK modelArgs={}"},
                   {"detail": "QUERY_ORDER_DETAIL=OK modelArgs={}"},
                   {"detail": "QUERY_LOGISTICS=OK modelArgs={}"},
                   {"detail": "QUERY_ORDER_DETAIL=OK modelArgs={}"},
                   {"detail": "budget-check"}]},
        {"state_ok": True, "exit_ok": True, "ticket_ok": True, "rounds_ok": True, "writePending_ok": True,
         "reflection_ok": True, "tool_rounds": 4},
        {"triggered": 0, "exhausted": 0}),
    _fx("反证：只打了 3 枪就落超限 → 轮数判负",
        {"zone": "rounds", "expect": {"exit": "TOOL_ROUNDS_EXHAUSTED", "rounds": 4, "writePending": False}},
        {"plan": [], "fallbackReason": "TOOL_ROUNDS_EXHAUSTED", "ticketId": "T-7",
         "trace": [{"detail": "QUERY_ORDER_DETAIL=OK modelArgs={}"},
                   {"detail": "QUERY_ORDER_DETAIL=OK modelArgs={}"},
                   {"detail": "QUERY_LOGISTICS=OK modelArgs={}"}]},
        {"rounds_ok": False},
        {"triggered": 0, "exhausted": 0}),
    _fx("写挂起超限：出口与读超限相同、路径不同（trace 的 write-pending 行）",
        {"zone": "rounds", "expect": {"exit": "TOOL_ROUNDS_EXHAUSTED", "rounds": 4, "writePending": True}},
        {"plan": [], "fallbackReason": "TOOL_ROUNDS_EXHAUSTED", "ticketId": "T-8",
         "trace": [{"detail": "QUERY_ORDER_DETAIL=OK modelArgs={}"},
                   {"detail": "QUERY_LOGISTICS=OK modelArgs={}"},
                   {"detail": "QUERY_ORDER_DETAIL=OK modelArgs={}"},
                   {"detail": "QUERY_ORDER_DETAIL=OK modelArgs={}"},
                   {"detail": "tool-rounds-exhausted write-pending=modifyDeliveryAddress"}]},
        {"exit_ok": True, "rounds_ok": True, "writePending_ok": True},
        {"triggered": 0, "exhausted": 0}),
    _fx("反证：期望写挂起而走的是预算检查那条路 → 判负（两条出口的路径不同）",
        {"zone": "rounds", "expect": {"exit": "TOOL_ROUNDS_EXHAUSTED", "rounds": 4, "writePending": True}},
        {"plan": [], "fallbackReason": "TOOL_ROUNDS_EXHAUSTED", "ticketId": "T-8",
         "trace": [{"detail": "QUERY_ORDER_DETAIL=OK modelArgs={}"},
                   {"detail": "QUERY_ORDER_DETAIL=OK modelArgs={}"},
                   {"detail": "QUERY_ORDER_DETAIL=OK modelArgs={}"},
                   {"detail": "QUERY_ORDER_DETAIL=OK modelArgs={}"},
                   {"detail": "budget-check"}]},
        {"writePending_ok": False},
        {"triggered": 0, "exhausted": 0}),
    _fx("反证：未知 zone 必须抛错，不许静默判过",
        {"zone": "plugin", "expect": {}}, {"plan": []}, None),
]


def selfcheck():
    """跑夹具 + 校验用例文件，返回 (失败行, 夹具条数)。CI 那一步与命令行共用同一份。"""
    failures = []
    for fixture in FIXTURES:
        name = fixture["name"]
        if fixture["raises"]:
            try:
                score_case(fixture["case"], fixture["result"], fixture["counters"])
            except Exception:
                continue
            failures.append(f"{name}：期望抛错，实际正常返回")
            continue
        try:
            checks = score_case(fixture["case"], fixture["result"], fixture["counters"])
        except Exception as exc:  # noqa: BLE001
            failures.append(f"{name}：判分器抛错 {exc!r}")
            continue
        for key, want in fixture["expected"].items():
            got = checks.get(key, "<缺失>")
            if got != want:
                failures.append(f"{name}：{key} 期望 {want!r}，实为 {got!r}")
    failures.extend("用例文件 " + problem for problem in validate_cases(load_cases()))
    return failures, len(FIXTURES)


def endpoint_for(case):
    channel = case.get("channel") or "web"
    if channel == "web":
        return "/api/v1/support/chat"
    return "/api/v1/support/webhook/" + channel


def _metric(runner, base, name, tag=None):
    """读一个 counter 的当前值；读不到返回 None（三态随之记未观测，不许当 0）。"""
    url = f"{base}/actuator/metrics/{name}" + (f"?tag={tag}" if tag else "")
    try:
        body = runner.http_json(url)
    except Exception:  # noqa: BLE001  观测不到不是失败——它就是"未观测"这件事本身
        return None
    return next((float(m.get("value") or 0) for m in (body.get("measurements") or [])
                 if m.get("statistic") == "COUNT"), None)


def _counters(runner, base):
    return {"triggered": _metric(runner, base, REFLECTION_METRIC, "outcome:triggered"),
            "exhausted": _metric(runner, base, REFLECTION_METRIC, "outcome:exhausted")}


def _delta(before, after):
    if before is None or after is None:
        return None
    return int(after - before)


def _ops_headers(args):
    """运维面的两个头缺一不可：先过 `AuthFilter` 的 bearer，再过运维凭证那一条守卫。

    内置假 runner 不跑真链路，所以这个 401 只有活体首跑才暴露（票 149 首跑实测：
    `/api/v1/support/ops/circuit` 只带 `X-Ops-Token` → `401 missing bearer token`）。
    """
    return {"X-Ops-Token": args.ops_token,
            "Authorization": "Bearer " + getattr(args, "bearer_token", "")}


def _set_fault(runner, args, base, fail_rate):
    """挂/清故障面。**绝不抛异常**：一次运维调用失败把整批掀翻，代价是一整轮预算；
    没挂上/没清干净由调用方记 `induction` 并打警告，读的人看得见（这比静默更值钱）。
    """
    try:
        # `/ops/fault` 是 PUT（GET 读、PUT 写，`OpsController`）：POST 会吃 405 而假 runner 看不出来。
        runner.http_json(f"{base}/api/v1/support/ops/fault", {"failRate": fail_rate},
                         _ops_headers(args), method="PUT")
        return True
    except Exception:  # noqa: BLE001
        return False


class _FaultWindow:
    """只烧第一枪：watcher 盯着 `shoppilot_tool_unavailable_total`，一抬头立刻把故障清零。

    没有它就没法观测"读工具③失败 → 换路成功"（biz-mock 的故障面是全局 delayMs/failRate，
    没有按工具或一次性的开关；挂着不放走的是"次数用尽"那一档）。
    """

    def __init__(self, runner, args, base):
        self.runner, self.args, self.base = runner, args, base
        self.baseline = None
        self.armed = False
        self.opened = False
        self._stop = threading.Event()

    def __enter__(self):
        self.baseline = _metric(self.runner, self.base, UNAVAILABLE_METRIC)
        if self.baseline is None:
            # 观测不到枪声就没法"只烧一枪"：宁可不注入，也别把一整例烧成用尽档。
            return self
        self.armed = _set_fault(self.runner, self.args, self.base, 1.0)
        if not self.armed:
            return self
        threading.Thread(target=self._watch, daemon=True).start()
        return self

    def _watch(self):
        deadline = time.time() + WINDOW_BUDGET_SECONDS
        while not self._stop.is_set() and time.time() < deadline:
            now = _metric(self.runner, self.base, UNAVAILABLE_METRIC)
            if now is not None and now > self.baseline:
                self.opened = True
                _set_fault(self.runner, self.args, self.base, 0.0)
                return
            time.sleep(WINDOW_POLL_SECONDS)

    def __exit__(self, *_):
        # 收尾前再看一眼：第一枪可能正好打在响应的尾巴上，watcher 还没轮到下一次轮询
        # （实测：请求 0.3s、轮询 50ms 时能撞上）。漏判会把"窗口其实立起来了"记成没立起来。
        if self.armed:
            now = _metric(self.runner, self.base, UNAVAILABLE_METRIC)
            if now is not None and self.baseline is not None and now > self.baseline:
                self.opened = True
        self._stop.set()
        if not _set_fault(self.runner, self.args, self.base, 0.0):
            print("  WARN  故障面没清干净（ops/fault 调用失败）——下一例可能被污染，读数请照登")
        return False


def run(parser_args, runner) -> int:
    """活体跑批。`runner` 是 run_tool_eval 的共享 HTTP 助手，惰性注入避免循环导入。"""
    cases = load_cases()
    problems = validate_cases(cases)
    if problems:
        for problem in problems:
            print("AUTONOMY CHECK FAILED  " + problem)
        return 2
    token = runner.mock_token(parser_args.base, "T001", "C001")
    headers = {"Authorization": "Bearer " + token}
    # ops 面的两个头（见 _ops_headers）：活体首跑实测只带 X-Ops-Token 会被 AuthFilter 401。
    parser_args.bearer_token = token
    circuit = runner.http_json(f"{parser_args.base}/api/v1/support/ops/circuit",
                               headers=_ops_headers(parser_args))
    print(f"模式={circuit.get('llmMode')} 模型={circuit.get('llmModel')} 样本={len(cases)} 节流={PACE_SECONDS}s")
    if not _set_fault(runner, parser_args, parser_args.base, 0.0):   # 起跑前先确认故障面是干净的
        print("  WARN  起跑前清故障面失败：读数可能被上一轮的残留故障污染，请照登")
    # 写类用例会改演示固定单（90001 的退款受理、90004 的改址）：跑前复位，否则第二遍是在测状态机
    # 而不是测模型（与 gold 路径同一条纪律）。这一条也是"复跑必须能复现"的前提（票 149 要跑两遍）。
    try:
        runner.http_json(f"{parser_args.base}/api/v1/support/ops/demo/reset", {},
                         _ops_headers(parser_args))
        print("已复位演示固定单（90001-90004、91001-91002）")
    except Exception as reset_failed:  # noqa: BLE001
        print(f"WARN  演示固定单复位失败，写类用例可能受状态污染：{reset_failed}")

    rows = []
    for index, case in enumerate(cases):
        if index:
            time.sleep(PACE_SECONDS)
            # 票 149 的口径：**每条前复位**演示数据——写类用例会改演示固定单（90001 的退款受理、
            # 90004 的改址），上一例留下的状态不该成为下一例的输入。复位是 ops 调用、不打模型、
            # 不进成本列（实测 0.03s）；失败只警告不掀翻整批（一次烧掉的是整轮预算）。
            try:
                runner.http_json(f"{parser_args.base}/api/v1/support/ops/demo/reset", {},
                                 _ops_headers(parser_args))
            except Exception as reset_each:  # noqa: BLE001
                print(f"  WARN  {case['id']} 前置复位失败，该例可能受状态污染：{reset_each}")
        fault = case.get("fault") or {}
        before_counters = _counters(runner, parser_args.base)
        before_tokens = _used_tokens(runner, parser_args, circuit)
        induction = "none"
        window, result, error, latency_ms = None, {}, None, 0
        try:
            if fault.get("mode") == "persistent":
                arming = _set_fault(runner, parser_args, parser_args.base, float(fault.get("failRate", 1.0)))
                induction = "persistent" if arming else "fault-set-failed"
            elif fault.get("mode") == "window":
                window = _FaultWindow(runner, parser_args, parser_args.base)
                window.__enter__()
                if not window.armed:
                    induction = "fault-set-failed"
            started = time.perf_counter()
            result = runner.http_json(parser_args.base + endpoint_for(case), {"query": case["query"]},
                                      dict(headers, **{"X-Conversation-Id": "eval-autonomy-" + case["id"]}))
            latency_ms = int((time.perf_counter() - started) * 1000)
        except Exception as failure:  # 单条失败不拖垮整批：记错误并继续
            error = str(failure)[:160]
        finally:
            # 故障面必须清零：挂着不放会把"下一例"烧成用尽档（窗口没立起来时也要清）。
            if window is not None:
                window.__exit__()
                if induction != "fault-set-failed":
                    induction = "window" if window.opened else "window-missed"
            elif fault.get("mode") == "persistent":
                if not _set_fault(runner, parser_args, parser_args.base, 0.0):
                    print("  WARN  故障面没清干净（ops/fault 调用失败）——下一例可能被污染，读数请照登")
        if error is not None:
            rows.append({"id": case["id"], "zone": case.get("zone"), "error": error, "induction": induction,
                         "state": None, "judged": 0, "failed": 0, "unobserved": 0})
            print(f"  {case['id']} zone={case.get('zone')} ERROR {error}")
            continue
        after_counters = _counters(runner, parser_args.base)
        counters = {"triggered": _delta(before_counters["triggered"], after_counters["triggered"]),
                    "exhausted": _delta(before_counters["exhausted"], after_counters["exhausted"])}
        if induction in UNINDUCED:
            # 诱导没立起来（窗口没抬过头 / ops 调用失败）→ 三态记未观测：**不是 PASS 也不是 FAIL**。
            counters = {"triggered": None, "exhausted": None}
        checks = score_case(case, result, counters)
        judged = [value for key, value in checks.items() if key.endswith("_ok")]
        rows.append({
            "id": case["id"], "zone": case.get("zone"), "induction": induction,
            "billed_tokens": _used_tokens(runner, parser_args, circuit) - before_tokens,
            "latency_ms": latency_ms, "answer_excerpt": str(result.get("answer") or "")[:120],
            **checks, "judged": len(judged),
            "failed": sum(1 for value in judged if value is False),
            "unobserved": sum(1 for value in judged if value is None),
        })
        print(f"  {case['id']} zone={case['zone']} state={checks.get('state')} "
              f"induction={induction} plan_steps={checks.get('plan_steps')} "
              f"rounds={checks.get('tool_rounds')} failed={rows[-1]['failed']}")

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    detail_path = RESULTS / f"tool-eval-{stamp}-dev-autonomy.csv"
    RESULTS.mkdir(parents=True, exist_ok=True)
    fieldnames = sorted({key for row in rows for key in row})
    with detail_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    summary = summarize(rows)
    for row in rows:
        if row.get("failed"):
            print(f"  FAIL  {row['id']}（{row['failed']} 条判定红）")
    print(f"自主性（part10）：{summary['cases']} 条 —— 触发 {summary['state_triggered']} / "
          f"不触发 {summary['state_none']} / 用尽 {summary['state_exhausted']} / "
          f"未观测 {summary['state_unobserved']}；判定 {summary['judged']} 条，"
          f"红 {summary['failed']} 条，未观测 {summary['unobserved']} 条")
    print("说明：这是**独立一列**，不给总分、不并入四列（意图/工具/参数/槽位）的任何百分比；"
          "billed_tokens / latency_ms 只作取证，不参与判定。")
    # 这是一次烧真钱的跑批：末尾这行只是显示用，绝不能因为路径不在仓内就抛异常把报告吞掉。
    shown = detail_path.relative_to(REPO) if detail_path.is_relative_to(REPO) else detail_path
    print(f"明细 {shown}")
    print(f"AUTONOMY DONE cases={summary['cases']} state_triggered={summary['state_triggered']} "
          f"state_none={summary['state_none']} state_exhausted={summary['state_exhausted']} "
          f"state_unobserved={summary['state_unobserved']} failed={summary['failed']} "
          f"unobserved_assertions={summary['unobserved']}")
    return 1 if summary["failed"] else 0


def _used_tokens(runner, args, circuit):
    """计费 token = `ops/circuit` 的当日计数（= Redis `shoppilot:llm:tokens:<day>`）。

    读不到就退回起跑时快照（增量记 0 并在报告里保持为 0，不拿响应 token 字段顶替——R12）。
    """
    try:
        live = runner.http_json(f"{args.base}/api/v1/support/ops/circuit",
                                headers=_ops_headers(args))
        return int(live.get("tokensUsedToday") or 0)
    except Exception:  # noqa: BLE001
        return int(circuit.get("tokensUsedToday") or 0)


if __name__ == "__main__":
    # 只做夹具自检 + 用例结构校验：0 token、无网关也能跑，CI 那一步按这个入口驱动它。
    # 真跑要活体网关，入口在 run_tool_eval.py --autonomy。
    import sys

    failed_rows, fixture_count = selfcheck()
    for failed in failed_rows:
        print("AUTONOMY CHECK FAILED  " + failed)
    if failed_rows:
        print(f"AUTONOMY SELFCHECK ok=0（{len(failed_rows)} 条红，共 {fixture_count} 条夹具）")
        sys.exit(1)
    print(f"AUTONOMY SELFCHECK ok={fixture_count}")
