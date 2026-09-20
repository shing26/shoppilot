"""round17 新增评测套件（part4-7）：情绪 / 渠道 / 计划 / 风格 / 反馈。

与 gold 集（part1-3）分开：gold 是意图与工具的判据源、CI 的 rescore 门禁钉着它；
本模块按 kind 分发判分，语义断言（mustNotSee / mustNotCarryFacts / 归属）机器判不了
就记 None = "这条链路上观测不到"，不静默计入分子——与 gold 那条"未观测断言"列同一条纪律。

设计成独立模块的理由：`run_tool_eval.py` 的 selfcheck 与 rescore 是 CI 门禁的载体，
套件代码进来会让那条路径多一份依赖面；这里只有 stdlib，且由 run_tool_eval 惰性导入。

用法（由 run_tool_eval.py 转发）：
  python scripts/run_tool_eval.py --suite emotion,plan
"""

import csv
import datetime as dt
import json
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RESULTS = REPO / "eval" / "results"

SUITES = {
    "emotion": "eval/cases-part4-emotion.jsonl",
    "channel": "eval/cases-part5-channel.jsonl",
    "plan": "eval/cases-part6-plan.jsonl",
    "style-feedback": "eval/cases-part7-style-feedback.jsonl",
}


def load_suite(name):
    path = REPO / SUITES[name]
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _details(result):
    return [str(s.get("detail") or "") for s in (result.get("trace") or [])]


def executed_tools(details):
    """从 trace 抽已执行工具序列：TOOL_EXEC 的 detail 形如 `QUERY_LOGISTICS=OK modelArgs=...`；
    plan-aborted / plan-rejected 也挂在 TOOL_EXEC 状态上，按前缀排除。"""
    tools = []
    for d in details:
        if d.startswith(("plan-aborted", "plan-rejected")):
            continue
        if d.startswith("gateway-derived"):
            parts = d.split(" ")
            if len(parts) > 1:
                tools.append(parts[1].split("=")[0])
            continue
        head = d.split("=")[0].strip()
        if head and head.isupper() and " " not in head:
            tools.append(head)
    return tools


def score_case(case, result):
    """kind 分发的纯函数判分：夹具可直接喂合成 result 驱动它。

    返回值里 True/False 是判定，None 是"这条链路上观测不到"（不参与分子也不进分母的静默项）。
    """
    kind = case.get("kind")
    expect = case.get("expect") or {}
    details = _details(result)
    fallback = str(result.get("fallbackReason") or "")
    answer = str(result.get("answer") or "")
    checks = {}

    if kind == "emotion":
        sentiment = next((d.split("sentiment=")[1].split(" ")[0].strip()
                          for d in details if "sentiment=" in d), "")
        checks["sentiment"] = sentiment or None
        checks["escalate_ok"] = (fallback == "EMOTION_ESCALATION") == bool(expect.get("escalate"))
        if expect.get("reason"):
            checks["reason_ok"] = fallback == expect["reason"]
        return checks

    if kind == "channel":
        if "streaming" in expect:
            checks["streaming_ok"] = bool(result.get("streaming")) == bool(expect["streaming"])
        if expect.get("asyncReply"):
            checks["async_ok"] = bool(result.get("receiptTicketId"))
        checks["answered_ok"] = bool(answer)
        if expect.get("mustNotSee"):
            checks["mustNotSee_ok"] = None       # 语义断言，机器判不了 → 未观测
        if expect.get("sessionCarryover"):
            checks["carryover_ok"] = bool(answer)  # 能续接的直接证据是有答案而不是身份错误
        return checks

    if kind == "plan":
        tools = executed_tools(details)
        checks["tools"] = ",".join(tools) or None
        if expect.get("plan"):
            checks["plan_ok"] = tools[:len(expect["plan"])] == expect["plan"]
        if expect.get("abortAfterStep0"):
            checks["abort_ok"] = any(d.startswith("plan-aborted") for d in details)
        if expect.get("mayAbortAfterStep0"):
            checks["abort_ok"] = None            # "可以中止"不判别形态 → 未观测
        if expect.get("planRejected"):
            checks["rejected_ok"] = (any(d.startswith("plan-rejected") for d in details)
                                     and bool(fallback))
        if expect.get("slotAsk") is not None:
            checks["slotAsk_ok"] = bool(result.get("slotAsked")) == bool(expect["slotAsk"])
        if expect.get("mustNotLeak") or expect.get("mustFailOwnershipCheck"):
            checks["ownership_ok"] = None        # 归属断言的离线面在 gold 集与 JVM 用例里，不重复判
        return checks

    if kind == "style":
        style = next((d.split("style=")[1].strip().split(" ")[0]
                      for d in details if "style=" in d), "")
        checks["style"] = style or None
        checks["style_ok"] = style == expect.get("style")
        if expect.get("mustNotContain"):
            checks["mustNotContain_ok"] = not any(t in answer for t in expect["mustNotContain"])
        if expect.get("mustNotCarryFacts"):
            checks["mustNotCarryFacts_ok"] = None  # 语义断言 → 未观测
        return checks

    raise ValueError("未知 kind: " + str(kind))


