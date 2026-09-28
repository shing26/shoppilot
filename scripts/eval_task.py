"""round22 票 65 / ADR 0050：task 级判据——「这件事最终办成了吗」。

与四列（意图 / 工具 / 参数 / 槽位）**并列**，**不给总分、不并入四列任何百分比**：
四列是单维请求质量，本判据是端到端终局。混进同一分母会让「四列全绿而任务没办成」
这个它要抓的形态重新隐身。

判据面**只读三个既有字段**（`plan` / `context.ruleIds` / `fallbackReason`，都是 round19
票 48/49 交付的），零新增后端代码、零新增探针：

    kind_task == "action"    → plan 里 target tool 的 status == OK 且无 fallbackReason
    kind_task == "policy"    → context.ruleIds 非空 且无 fallbackReason
    kind_task == "escalate"  → fallbackReason == USER_REQUESTED
    该字段在这条链路上没有（离线明细不带 plan/context）→ None = 未观测

独立成模块而不是并进 `eval_suites.py`：那份文件是 CI 门禁的断言载体，round19 登记第 4 项
**逐字要求**「新增独立评测模块（`eval_suites.py` 形态）比改现有 gold 安全」。

用法：
  python scripts/eval_task.py            # 夹具自检（0 token、无网关），CI 那一步用它
  python scripts/run_tool_eval.py --task # 活体跑批（复用 run_tool_eval 的 HTTP 助手）
"""

import csv
import datetime as dt
import json
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RESULTS = REPO / "eval" / "results"
CASES = REPO / "eval" / "cases-part8-task.jsonl"

KIND_TASKS = ("action", "policy", "escalate")


def load_cases(path=CASES):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def validate_cases(cases):
    """夹具的结构校验：0 token、无网关。判据要读的字段必须真的在用例里被声明。"""
    problems = []
    seen = set()
    for index, case in enumerate(cases, start=1):
        cid = case.get("id") or f"<第 {index} 行无 id>"
        if cid in seen:
            problems.append(f"{cid}：id 重复")
        seen.add(cid)
        kind_task = case.get("kind_task")
        if kind_task not in KIND_TASKS:
            problems.append(f"{cid}：kind_task 未知或缺失（{kind_task!r}）")
            continue
        if not case.get("query"):
            problems.append(f"{cid}：缺 query")
        expect = case.get("expect") or {}
        # action 必须声明要办成的那一件事是哪个工具；policy/escalate 不需要 target
        if kind_task == "action" and not expect.get("targetTool"):
            problems.append(f"{cid}：kind_task=action 必须给 expect.targetTool")
    return problems


def _observed(result, key):
    """该字段在result里有没有出现。离线明细不带 plan/context 时，这条判据就是未观测，不是失败。"""
    return key in result


def _no_fallback(result):
    return result.get("fallbackReason") in (None, "")


def score_case(case, result):
    """纯函数判分：夹具可直接喂合成 result 驱动它。

    返回 {'task_done': True|False|None}。None 是「这条链路上观测不到」，既不进分子也不进分母，
    与 gold 那条「未观测断言」列同一条纪律。
    """
    kind_task = case.get("kind_task")
    expect = case.get("expect") or {}
    checks = {}

    if kind_task == "action":
        if not _observed(result, "plan"):
            checks["task_done"] = None
            return checks
        target = expect.get("targetTool")
        hit = next((step for step in (result.get("plan") or []) if step.get("tool") == target), None)
        # 办成 = 那一步真的执行过且成功，且这一轮没有落降级
        checks["task_done"] = bool(hit) and str(hit.get("status")) == "OK" and _no_fallback(result)
        return checks

    if kind_task == "policy":
        if not _observed(result, "context"):
            checks["task_done"] = None
            return checks
        rule_ids = (result.get("context") or {}).get("ruleIds")
        # 政策任务办成 = 答在条款上（引用了规则块）且没有落降级
        checks["task_done"] = bool(rule_ids) and _no_fallback(result)
        return checks

    if kind_task == "escalate":
        if not _observed(result, "fallbackReason"):
            checks["task_done"] = None
            return checks
        checks["task_done"] = result.get("fallbackReason") == "USER_REQUESTED"
        return checks

    raise ValueError("未知 kind_task: " + str(kind_task))


