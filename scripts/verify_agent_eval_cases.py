"""round50 评测集扩展：回归 / 边界两份用例集的结构校验（0 token、无网关）。

守的是**用例集本身**，不是模型效果：
  ① 规模：回归 ≥36 条且成对（原始问句 + ≥1 条口语变体），边界 ≥45 条；
  ② 字段、id、问句唯一；问句不得与既有 gold 重复（重复的只能放 dev）；
  ③ 回归每条必须有来源（issue/ADR/EVIDENCE）、缺陷描述，成对两条 expect 完全一致；
  ④ 边界七类各自有下限；多轮用例的 turns 结构合法；
  ⑤ doc / section 必须在 `knowledge/` 真实存在，required / forbidden 不自相矛盾；
  ⑥ holdout ≥35%，且原始问句（来自历史故障的句子）不得进 holdout；
  ⑦ 任何问句不得回流 `knowledge/` 语料或提示词；
  ⑧ 每条用例正对照必绿、注入缺陷必红（`agent_eval_cases.selfcheck`）；
  ⑨ 既有判据文件（tool-cases / answer-cases / retrieval / handoff-golden）字节级未改。

它不跑网关、不判模型好坏。活体跑分是 `scripts/run_agent_eval.py` 的事。
"""

import re
import sys
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCRIPTS = REPO / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import agent_eval_cases as shared  # noqa: E402

REGRESSION_KEYS = {"id", "pair", "variant", "source", "defect", "kind", "query", "tenant",
                   "customer", "expect", "split"}
EDGE_KEYS = {"id", "category", "query", "tenant", "customer", "expect", "split", "turns", "note"}
KINDS = {"intent", "tool", "answer"}
# 来源只要求可追溯：issue / ticket 号、ADR 号、EVIDENCE 行、fix 提交、round、知识文档名。
SOURCE = re.compile(r"(issue:\d+|ticket:\d+|ADR\s*\d+|docs/EVIDENCE\.md|"
                    r"fix\(round\d+\):[0-9a-f]{7,}|round\d+|fresh-\d+)", re.I)
EDGE_MIN = {"multi_intent": 8, "fresh_pin": 10, "slot_continue": 6, "typo": 6,
            "unknown": 5, "empty_recall": 3, "numeric_homograph": 5}
TENANTS = {"T001", "T002", "T003", "T004"}
CUSTOMERS = {"C001", "C002", "C003"}