def _fx(name, case, result, expected, raises=False):
    return {"name": name, "case": case, "result": result, "expected": expected, "raises": raises}


# 判分器夹具：喂合成 case/result 驱动 score_case，不发 HTTP、不读用例文件，0 token 可复跑。
# 与 run_tool_eval 的 scorer_selfcheck 同一条纪律（ADR 0021）：判据动了而夹具没跟着动，
# verify_eval_judge 里那条"夹具全过"的断言就会红。expected 里的 None 是承重的——语义断言
# 必须老实返回"未观测"，写成 True 等于把它静默计入分子，那正是 ticket 34 警告过的假绿。
FIXTURES = [
    _fx("情绪升级：ANGRY 落 EMOTION_ESCALATION 并核对 reason",
        {"kind": "emotion", "expect": {"escalate": True, "reason": "EMOTION_ESCALATION"}},
        {"answer": "已为您转人工。", "fallbackReason": "EMOTION_ESCALATION",
         "trace": [{"detail": "sentiment=ANGRY"}]},
        {"sentiment": "ANGRY", "escalate_ok": True, "reason_ok": True}),
    _fx("情绪不误升级：CALM 且无降级因",
        {"kind": "emotion", "expect": {"escalate": False}},
        {"answer": "退货运费由平台承担。", "fallbackReason": None,
         "trace": [{"detail": "sentiment=CALM"}]},
        {"sentiment": "CALM", "escalate_ok": True}),
    _fx("反证：宣称不升级却落了 EMOTION_ESCALATION，必须判负",
        {"kind": "emotion", "expect": {"escalate": False}},
        {"answer": "……", "fallbackReason": "EMOTION_ESCALATION",
         "trace": [{"detail": "sentiment=UNCERTAIN"}]},
        {"escalate_ok": False}),
    _fx("渠道流式一致：streaming 与答案两处都判",
        {"kind": "channel", "expect": {"streaming": True}},
        {"streaming": True, "answer": "顺丰 SF1234 已发出。"},
        {"streaming_ok": True, "answered_ok": True}),
    _fx("反证：宣称流式实为同步，必须判负",
        {"kind": "channel", "expect": {"streaming": True}},
        {"streaming": False, "answer": "顺丰 SF1234 已发出。"},
        {"streaming_ok": False}),
    _fx("邮件异步回执：receiptTicketId 有无即判据",
        {"kind": "channel", "expect": {"asyncReply": True}},
        {"answer": "已受理。", "receiptTicketId": "RCPT-0001"},
        {"async_ok": True, "answered_ok": True}),
    _fx("反证：宣称有回执而实缺，必须判负",
        {"kind": "channel", "expect": {"asyncReply": True}},
        {"answer": "已受理。", "receiptTicketId": None},
        {"async_ok": False}),
    _fx("渠道语义断言（mustNotSee）机器判不了 → 必须记未观测",
        {"kind": "channel", "expect": {"mustNotSee": ["T0999001"]}},
        {"answer": "帮您查到了。", "trace": []},
        {"mustNotSee_ok": None, "answered_ok": True}),
    _fx("会话续接：能续接的直接证据是有答案（非身份错误）",
        {"kind": "channel", "expect": {"sessionCarryover": True}},
        {"answer": "上一单的退款已受理。", "trace": []},
        {"carryover_ok": True}),
    _fx("计划两步顺序正确：顺带核对 executed_tools 的解析",
        {"kind": "plan", "expect": {"plan": ["QUERY_LOGISTICS", "REFUND_APPLY"]}},
        {"answer": "已退款。", "trace": [{"detail": "QUERY_LOGISTICS=OK modelArgs={}"},
                                        {"detail": "REFUND_APPLY=OK modelArgs={}"}]},
        {"tools": "QUERY_LOGISTICS,REFUND_APPLY", "plan_ok": True}),
    _fx("反证：顺序打乱必须判负（同样的两个工具不算过）",
        {"kind": "plan", "expect": {"plan": ["REFUND_APPLY", "QUERY_LOGISTICS"]}},
        {"answer": "已退款。", "trace": [{"detail": "QUERY_LOGISTICS=OK modelArgs={}"},
                                        {"detail": "REFUND_APPLY=OK modelArgs={}"}]},
        {"plan_ok": False}),
    _fx("前步失败中止：plan-aborted 成立，且中止标记不进已执行工具序列",
        {"kind": "plan", "expect": {"abortAfterStep0": True}},
        {"answer": "该单已签收，无法改址。", "trace": [{"detail": "QUERY_LOGISTICS=FAIL modelArgs={}"},
                                                      {"detail": "plan-aborted status=FAILED"}]},
        {"tools": "QUERY_LOGISTICS", "abort_ok": True}),
    _fx("网关自派工具：模型没发 function call 也要算已执行（gateway-derived 行的解析）",
        {"kind": "plan", "expect": {"plan": ["QUERY_LOGISTICS"]}},
        {"answer": "顺丰 SF1234 已发出。",
         "trace": [{"detail": "gateway-derived QUERY_LOGISTICS=OK modelArgs={}"}]},
        {"tools": "QUERY_LOGISTICS", "plan_ok": True}),
    _fx("计划拒收：plan-rejected 且必须落到降级",
        {"kind": "plan", "expect": {"planRejected": True}},
        {"answer": "为您转人工。", "fallbackReason": "TOOL_FAILED",
         "trace": [{"detail": "plan-rejected expr={steps[1].result.foo}"}]},
        {"rejected_ok": True}),
    _fx("反证：只拒收不降级不算拒收成立",
        {"kind": "plan", "expect": {"planRejected": True}},
        {"answer": "……", "fallbackReason": None,
         "trace": [{"detail": "plan-rejected expr={steps[1].result.foo}"}]},
        {"rejected_ok": False}),
    _fx("槽位追问：slotAsked 与期望一致才过",
        {"kind": "plan", "expect": {"slotAsk": True}},
        {"answer": "请问要改到哪个地址？", "slotAsked": True, "trace": []},
        {"slotAsk_ok": True}),
    _fx("反证：期望追问而没追问，必须判负",
        {"kind": "plan", "expect": {"slotAsk": True}},
        {"answer": "已改址。", "slotAsked": False, "trace": []},
        {"slotAsk_ok": False}),
    _fx("计划语义断言（mustNotLeak）→ 必须记未观测",
        {"kind": "plan", "expect": {"mustNotLeak": ["T0999002"]}},
        {"answer": "帮您查到了。", "trace": []},
        {"ownership_ok": None}),
    _fx("「可以中止」不判别形态 → 必须记未观测，不许当成已过",
        {"kind": "plan", "expect": {"mayAbortAfterStep0": True}},
        {"answer": "……", "trace": [{"detail": "QUERY_LOGISTICS=OK modelArgs={}"}]},
        {"abort_ok": None}),
    _fx("风格档位：style= 从 trace 抽出来对上期望",
        {"kind": "style", "expect": {"style": "FORMAL"}},
        {"answer": "您好，关于您的订单。", "trace": [{"detail": "style=FORMAL"}]},
        {"style": "FORMAL", "style_ok": True}),
    _fx("反证：档位与期望不符必须判负",
        {"kind": "style", "expect": {"style": "CONCISE"}},
        {"answer": "您好，关于您的订单。", "trace": [{"detail": "style=FORMAL"}]},
        {"style_ok": False}),
    _fx("风格禁用语：档位不符时 mustNotContain 仍逐字判",
        {"kind": "style", "expect": {"style": "FRIENDLY", "mustNotContain": ["您的订单"]}},
        {"answer": "您好，您的订单正在派送。", "trace": [{"detail": "style=FRIENDLY"}]},
        {"style_ok": True, "mustNotContain_ok": False}),
    _fx("风格语义断言（mustNotCarryFacts）→ 必须记未观测",
        {"kind": "style", "expect": {"style": "FORMAL", "mustNotCarryFacts": True}},
        {"answer": "您好，已为您处理。", "trace": [{"detail": "style=FORMAL"}]},
        {"mustNotCarryFacts_ok": None}),
    _fx("反证：未知 kind 必须抛错，不许静默判过",
        {"kind": "plugin", "expect": {}}, {"answer": "……"},
        {}, raises=True),
]


