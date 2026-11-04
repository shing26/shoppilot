"""round48 票 206 / ADR 0083：检索质量用例集的结构校验（0 token、无网关）。

守的是**用例集本身**，不是检索效果：
  ① 规模 100–200 条，id / query 唯一（query 按去标点去空白归一后比）；
  ② 每条用例的 `expect_doc` 必须是 `knowledge/` 里真实存在的文件；
  ③ intent 与文档前缀的主题一致（fresh/hotel/promo/return/shipping），且是网关的五个政策意图之一；
  ④ 租户自洽：店铺级文档（scope: SHOP）只能用归属店发起，平台级文档用任何已注册租户；
  ⑤ 覆盖：每份文档至少 2 条、其中至少 1 条在 dev；holdout 占比 25%–35%；
  ⑥ 查询不能原样抄文档标题或小节标题（否则测的是"复制粘贴"不是召回）。

它**不**证明检索质量，也**不**读任何一路检索结果——那是 `scripts/retrieval_quality.py`
在录完夹具之后的事。改了用例集必须重录夹具，否则两份对不上。
"""

import json
import re
import sys
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CASES = REPO / "eval" / "retrieval-cases.json"
KNOWLEDGE = REPO / "knowledge"

INTENTS = {"POLICY_FRESH", "POLICY_HOTEL", "POLICY_PROMO", "POLICY_RETURN", "POLICY_SHIPPING"}
# 文档文件名前缀 → 主题意图。站内只有这五类政策文档，前缀与意图是一一对应的。
PREFIX_INTENT = {
    "fresh": "POLICY_FRESH",
    "hotel": "POLICY_HOTEL",
    "promo": "POLICY_PROMO",
    "return": "POLICY_RETURN",
    "shipping": "POLICY_SHIPPING",
}
TENANTS = {"T001", "T002", "T004"}


def fail(errors, message):
    errors.append(message)


def frontmatter(path):
    text = path.read_text(encoding="utf-8")
    found = re.match(r"^---\n(.*?)\n---\n", text, re.S)
    if not found:
        return {}, text
    fields = {}
    for line in found.group(1).splitlines():
        key, sep, value = line.partition(":")
        if sep:
            fields[key.strip()] = value.strip()
    return fields, text[found.end():]


def normalize(query):
    return re.sub(r"[\s\W_]+", "", query, flags=re.UNICODE).lower()


def headings(body):
    return {normalize(line.lstrip("#").strip()) for line in body.splitlines() if line.startswith("#")}


def main() -> int:
    errors = []
    if not CASES.exists():
        print(f"缺少 {CASES.relative_to(REPO)}")
        return 1
    data = json.loads(CASES.read_text(encoding="utf-8"))
    cases = data.get("cases") or []
    if not isinstance(cases, list):
        print("cases 必须是数组")
        return 1

    # ① 规模与唯一性
    if not 100 <= len(cases) <= 200:
        fail(errors, f"用例数 {len(cases)} 不在 100–200 之间")
    ids, queries = Counter(), Counter()
    for case in cases:
        for field in ("id", "query", "intent", "expect_doc", "tenant", "split"):
            if not case.get(field):
                fail(errors, f"{case.get('id', '<无 id>')} 缺字段 {field}")
        ids[case.get("id")] += 1
        queries[normalize(case.get("query", ""))] += 1
        if case.get("split") not in ("dev", "holdout"):
            fail(errors, f"{case.get('id')} 的 split 必须是 dev / holdout")
    for value, count in ids.items():
        if count > 1:
            fail(errors, f"id 重复 {value} ×{count}")
    for value, count in queries.items():
        if value and count > 1:
            fail(errors, f"query 重复（归一后）{value} ×{count}")

    # ②–④ 每条用例与语料的一致性
    docs = {}
    for path in sorted(KNOWLEDGE.glob("*.md")):
        fields, body = frontmatter(path)
        docs[path.stem] = {"fields": fields, "headings": headings(body),
                           "title": normalize(fields.get("title", ""))}
    if not docs:
        print("knowledge/ 里没有文档")
        return 1

    for case in cases:
        case_id = case.get("id", "?")
        doc = case.get("expect_doc")
        if doc not in docs:
            fail(errors, f"{case_id} 的 expect_doc={doc} 不在 knowledge/ 里")
            continue
        prefix = doc.split("-", 1)[0]
        expected_intent = PREFIX_INTENT.get(prefix)
        if expected_intent is None:
            fail(errors, f"{case_id} 的 expect_doc={doc} 前缀无法归类")
        elif case.get("intent") != expected_intent:
            fail(errors, f"{case_id} 的 intent={case.get('intent')} 与文档主题 {expected_intent} 不一致")
        if case.get("intent") not in INTENTS:
            fail(errors, f"{case_id} 的 intent={case.get('intent')} 不是政策意图之一")
        fields = docs[doc]["fields"]
        if fields.get("scope") == "SHOP":
            if case.get("tenant") != fields.get("tenant_id"):
                fail(errors, f"{case_id} 是店铺级文档，tenant 必须是 {fields.get('tenant_id')}")
        elif fields.get("scope") == "PLATFORM":
            if case.get("tenant") not in TENANTS:
                fail(errors, f"{case_id} 的 tenant={case.get('tenant')} 不是已注册租户")
        else:
            fail(errors, f"{doc} 的 scope={fields.get('scope')!r} 无法判定可见性")
        # ⑥ 查询不能是标题/小节的复制
        normalized = normalize(case.get("query", ""))
        if normalized and (normalized == docs[doc]["title"] or normalized in docs[doc]["headings"]):
            fail(errors, f"{case_id} 的 query 原样抄了文档标题/小节标题")

    # ⑤ 覆盖与 split 比例
    per_doc = Counter(case.get("expect_doc") for case in cases)
    dev_docs = Counter(case.get("expect_doc") for case in cases if case.get("split") == "dev")
    for doc in docs:
        if per_doc[doc] < 2:
            fail(errors, f"{doc} 只有 {per_doc[doc]} 条用例（至少 2 条）")
        if dev_docs[doc] < 1:
            fail(errors, f"{doc} 在 dev split 里没有用例（无法用它定阈值）")
    uncovered = sorted(set(docs) - set(per_doc))
    if uncovered:
        fail(errors, f"这些文档没有用例：{uncovered}")
    holdout = sum(1 for case in cases if case.get("split") == "holdout")
    ratio = holdout / len(cases) if cases else 0
    if not 0.25 <= ratio <= 0.35:
        fail(errors, f"holdout 占比 {ratio:.1%} 不在 25%–35% 之间")

    if errors:
        print("检索用例集校验失败：")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"检索用例集 OK：{len(cases)} 条 / {len(docs)} 份文档全覆盖 / holdout {holdout} 条（{ratio:.1%}）")
    print(f"  split: {dict(Counter(case.get('split') for case in cases))}")
    print(f"  intent: {dict(Counter(case.get('intent') for case in cases))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