def main() -> int:
    errors: list[str] = []

    for relative, wanted in shared.EXISTING_JUDGE_FILES.items():
        path = REPO / relative
        if not path.exists():
            errors.append(f"{relative} 不存在")
        elif shared.sha256(path) != wanted:
            errors.append(f"{relative} 被改动（哈希不符）；既有判据文件只读")

    regression = shared.load_cases("regression")
    edge = shared.load_cases("edge")
    if not regression:
        errors.append("eval/regression-cases.jsonl 为空")
    if not edge:
        errors.append("eval/edge-cases.jsonl 为空")

    existing = shared.existing_queries()
    docs = shared.knowledge_docs()
    prompts = shared.prompt_texts()

    # ① 规模
    if len(regression) < 36:
        errors.append(f"回归用例 {len(regression)} 条，少于 36")
    if len(edge) < 45:
        errors.append(f"边界用例 {len(edge)} 条，少于 45")

    # ② 唯一性（跨两份新集合）
    ids = Counter(case.get("id") for case in regression + edge)
    for value, count in ids.items():
        if not value:
            errors.append("存在没有 id 的用例")
        elif count > 1:
            errors.append(f"id 重复 {value} ×{count}")
    queries = Counter(shared.normalize(case.get("query", "")) for case in regression + edge)
    for value, count in queries.items():
        if value and count > 1:
            errors.append(f"问句重复（归一后）{value[:24]} ×{count}")

    # ③ 回归成对
    pairs: dict[str, list[dict]] = {}
    for case in regression:
        label = case.get("id", "<无 id>")
        if set(case) != REGRESSION_KEYS:
            errors.append(f"{label} 字段集合不对：多 {sorted(set(case) - REGRESSION_KEYS)} 缺 "
                          f"{sorted(REGRESSION_KEYS - set(case))}")
        pair = case.get("pair") or ""
        pairs.setdefault(pair, []).append(case)
        if not pair:
            errors.append(f"{label} 缺 pair")
        if case.get("variant") not in ("original", "colloquial"):
            errors.append(f"{label} 的 variant 必须是 original / colloquial")
        if case.get("kind") not in KINDS:
            errors.append(f"{label} 的 kind={case.get('kind')} 不在 {sorted(KINDS)}")
        if not SOURCE.search(str(case.get("source") or "")):
            errors.append(f"{label} 的 source={case.get('source')!r} 不是可追溯出处")
        if not str(case.get("defect") or "").strip():
            errors.append(f"{label} 缺 defect 描述")
        _common_case_checks(case, label, errors, existing, docs, prompts)

    for pair, group in pairs.items():
        if not pair:
            continue
        variants = Counter(case.get("variant") for case in group)
        if len(group) < 2:
            errors.append(f"pair {pair} 只有 {len(group)} 条（需要原始 + ≥1 条口语）")
        if variants["original"] != 1:
            errors.append(f"pair {pair} 的 original 必须有且只有 1 条，实为 {variants['original']}")
        if variants["colloquial"] < 1:
            errors.append(f"pair {pair} 缺口语变体")
        anchors = {(case.get("tenant"), case.get("customer"), case.get("kind")) for case in group}
        if len(anchors) > 1:
            errors.append(f"pair {pair} 的 tenant/customer/kind 不一致：{sorted(anchors)}")
        expects = {shared.normalize(str(case.get("expect"))) for case in group}
        if len(expects) > 1:
            errors.append(f"pair {pair} 各条 expect 不一致")

    # ④ 边界分类与多轮
    for case in edge:
        label = case.get("id", "<无 id>")
        if not set(case) <= EDGE_KEYS:
            errors.append(f"{label} 有未知字段 {sorted(set(case) - EDGE_KEYS)}")
        if case.get("category") not in shared.EDGE_CATEGORIES:
            errors.append(f"{label} 的 category={case.get('category')} 不在 {sorted(shared.EDGE_CATEGORIES)}")
        turns = case.get("turns")
        if turns is not None:
            if not isinstance(turns, list) or not turns:
                errors.append(f"{label} 的 turns 必须是非空数组")
            else:
                if len(turns) > 3:
                    errors.append(f"{label} 的 turns 超过 3 轮")
                for index, turn in enumerate(turns):
                    if not isinstance(turn, dict) or not str(turn.get("query") or "").strip():
                        errors.append(f"{label} 的 turns[{index}] 缺 query")
                if shared.normalize(case.get("query", "")) in {
                        shared.normalize(turn.get("query", "")) for turn in turns if isinstance(turn, dict)}:
                    errors.append(f"{label} 的末轮 query 与前置 turns 重复")
        if case.get("category") == "slot_continue" and not turns:
            errors.append(f"{label} 是 slot_continue，必须带前置 turns")
        _common_case_checks(case, label, errors, existing, docs, prompts)

    per_category = Counter(case.get("category") for case in edge)
    for category, minimum in EDGE_MIN.items():
        if per_category[category] < minimum:
            errors.append(f"边界分类 {category} 只有 {per_category[category]} 条，少于 {minimum}")

    # ⑥ holdout 比例与原始问句纪律
    reg_holdout = sum(1 for case in regression if case.get("split") == "holdout")
    edge_holdout = sum(1 for case in edge if case.get("split") == "holdout")
    if regression:
        ratio = reg_holdout / len(regression)
        if ratio < 0.35:
            errors.append(f"回归 holdout 占比 {ratio:.1%} 低于 35%")
    if edge:
        ratio = edge_holdout / len(edge)
        if ratio < 0.35:
            errors.append(f"边界 holdout 占比 {ratio:.1%} 低于 35%")
    for case in regression:
        if case.get("variant") == "original" and case.get("split") == "holdout":
            errors.append(f"{case.get('id')} 是历史故障原句，只能放 dev")

    # ⑧ 变异自检
    for case in regression + edge:
        for problem in shared.selfcheck(case):
            errors.append(f"自检：{problem}")

    if errors:
        print("评测用例集校验失败：")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"评测用例集 OK：回归 {len(regression)} 条 / {len(pairs)} 对，"
          f"边界 {len(edge)} 条 / {len(per_category)} 类")
    print(f"  split: 回归 holdout {reg_holdout}（{reg_holdout / len(regression):.1%}），"
          f"边界 holdout {edge_holdout}（{edge_holdout / len(edge):.1%}）")
    print(f"  kind: {dict(Counter(case.get('kind') for case in regression))}")
    print(f"  边界: {dict(per_category)}")
    print(f"  变异自检: {len(regression) + len(edge)} 条全部正对照绿、注入缺陷红")
    return 0


def _common_case_checks(case, label, errors, existing, docs, prompts):
    query = case.get("query", "")
    if not str(query).strip():
        errors.append(f"{label} 缺 query")
    if case.get("tenant") not in TENANTS:
        errors.append(f"{label} 的 tenant={case.get('tenant')} 不是已注册租户")
    if case.get("customer") not in CUSTOMERS:
        errors.append(f"{label} 的 customer={case.get('customer')} 不是已登记买家")
    if case.get("split") not in ("dev", "holdout"):
        errors.append(f"{label} 的 split 必须是 dev / holdout")
    normalized = shared.normalize(query)
    if normalized in existing and case.get("split") != "dev":
        errors.append(f"{label} 的问句已在 {existing[normalized]} 里出现，只能放 dev")
    for hit in shared.leak_into_corpus(query, docs, prompts):
        errors.append(f"{label} 的问句回流到了 {hit}")
    expect = case.get("expect") or {}
    if not isinstance(expect, dict) or not expect:
        errors.append(f"{label} 缺 expect")
        return
    if expect.get("intent") and expect["intent"] not in shared.INTENTS:
        errors.append(f"{label} 的 intent={expect['intent']} 不在网关意图里")
    for tool in shared.expected_tools(expect) + (expect.get("plan_tools") or []):
        if tool not in shared.TOOLS:
            errors.append(f"{label} 用了未知工具 {tool}")
    for tool in expect.get("notTool") or []:
        if tool not in shared.TOOLS:
            errors.append(f"{label} 的 notTool 含未知工具 {tool}")
    doc = expect.get("doc")
    if doc:
        if doc not in docs:
            errors.append(f"{label} 的 doc={doc} 不在 knowledge/ 里")
        elif expect.get("section") and expect["section"] not in docs[doc]["headings"]:
            errors.append(f"{label} 的 section={expect['section']!r} 不在 {doc} 的小节里")


if __name__ == "__main__":
    sys.exit(main())