def summarize(rows):
    """聚合：单独一列，**不给总分**。未观测的条数与比率一起报（不是 False，也不静默计入）。"""
    total = len(rows)
    done = sum(1 for row in rows if row.get("task_done") is True)
    not_done = sum(1 for row in rows if row.get("task_done") is False)
    unverifiable = sum(1 for row in rows if row.get("task_done") is None)
    return {
        "cases": total,
        "task_done": done,
        "task_not_done": not_done,
        "observed": done + not_done,
        "unverifiable": unverifiable,
        "unverifiable_ratio": round(unverifiable / total, 4) if total else 0.0,
    }


def _fx(name, case, result, expected, raises=False):
    return {"name": name, "case": case, "result": result, "expected": expected, "raises": raises}


# 夹具：喂合成 case/result 驱动 score_case，不发 HTTP、不读用例文件，0 token 可复跑。
# 与 eval_suites 同一条纪律：expected 里的 None 是承重的——未观测必须老实返回 None，
# 写成 True/False 等于把它静默计入分母，那正是 ticket 34 警告过的假绿。
FIXTURES = [
    _fx("action：目标工具真跑过且成功 → 办成",
        {"kind_task": "action", "expect": {"targetTool": "queryOrderDetail"}},
        {"plan": [{"tool": "queryOrderDetail", "status": "OK"}], "fallbackReason": None},
        {"task_done": True}),
    _fx("action：目标工具跑了但业务拒绝（NOT_FOUND）→ 没办成",
        {"kind_task": "action", "expect": {"targetTool": "queryLogistics"}},
        {"plan": [{"tool": "queryLogistics", "status": "NOT_FOUND"}], "fallbackReason": None},
        {"task_done": False}),
    # 反例 A：四列会全绿（工具选对、参数也填了）而任务没办成——非归属者查别店的单
    _fx("反例 A：四列绿、任务红（target 工具选对但业务返回 NOT_FOUND）",
        {"kind_task": "action", "expect": {"targetTool": "queryLogistics"}},
        {"plan": [{"tool": "queryLogistics", "status": "NOT_FOUND", "arguments": {"orderNo": "90001"}}],
         "answer": "未在本店找到该订单。", "fallbackReason": None},
        {"task_done": False}),
    # 反例 B：工具列按旧口径会红（gold 只认 queryLogistics）而任务其实办成了
    _fx("反例 B：工具列红、任务绿（目标工具是 queryOrderDetail 且成功）",
        {"kind_task": "action", "expect": {"targetTool": "queryOrderDetail"}},
        {"plan": [{"tool": "queryOrderDetail", "status": "OK", "arguments": {"orderNo": "90001"}}],
         "fallbackReason": None},
        {"task_done": True}),
    _fx("action：目标工具没被调用 → 没办成（plan 是空数组，属已观测）",
        {"kind_task": "action", "expect": {"targetTool": "applyRefund"}},
        {"plan": [], "fallbackReason": None},
        {"task_done": False}),
    _fx("action：落降级即没办成，哪怕工具成功过",
        {"kind_task": "action", "expect": {"targetTool": "queryOrderDetail"}},
        {"plan": [{"tool": "queryOrderDetail", "status": "OK"}], "fallbackReason": "TOOL_UNAVAILABLE"},
        {"task_done": False}),
    _fx("action：离线明细不带 plan → 必须记未观测（不是 False）",
        {"kind_task": "action", "expect": {"targetTool": "queryOrderDetail"}},
        {"answer": "订单已发货。"},
        {"task_done": None}),
    _fx("policy：引用了规则块且无降级 → 办成",
        {"kind_task": "policy", "expect": {}},
        {"answer": "自签收次日起算。", "context": {"ruleIds": ["return-01-7day-basic"]}, "fallbackReason": None},
        {"task_done": True}),
    _fx("policy：零召回（ruleIds 为空）→ 没办成",
        {"kind_task": "policy", "expect": {}},
        {"answer": "本轮未检索到相关条款。", "context": {"ruleIds": []}, "fallbackReason": None},
        {"task_done": False}),
    _fx("policy：离线明细不带 context → 必须记未观测",
        {"kind_task": "policy", "expect": {}},
        {"answer": "自签收次日起算。"},
        {"task_done": None}),
    _fx("escalate：显式转人工落 USER_REQUESTED → 办成",
        {"kind_task": "escalate", "expect": {}},
        {"answer": "已为您转人工。（工单号 T-1）", "fallbackReason": "USER_REQUESTED"},
        {"task_done": True}),
    _fx("escalate：被情绪门抢走（EMOTION_ESCALATION）→ 没办成（用户要的是转人工这条路）",
        {"kind_task": "escalate", "expect": {}},
        {"answer": "已为您转人工。", "fallbackReason": "EMOTION_ESCALATION"},
        {"task_done": False}),
    _fx("escalate：家常答完（无降级）→ 没办成",
        {"kind_task": "escalate", "expect": {}},
        {"answer": "七天无理由自签收次日起算。", "fallbackReason": None},
        {"task_done": False}),
    _fx("反证：未知 kind_task 必须抛错，不许静默判过",
        {"kind_task": "chat", "expect": {}}, {"answer": "……"},
        {}, raises=True),
]