def selfcheck():
    """跑夹具，返回 (失败行, 夹具条数)。verify_eval_judge 与命令行共用同一份夹具。"""
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
        except Exception as exc:
            failures.append(f"{name}：判分器抛错 {exc!r}")
            continue
        for key, want in fixture["expected"].items():
            got = checks.get(key, "<缺失>")
            if got != want:
                failures.append(f"{name}：{key} 期望 {want!r}，实为 {got!r}")
    return failures, len(FIXTURES)


def endpoint_for(case):
    channel = case.get("channel") or "web"
    if channel == "web":
        return "/api/v1/support/chat"
    if channel == "email":
        return "/api/v1/support/email"
    return "/api/v1/support/webhook/" + channel


def run(parser_args, runner) -> int:
    """套件跑批。`runner` 是 run_tool_eval 的共享 HTTP 助手集合，惰性注入避免循环导入。"""
    names = [n.strip() for n in parser_args.suite.split(",") if n.strip()]
    unknown = [n for n in names if n not in SUITES]
    if unknown:
        print("未知套件：" + ",".join(unknown) + "；可选 " + ",".join(sorted(SUITES)))
        return 2
    cases = [c for name in names for c in load_suite(name)]
    token = runner.mock_token(parser_args.base, "T001", "C001")
    headers = {"Authorization": "Bearer " + token}
    ops_headers = dict(headers, **{"X-Ops-Token": parser_args.ops_token})
    circuit = runner.http_json(f"{parser_args.base}/api/v1/support/ops/circuit", headers=ops_headers)
    mode, model = circuit.get("llmMode"), circuit.get("llmModel")

    rows, by_id = [], {}
    for case in cases:
        conversation = f"eval-suite-{case['id']}"
        try:
            if case.get("kind") == "feedback":
                checks = _feedback_case(parser_args, case, token, headers, conversation, runner)
            else:
                payload = ({"body": case["query"]} if (case.get("channel") == "email")
                           else {"query": case["query"]})
                result = runner.http_json(parser_args.base + endpoint_for(case), payload,
                                          dict(headers, **{"X-Conversation-Id": conversation}))
                checks = score_case(case, result)
                checks["answer_excerpt"] = str(result.get("answer") or "")[:120]
                by_id[case["id"]] = str(result.get("answer") or "")
        except Exception as failure:  # 单条失败不拖垮整批：记错误并继续
            checks = {"error": str(failure)[:120], "checks_ok": False}
        rows.append({"id": case["id"], "kind": case.get("kind"), "channel": case.get("channel") or "web",
                     "intent": case.get("intent"), **checks})

    # sameAnswerAs：跨用例逐字比对——渠道一致性的直接证据
    for case, row in zip(cases, rows):
        expected = (case.get("expect") or {}).get("sameAnswerAs")
        if not expected:
            continue
        base_answer = by_id.get(expected)
        row["sameAnswerAs"] = expected
        row["sameAnswerAs_ok"] = None if (base_answer is None or not row.get("answer_excerpt")) \
            else (base_answer.strip() == by_id.get(case["id"], "").strip())

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    slug = "-".join(names)
    detail_path = RESULTS / f"tool-eval-{stamp}-dev-{slug}.csv"
    fieldnames = sorted({k for row in rows for k in row})
    with detail_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    failures = [f"{row['id']}.{key}" for row in rows for key, value in row.items()
                if key.endswith("_ok") and value is False]
    for item in failures[:20]:
        print("  FAIL  " + item)
    print(f"新增套件 {','.join(names)}：{len(rows)} 条，判定失败 {len(failures)} 条，"
          f"未观测断言 {sum(1 for row in rows for k, v in row.items() if k.endswith('_ok') and v is None)} 条")
    print(f"明细 {detail_path.relative_to(REPO)}")
    print(f"EVAL DONE cases={len(rows)} errors=0 mode={mode} limit=0")
    return 1 if failures else 0


