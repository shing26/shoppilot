#!/usr/bin/env python3
"""round50 评测集扩展的共享层（0 token、无网关）。

三份新用例集（`eval/regression-cases.jsonl` / `eval/edge-cases.jsonl` /
`eval/security-cases.jsonl`）与活体跑器共用这里的四件事：

1. **读**：JSONL 逐行解析，字段与归一化口径只有一份；
2. **判**：把「期望」变成可机器执行的断言（intent / tool / args / slotAsk /
   required / forbidden / doc / section / fallback / ticket / HTTP 状态 / 泄漏面）；
3. **正对照**：为每条用例构造一份**应当通过**的观测，证明用例不是自相矛盾；
4. **变异**：对每条用例注入一个对应缺陷（换错工具、丢掉事实、放宽 403、泄漏标记……），
   证明它会变红。没有变异红的用例不收。

它不连任何服务，也不读检索结果；活体跑器负责把真实响应装成同一形状的观测。
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
import unicodedata
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCRIPTS = REPO / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import answer_facts  # noqa: E402
import run_tool_eval  # noqa: E402

REGRESSION = REPO / "eval" / "regression-cases.jsonl"
EDGE = REPO / "eval" / "edge-cases.jsonl"
SECURITY = REPO / "eval" / "security-cases.jsonl"

# 既有判据文件的地址。round50 只新增用例集，一个字都不许改；校验器把「没改」变成可执行断言。
# 需要更新时由所有者书面同意并同步改这里——不要为了让自己变绿而改哈希。
EXISTING_JUDGE_FILES = {
    "eval/tool-cases.jsonl": "a8c88525fa7c7d6087aabc290d53fb17c29db3b1b4416c5a73c5cf6802d21a65",
    "eval/answer-cases.jsonl": "9acd18662765ad34f62b8e95013f9ac9e0b86bfce78b6e2a5f4d9ca4f1839cd7",
    "eval/retrieval-cases.json": "75de867f80b432b901e51628575441be74f51aedbc7611804b8e0968af9bbba2",
    "eval/handoff-golden.jsonl": "3d91c590e9d9cfd6af2599be71c1aa3acddb10faa25f7219a0980d7eaec1a6f5",
}

TOOLS = {"queryOrderDetail", "queryLogistics", "modifyDeliveryAddress", "applyRefund"}
INTENTS = {
    "POLICY_RETURN", "POLICY_SHIPPING", "POLICY_PROMO", "POLICY_FRESH", "POLICY_HOTEL",
    "ACTION_REFUND", "ACTION_LOGISTICS", "ACTION_ADDRESS", "ACTION_ORDER",
    "ESCALATE", "UNKNOWN",
}
SECURITY_CATEGORIES = {"cross_tenant", "conversation", "role", "tool_surface", "injection", "sse_idempotency"}
EDGE_CATEGORIES = {"multi_intent", "fresh_pin", "slot_continue", "typo", "unknown", "empty_recall",
                   "numeric_homograph"}
PUNCTUATION = re.compile(r"[\s\W_]+", re.UNICODE)
HEADING = re.compile(r"^##\s+(.+?)\s*$")


def normalize(text: str) -> str:
    folded = unicodedata.normalize("NFKC", text or "").lower()
    return PUNCTUATION.sub("", folded)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        raise FileNotFoundError(path)
    cases = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            cases.append(json.loads(line))
        except json.JSONDecodeError as error:
            raise ValueError(f"{path.name}:{number} 不是合法 JSON：{error}") from error
    return cases


def load_cases(name: str) -> list[dict]:
    return load_jsonl({"regression": REGRESSION, "edge": EDGE, "security": SECURITY}[name])


def existing_queries() -> dict[str, str]:
    """既有判据文件里出现过的问句 → 出处。新集合里撞上这些问句的只能放 dev。"""
    found: dict[str, str] = {}
    paths = [REPO / "eval" / "tool-cases.jsonl", REPO / "eval" / "answer-cases.jsonl",
             REPO / "eval" / "golden-ux-30.jsonl"]
    paths += sorted((REPO / "eval").glob("cases-part*.jsonl"))
    for path in paths:
        for case in load_jsonl(path):
            query = normalize(case.get("query", ""))
            if query:
                found.setdefault(query, path.name)
    retrieval = json.loads((REPO / "eval" / "retrieval-cases.json").read_text(encoding="utf-8"))
    for case in retrieval.get("cases") or []:
        query = normalize(case.get("query", ""))
        if query:
            found.setdefault(query, "retrieval-cases.json")
    return found


def parse_front_matter(raw: str) -> tuple[dict, str]:
    if not raw.startswith("---\n"):
        return {}, raw
    end = raw.find("\n---\n", 4)
    if end < 0:
        return {}, raw
    meta = {}
    for line in raw[4:end].splitlines():
        key, sep, value = line.partition(":")
        if sep:
            meta[key.strip()] = value.strip()
    return meta, raw[end + 5:]


def knowledge_docs() -> dict[str, dict]:
    docs = {}
    for path in sorted((REPO / "knowledge").glob("*.md")):
        raw = path.read_text(encoding="utf-8")
        meta, body = parse_front_matter(raw)
        headings = {line[3:].strip() for line in body.splitlines() if line.startswith("## ")}
        docs[path.stem] = {"title": meta.get("title", ""), "headings": headings, "body": raw}
    return docs


def prompt_texts() -> dict[str, str]:
    root = REPO / "shoppilot-gateway" / "src" / "main" / "resources" / "prompts"
    return {str(path.relative_to(REPO)): path.read_text(encoding="utf-8")
            for path in sorted(root.rglob("*.md"))}


def leak_into_corpus(query: str, docs: dict[str, dict], prompts: dict[str, str]) -> list[str]:
    """评测问句不得回流语料或提示词。按归一化后的整句子串判，短于 8 字的问句不判。"""
    needle = normalize(query)
    if len(needle) < 8:
        return []
    hits = []
    for doc, meta in docs.items():
        if needle in normalize(meta["body"]):
            hits.append(f"knowledge/{doc}.md")
    for name, text in prompts.items():
        if needle in normalize(text):
            hits.append(name)
    return hits


def _as_list(value):
    if value is None:
        return []
    return value if isinstance(value, list) else [value]


def expected_tools(expect: dict) -> list[str]:
    return _as_list(expect.get("tool"))


def chain_entry(tool: str, status: str = "OK", args: dict | None = None) -> dict:
    return {"tool": tool, "status": status, "args": args or {}, "fabricated": False}


def good_answer(case: dict) -> str:
    """构造一份满足 required / forbidden 的答案：每条 required 取第一个备选。"""
    expect = case.get("expect") or {}
    parts = []
    for fact in expect.get("required") or []:
        alternatives = fact if isinstance(fact, list) else [fact]
        parts.append(str(alternatives[0]))
    answer = "；".join(parts)
    for forbidden in expect.get("forbidden") or []:
        terms = forbidden if isinstance(forbidden, list) else [forbidden]
        for term in terms:
            if term and answer_facts.normalize(term) in answer_facts.normalize(answer):
                # 自相矛盾：required 的第一备选里含 forbidden 词
                raise ValueError(f"{case.get('id')} 的正对照答案自带 forbidden 词 {term!r}")
    return answer or "已按条款处理。"


def _good_http_status(expect: dict) -> int:
    status = expect.get("status") or expect.get("httpStatus") or 200
    if isinstance(status, list):
        # 正对照优先挑成功码；没有成功码就挑列表第一个
        return next((code for code in status if 200 <= int(code) < 300), int(status[0]))
    return int(status)


def good_observation(case: dict) -> dict:
    """一条用例的「正确观测」。跑分器与变异器都以它为起点，避免两套构造。"""
    expect = case.get("expect") or {}
    tools = expected_tools(expect)
    status = expect.get("expectStatus") or "OK"
    plan = []
    if tools:
        for tool in tools[:1]:
            plan.append({"tool": tool, "status": status, "arguments": expect.get("args") or {},
                         "attempt": 1, "reflection": False})
    if expect.get("plan_tools"):
        statuses = expect.get("plan_status") or {}
        any_of = expect.get("plan_status_in") or {}
        for tool in expect["plan_tools"]:
            status = statuses.get(tool) or (any_of.get(tool) or ["OK"])[0]
            plan.append({"tool": tool, "status": status,
                         "arguments": expect.get("args") or {}, "attempt": 1, "reflection": False})
    if expect.get("plan_empty"):
        plan = []
    details = []
    if expect.get("doc"):
        details.append({"ruleId": "R", "heading": expect.get("section") or "标题", "doc": expect["doc"]})
    citations = [] if expect.get("noCitations") else (["R"] if details or expect.get("citations") else [])
    if expect.get("citations"):
        citations = list(expect["citations"])
    answer = good_answer(case)
    for marker in expect.get("answer_present") or []:
        if marker not in answer:
            answer = (answer + "；" + marker).strip("；")
    body = case.get("body_fixture") or " ".join(str(marker) for marker in expect.get("body_present") or [])
    return {
        "intent": expect.get("intent") or "UNKNOWN",
        "chain": ([chain_entry(tools[0], status, expect.get("args"))] if tools else []),
        "slot_asked": bool(expect.get("slotAsk")),
        "tool_used": bool(expect.get("tool_used", bool(tools))),
        "answer": answer,
        "citations": citations,
        "citation_details": details,
        "fallback": expect.get("fallback"),
        "ticket_id": "T-DEMO-1" if expect.get("ticket") else "",
        "plan": plan,
        "context_history": int(expect.get("context_history_min") or 0),
        "tokens": 0 if expect.get("tokens_zero") else 42,
        "http_status": _good_http_status(expect),
        "body": body,
        "error_code": expect.get("errorCode") or "",
        "json": dict(expect.get("json") or {}),
    }


def _score_tool(expect: dict, obs: dict) -> list[str]:
    reasons = []
    judged = run_tool_eval.judge(expect, {
        "chain": obs["chain"],
        "answer": obs["answer"],
        "intent": obs["intent"],
        "slot_asked": obs["slot_asked"],
        "fallback_reason": obs["fallback"],
    })
    if not judged["checks_ok"]:
        for key in ("tool_ok", "args_ok", "slot_structured_ok", "fabricated", "escalate_ok", "status_ok"):
            value = judged.get(key)
            if value is False or (isinstance(value, str) and value):
                reasons.append(f"{key}={value}")
    for tool in _as_list(expect.get("notTool")):
        if tool in [link["tool"] for link in obs["chain"] if link.get("tool")]:
            reasons.append(f"notTool 命中 {tool}")
    return reasons


def _score_facts(expect: dict, obs: dict) -> list[str]:
    if not expect.get("required") and not expect.get("forbidden"):
        return []
    judged = answer_facts.judge({"required": expect.get("required") or [],
                                 "forbidden": expect.get("forbidden") or []},
                                obs["answer"])
    reasons = []
    if judged["answer_empty"]:
        reasons.append("答案为空")
    if judged["missing"]:
        reasons.append("缺事实：" + "、".join(judged["missing"]))
    if judged["forbidden_hits"]:
        reasons.append("命中 forbidden：" + "、".join(judged["forbidden_hits"]))
    return reasons


def _score_security(expect: dict, obs: dict) -> list[str]:
    reasons = []
    statuses = expect.get("status")
    if statuses is not None:
        allowed = statuses if isinstance(statuses, list) else [statuses]
        if obs["http_status"] not in allowed:
            reasons.append(f"HTTP {obs['http_status']} 不在 {allowed}")
    for marker in expect.get("body_present") or []:
        if marker not in obs["body"]:
            reasons.append(f"响应体缺 {marker!r}")
    for marker in expect.get("body_absent") or []:
        if marker in obs["body"]:
            reasons.append(f"响应体泄漏 {marker!r}")
    for pattern in expect.get("body_regex_absent") or []:
        if re.search(pattern, obs["body"]):
            reasons.append(f"响应体命中禁用正则 {pattern}")
    if expect.get("errorCode") and obs["error_code"] != expect["errorCode"]:
        reasons.append(f"errorCode={obs['error_code']} 期望 {expect['errorCode']}")
    answer = obs["answer"]
    for marker in expect.get("answer_present") or []:
        if marker not in answer:
            reasons.append(f"答案缺 {marker!r}")
    for marker in expect.get("answer_absent") or []:
        if marker in answer:
            reasons.append(f"答案泄漏/越权承诺 {marker!r}")
    for pattern in expect.get("answer_regex_absent") or []:
        if re.search(pattern, answer):
            reasons.append(f"答案命中禁用正则 {pattern}")
    if expect.get("citations_empty") and obs["citations"]:
        reasons.append("引用应为空")
    return reasons


def score(case: dict, obs: dict) -> dict:
    """把所有期望压成一组可读原因；空列表 = 通过。"""
    expect = case.get("expect") or {}
    reasons: list[str] = []

    if expect.get("intent") and obs["intent"] != expect["intent"]:
        reasons.append(f"intent={obs['intent']} 期望 {expect['intent']}")
    for forbidden_intent in _as_list(expect.get("intentNot")):
        if obs["intent"] == forbidden_intent:
            reasons.append(f"intent 命中禁用值 {forbidden_intent}")

    # 只有出现工具面断言时才走 run_tool_eval.judge；纯答案题不该被「零工具」反向约束。
    if any(key in expect for key in ("tool", "slotAsk", "mustNotContainArgs", "expectStatus", "escalate")):
        reasons += _score_tool(expect, obs)

    reasons += _score_facts(expect, obs)

    if "fallback" in expect and obs["fallback"] != expect["fallback"]:
        reasons.append(f"fallback={obs['fallback']} 期望 {expect['fallback']}")
    if expect.get("notEscalate") and (obs["intent"] == "ESCALATE" or obs["fallback"] == "EMOTION_ESCALATION"):
        reasons.append("不应升级却升级了")
    if "ticket" in expect and bool(obs["ticket_id"]) != bool(expect["ticket"]):
        reasons.append(f"ticket={bool(obs['ticket_id'])} 期望 {expect['ticket']}")

    if expect.get("doc"):
        docs = [detail.get("doc") for detail in obs["citation_details"]]
        if expect["doc"] not in docs:
            reasons.append(f"引用文档={docs} 期望 {expect['doc']}")
    if expect.get("noCitations") and obs["citations"]:
        reasons.append(f"空召回不该给引用，实给 {obs['citations']}")
    if expect.get("section"):
        headings = [detail.get("heading") for detail in obs["citation_details"]]
        if expect["section"] not in headings:
            reasons.append(f"引用小节={headings} 期望 {expect['section']}")

    if "tool_used" in expect and bool(obs.get("tool_used")) != bool(expect["tool_used"]):
        reasons.append(f"toolUsed={bool(obs.get('tool_used'))} 期望 {expect['tool_used']}")
    if expect.get("plan_empty") and obs["plan"]:
        reasons.append("plan 应为空")
    plan_tools = [step.get("tool") for step in obs["plan"]]
    for tool in expect.get("plan_tools") or []:
        if tool not in plan_tools:
            reasons.append(f"plan 缺 {tool}")
    for tool in expect.get("plan_tools_absent") or []:
        if tool in plan_tools:
            reasons.append(f"plan 不应出现 {tool}")
    for tool, wanted in (expect.get("plan_status") or {}).items():
        got = next((step.get("status") for step in obs["plan"] if step.get("tool") == tool), None)
        if got != wanted:
            reasons.append(f"plan[{tool}].status={got} 期望 {wanted}")
    for tool, wanted in (expect.get("plan_status_in") or {}).items():
        got = next((step.get("status") for step in obs["plan"] if step.get("tool") == tool), None)
        if got not in wanted:
            reasons.append(f"plan[{tool}].status={got} 期望属于 {wanted}")
    if expect.get("context_history_min") is not None:
        if obs["context_history"] < int(expect["context_history_min"]):
            reasons.append(f"context.historyTurns={obs['context_history']} 小于 {expect['context_history_min']}")
    if expect.get("context_history_max") is not None:
        if obs["context_history"] > int(expect["context_history_max"]):
            reasons.append(f"context.historyTurns={obs['context_history']} 大于 {expect['context_history_max']}")
    if expect.get("tokens_zero") and obs["tokens"] != 0:
        reasons.append(f"token={obs['tokens']} 期望 0")
    for key, wanted in (expect.get("json") or {}).items():
        got = obs.get("json", {}).get(key) if isinstance(obs.get("json"), dict) else None
        if got != wanted:
            reasons.append(f"json.{key}={got} 期望 {wanted}")

    reasons += _score_security(expect, obs)
    return {"ok": not reasons, "reasons": reasons}


# ---- 变异：每条用例至少一个对应缺陷，注入后必须红 ----


def mutations(case: dict) -> list[tuple[str, dict]]:
    expect = case.get("expect") or {}
    base = good_observation(case)
    mutants: list[tuple[str, dict]] = []

    def add(name: str, **changes):
        clone = json.loads(json.dumps(base))
        clone.update(changes)
        mutants.append((name, clone))

    if expect.get("intent"):
        other = "UNKNOWN" if expect["intent"] != "UNKNOWN" else "POLICY_RETURN"
        add("intent 误判", intent=other)
    if "tool" in expect:
        tools = expected_tools(expect)
        if tools:
            wrong = "queryOrderDetail" if tools[0] != "queryOrderDetail" else "applyRefund"
            mutated = json.loads(json.dumps(base))
            mutated["chain"] = [chain_entry(wrong, "OK", expect.get("args"))]
            mutants.append(("工具选错", mutated))
        else:
            add("不该调用工具", chain=[chain_entry("applyRefund", "OK", {})])
    if "slotAsk" in expect:
        add("槽位追问反转", slot_asked=not bool(expect["slotAsk"]))
    if expect.get("required"):
        add("答案丢事实", answer="")
    if expect.get("forbidden"):
        term = expect["forbidden"][0]
        term = term[0] if isinstance(term, list) else term
        # 正对照答案里可能有“不发”“无需”这类否定事实；直接拼 forbidden 会落进判分器的
        # 否定守卫窗口，看起来像“引用错误说法再否定”。加一段无否定词的引导语把窗口隔开。
        add("答案命中 forbidden", answer=f"{base['answer']}。另外明确{term}")
    if expect.get("doc"):
        add("引用文档错", citation_details=[{"ruleId": "R", "heading": "标题", "doc": "return-01-7day-basic"}])
    if expect.get("section"):
        add("引用小节错", citation_details=[{"ruleId": "R", "heading": "无关小节", "doc": expect.get("doc") or "return-01-7day-basic"}])
    if "fallback" in expect:
        add("fallback 漂移", fallback="EMOTION_ESCALATION" if expect["fallback"] != "EMOTION_ESCALATION" else None)
    if expect.get("notEscalate"):
        add("误升级", intent="ESCALATE", fallback="EMOTION_ESCALATION")
    if "ticket" in expect:
        add("工单有无反转", ticket_id="" if expect["ticket"] else "T-X")
    if "tool_used" in expect:
        add("toolUsed 反转", tool_used=not bool(expect["tool_used"]))
    if expect.get("plan_empty"):
        add("plan 非空", plan=[{"tool": "applyRefund", "status": "OK", "arguments": {}, "attempt": 1, "reflection": False}])
    if expect.get("plan_tools"):
        add("plan 缺工具", plan=[])
    if expect.get("plan_tools_absent"):
        tool = expect["plan_tools_absent"][0]
        add("plan 出现禁用工具", plan=[{"tool": tool, "status": "OK", "arguments": {}, "attempt": 1, "reflection": False}])
    if expect.get("plan_status"):
        tool, wanted = next(iter(expect["plan_status"].items()))
        drifted = "OK" if wanted != "OK" else "NOT_FOUND"
        add("plan 状态漂移", plan=[{"tool": tool, "status": drifted, "arguments": {}, "attempt": 1,
                                   "reflection": False}])
    if expect.get("plan_status_in"):
        tool, wanted = next(iter(expect["plan_status_in"].items()))
        drifted = next(status for status in ("OK", "NOT_FOUND", "STATE_NOT_ALLOWED")
                       if status not in wanted)
        add("plan 状态漂移", plan=[{"tool": tool, "status": drifted, "arguments": {}, "attempt": 1,
                                   "reflection": False}])
    if expect.get("context_history_min") is not None:
        add("会话历史丢失", context_history=0)
    if expect.get("context_history_max") is not None:
        add("串到他人历史", context_history=int(expect["context_history_max"]) + 1)
    if expect.get("tokens_zero"):
        add("回放仍调模型", tokens=99)
    if expect.get("noCitations"):
        add("空召回却给引用", citations=["R"], citation_details=[{"ruleId": "R", "heading": "h", "doc": "fresh-01-claim-window"}])

    status = expect.get("status")
    if status is not None:
        allowed = status if isinstance(status, list) else [status]
        add("状态码放宽", http_status=200 if 200 not in allowed else 403)
    for marker in expect.get("body_present") or []:
        add(f"响应体缺 {marker}", body=base["body"].replace(marker, ""))
    for marker in expect.get("body_absent") or []:
        add(f"响应体泄漏 {marker}", body=base["body"] + marker)
    for marker in expect.get("answer_absent") or []:
        add(f"答案泄漏 {marker}", answer=base["answer"] + marker)
    for marker in expect.get("answer_present") or []:
        add(f"答案缺 {marker}", answer=base["answer"].replace(marker, ""))
    for pattern in expect.get("answer_regex_absent") or []:
        add(f"答案命中正则 {pattern}", answer=base["answer"] + "13800001234")
    if expect.get("citations_empty"):
        add("引用非空", citations=["R"], citation_details=[{"ruleId": "R", "heading": "h", "doc": "d"}])
    if expect.get("errorCode"):
        add("错误码漂移", error_code="internal_error")
    if expect.get("json"):
        key = next(iter(expect["json"]))
        add("JSON 字段漂移", json={key: "__MUTANT__"})
    return mutants


def selfcheck(case: dict) -> list[str]:
    """正对照 + 每个变异都必须红。返回问题列表（空 = 用例可用）。"""
    case_id = case.get("id", "<无 id>")
    problems = []
    try:
        base = good_observation(case)
    except ValueError as error:
        return [f"{case_id}: {error}"]
    verdict = score(case, base)
    if not verdict["ok"]:
        problems.append(f"{case_id}: 正对照不通过：{'；'.join(verdict['reasons'])}")
    variants = mutations(case)
    if not variants:
        problems.append(f"{case_id}: 没有任何变异，无法证明用例会红")
    for name, mutant in variants:
        if score(case, mutant)["ok"]:
            problems.append(f"{case_id}: 变异「{name}」仍是绿的，用例杀不死这个缺陷")
    return problems


# ---- 安全用例：一步一断言，判分复用同一套 score / mutations ----


def observation_from_chat(payload: dict, http_status: int = 200, body: str = "") -> dict:
    """把 ChatResponse（或错误体）装成 score() 认得的观测。

    活体跑器只用这一处映射：字段名与 `ChatController.ChatResponse` 一一对应，
    避免两个跑器各写一份、字段漂移后一绿一红。
    """
    payload = payload or {}
    context = payload.get("context") or {}
    plan = []
    for step in payload.get("plan") or []:
        tool = step.get("tool") or ""
        plan.append({"tool": run_tool_eval.TOOL_API_NAMES.get(tool, tool), "status": step.get("status"),
                     "arguments": step.get("arguments") or {}, "attempt": step.get("attempt"),
                     "reflection": step.get("reflection")})
    tokens = int(payload.get("promptTokens") or 0) + int(payload.get("completionTokens") or 0)
    if payload.get("tokenUsage") is not None:
        tokens = int(payload.get("tokenUsage") or 0)
    return {
        "intent": payload.get("intent") or "",
        "chain": run_tool_eval.parse_trace(payload),
        "slot_asked": bool(payload.get("slotAsked")),
        "tool_used": bool(payload.get("toolUsed")),
        "answer": payload.get("answer") or "",
        "citations": payload.get("citations") or [],
        "citation_details": payload.get("citationDetails") or [],
        "fallback": payload.get("fallbackReason"),
        "ticket_id": payload.get("ticketId") or "",
        "plan": plan,
        "context_history": int(context.get("historyTurns") or 0),
        "tokens": tokens,
        "http_status": http_status,
        "body": body or json.dumps(payload, ensure_ascii=False),
        "error_code": payload.get("code") or payload.get("errorCode") or "",
        "json": payload,
    }


def observation_from_response(status: int, payload: dict | None, body: str) -> dict:
    """REST 步（非聊天）的观测：没有答案/计划，只有状态码、响应体和错误码。"""
    payload = payload or {}
    return {
        "intent": "", "chain": [], "slot_asked": False, "tool_used": False, "answer": "",
        "citations": [], "citation_details": [], "fallback": None, "ticket_id": "",
        "plan": [], "context_history": 0, "tokens": 0, "http_status": status,
        "body": body, "error_code": payload.get("code") or payload.get("errorCode") or "",
        "json": payload,
    }


def security_steps(case: dict) -> list[dict]:
    steps = case.get("steps")
    return steps if isinstance(steps, list) else []


def security_step_case(case: dict, step: dict) -> dict:
    """把一条安全步骤压成 score() 认得的单步用例。id 带步骤名，失败能定位到行。"""
    return {"id": f"{case.get('id')}/{step.get('name')}", "expect": step.get("expect") or {}}


def selfcheck_security(case: dict) -> list[str]:
    """每个步骤都要正对照绿、每个注入缺陷都红；空步骤单独报。"""
    if not security_steps(case):
        return [f"{case.get('id', '<无 id>')}: 没有 steps"]
    problems = []
    for step in security_steps(case):
        problems += selfcheck(security_step_case(case, step))
    return problems


def main() -> int:
    """三份新用例集的合并自检：正对照 + 变异，全部 0 token、无网关。"""
    problems = []
    mutants = 0
    for name in ("regression", "edge"):
        for case in load_cases(name):
            problems += selfcheck(case)
            mutants += len(mutations(case))
    for case in load_cases("security"):
        for step in security_steps(case):
            problems += selfcheck(security_step_case(case, step))
            mutants += len(mutations(security_step_case(case, step)))
    if problems:
        print("自检失败：")
        for problem in problems:
            print("  - " + problem)
        return 1
    print(f"自检 OK：{len(load_cases('regression'))} + {len(load_cases('edge'))} + "
          f"{len(load_cases('security'))} 条；{mutants} 个变异全部红")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