def selfcheck():
    """跑夹具 + 校验用例文件，返回 (失败行, 夹具条数)。CI 那一步与命令行共用同一份。"""
    failures = []
    for fixture in FIXTURES:
        name = fixture["name"]
        if fixture["raises"]:
            try:
                score_case(fixture["case"], fixture["result"])
            except Exception:
                continue
            failures.append(f"{name}：期望抛错，实际正常返回")
            continue
        try:
            checks = score_case(fixture["case"], fixture["result"])
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
    return "/api/v1/support/chat"


def run(parser_args, runner) -> int:
    """活体跑批。`runner` 是 run_tool_eval 的共享 HTTP 助手，惰性注入避免循环导入。"""
    cases = load_cases()
    problems = validate_cases(cases)
    if problems:
        for problem in problems:
            print("TASK CHECK FAILED  " + problem)
        return 2
    token = runner.mock_token(parser_args.base, "T001", "C001")
    headers = {"Authorization": "Bearer " + token}

    rows = []
    for case in cases:
        try:
            result = runner.http_json(parser_args.base + endpoint_for(case), {"query": case["query"]},
                                      dict(headers, **{"X-Conversation-Id": "eval-task-" + case["id"]}))
            checks = score_case(case, result)
            checks["answer_excerpt"] = str(result.get("answer") or "")[:120]
        except Exception as failure:  # noqa: BLE001  单条失败不拖垮整批
            checks = {"task_done": None, "error": str(failure)[:120]}
        rows.append({"id": case["id"], "kind_task": case.get("kind_task"), **checks})

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    detail_path = RESULTS / f"tool-eval-{stamp}-dev-task.csv"
    fieldnames = sorted({key for row in rows for key in row})
    with detail_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    summary = summarize(rows)
    print(f"task 级判据：{summary['cases']} 条 —— task_done {summary['task_done']} / "
          f"没办成 {summary['task_not_done']} / 未观测 {summary['unverifiable']}"
          f"（比率 {summary['unverifiable_ratio']:.1%}），已观测 {summary['observed']} 条")
    print("说明：这是**独立一列**，不给总分、不并入四列（意图/工具/参数/槽位）的任何百分比。")
    print(f"明细 {detail_path.relative_to(REPO)}")
    print(f"TASK DONE cases={summary['cases']} task_done={summary['task_done']} "
          f"unverifiable={summary['unverifiable']}")
    return 0


if __name__ == "__main__":
    # 只做夹具自检 + 用例结构校验：0 token、无网关也能跑，CI 那一步按这个入口驱动它。
    # 真跑要活体网关，入口在 run_tool_eval.py --task。
    import sys

    failed_rows, fixture_count = selfcheck()
    for failed in failed_rows:
        print("TASK CHECK FAILED  " + failed)
    if failed_rows:
        print(f"TASK SELFCHECK ok=0（{len(failed_rows)} 条红，共 {fixture_count} 条夹具）")
        sys.exit(1)
    print(f"TASK SELFCHECK ok={fixture_count}")