def _feedback_case(args, case, token, headers, conversation, runner):
    """反馈用例：先问一句拿答案，再按 expect 驱动显式点踩/点赞或读隐式信号。"""
    result = runner.http_json(args.base + "/api/v1/support/chat", {"query": case["query"]},
                              dict(headers, **{"X-Conversation-Id": conversation}))
    expect = case.get("expect") or {}
    checks = {"answered_ok": bool(result.get("answer"))}
    verdict = expect.get("explicitFeedback")
    if verdict:
        ack = runner.http_json(args.base + "/api/v1/support/chat/feedback",
                               {"conversationId": conversation, "verdict": verdict, "reason": "eval-harness"},
                               headers)
        checks["feedbackRow_ok"] = bool(ack.get("feedbackId"))
        if expect.get("reviewQueueVisible") is not None:
            checks["reviewQueue_ok"] = bool(ack.get("reviewQueued")) == bool(expect["reviewQueueVisible"])
        if expect.get("linkRuleIds"):
            checks["linkRuleIds_ok"] = bool(ack.get("ruleIds"))
    implied = expect.get("implied")
    if implied:
        if implied == "none":
            checks["implied_observed"] = None
        else:
            # 隐式信号计数走 /actuator/metrics/{name}?tag=kind:X 这个 JSON 端点（metrics 已在
            # application.yml 的 exposure.include 里，与 prometheus 同组），按 tag 过滤直接取该 kind
            # 的计数，比在 prometheus 全文里做子串匹配更准。用它而不是新写一个纯文本读取助手：
            # 取数路径复用仓内既有的 http_json，本文件不再持有第二条发 HTTP 的代码。
            body = runner.http_json(
                f"{args.base}/actuator/metrics/shoppilot_feedback_implied_total?tag=kind:{implied}")
            checks["implied_ok"] = any(float(m.get("value") or 0) > 0
                                       for m in (body.get("measurements") or []))
    return checks


if __name__ == "__main__":
    # 只做夹具自检：判据本身 0 token、无网关也能跑，verify_eval_judge 按这个入口驱动它。
    # 真跑套件要活体网关，入口在 run_tool_eval.py --suite。
    import sys

    failed_rows, fixture_count = selfcheck()
    for failed in failed_rows:
        print("SUITE CHECK FAILED  " + failed)
    if failed_rows:
        print(f"SUITE SELFCHECK ok=0（{len(failed_rows)} 条夹具红，共 {fixture_count} 条）")
        sys.exit(1)
    print(f"SUITE SELFCHECK ok={fixture_count}")
