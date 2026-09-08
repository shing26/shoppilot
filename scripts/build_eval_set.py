"""合并并校验 ticket 16 的标注集（eval/tool-cases.jsonl）。

三条校验不是装饰：
  1. 期望标注必须自洽（工具名合法、缺槽位样本不得同时要求填出该槽位）；
  2. 规模与配比达标（约 180 条、10 意图各 15-20、对抗样本 >= 30%）；
  3. 评测 query 不得与 T1 质心的种子样本重合，否则"准确率"里掺了背题。

用法: python scripts/build_eval_set.py
"""

import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PARTS = (
    REPO / "eval" / "cases-part1-policy.jsonl",
    REPO / "eval" / "cases-part2-action.jsonl",
    REPO / "eval" / "cases-part3-edge.jsonl",
)
OUT = REPO / "eval" / "tool-cases.jsonl"
SEEDS = REPO / "shoppilot-gateway" / "src" / "main" / "resources" / "intent-samples.json"

VALID_TOOLS = {None, "queryOrderDetail", "queryLogistics", "modifyDeliveryAddress", "applyRefund"}
ADVERSARIAL = {"missing_slot", "multi_intent", "order_not_found", "cross_tenant", "colloquial"}
MIN_PER_INTENT, MAX_PER_INTENT = 15, 20
MIN_TOTAL, MIN_ADVERSARIAL_RATIO = 180, 0.30


def main() -> int:
    cases = []
    for part in PARTS:
        for lineno, raw in enumerate(part.read_text(encoding="utf-8").splitlines(), 1):
            if not raw.strip():
                continue
            try:
                case = json.loads(raw)
            except json.JSONDecodeError as bad:
                print(f"FAIL  {part.name}:{lineno} 不是合法 JSON: {bad}")
                return 1
            case["_source"] = f"{part.name}:{lineno}"
            cases.append(case)

    failures = []
    ids = [c["id"] for c in cases]
    for duplicate in {i for i in ids if ids.count(i) > 1}:
        failures.append(f"用例 id 重复：{duplicate}")

    for case in cases:
        expect = case["expect"]
        tool = expect.get("tool")
        if tool not in VALID_TOOLS:
            failures.append(f"{case['id']} 期望工具名非法：{tool}")
        if tool is None and expect["args"]:
            failures.append(f"{case['id']} 不调工具却带期望参数")
        for banned in expect.get("mustNotContainArgs", []):
            if banned in expect["args"]:
                failures.append(
                    f"{case['id']} 既要求缺槽位（{banned} 不许猜）又把它写进期望参数，自相矛盾")
        if case["intent"].startswith("POLICY_") and tool is not None:
            failures.append(f"{case['id']} 政策意图却期望调用工具")
        if case["intent"].startswith("ACTION_") and tool is None:
            failures.append(f"{case['id']} 动作意图却期望不调工具")

    by_intent = {}
    for case in cases:
        by_intent.setdefault(case["intent"], []).append(case)
    for intent, items in sorted(by_intent.items()):
        if not MIN_PER_INTENT <= len(items) <= MAX_PER_INTENT:
            failures.append(f"意图 {intent} 样本数 {len(items)} 不在 {MIN_PER_INTENT}-{MAX_PER_INTENT}")
    if len(cases) < MIN_TOTAL:
        failures.append(f"总样本 {len(cases)} < {MIN_TOTAL}")
    adversarial = [c for c in cases if c["kind"] in ADVERSARIAL]
    ratio = len(adversarial) / len(cases)
    if ratio < MIN_ADVERSARIAL_RATIO:
        failures.append(f"对抗样本占比 {ratio:.1%} < {MIN_ADVERSARIAL_RATIO:.0%}")

    seed_queries = {s for group in json.loads(SEEDS.read_text(encoding="utf-8")).values() for s in group}
    leaked = [c["id"] for c in cases if c["query"] in seed_queries]
    if leaked:
        failures.append(f"评测 query 与 T1 质心种子重合（背题）：{leaked}")

    warnings = []
    seen = {}
    for case in cases:
        who = (case.get("tenant", "T001"), case.get("customer", "C001"))
        seen.setdefault((case["query"], who), []).append(case["id"])
    for (query, who), owners in seen.items():
        if len(owners) > 1:
            warnings.append(f"同一身份下 query 重复：{query} {who} -> {owners}")

    OUT.write_text("".join(
        json.dumps({k: v for k, v in case.items() if k != "_source"}, ensure_ascii=False) + "\n"
        for case in cases), encoding="utf-8")

    print(f"合并 {len(cases)} 条 -> {OUT.relative_to(REPO)}")
    for intent, items in sorted(by_intent.items()):
        kinds = {}
        for item in items:
            kinds[item["kind"]] = kinds.get(item["kind"], 0) + 1
        print(f"  {intent:16} {len(items):3} 条  " + " ".join(f"{k}={v}" for k, v in sorted(kinds.items())))
    print(f"  对抗样本 {len(adversarial)}/{len(cases)} = {ratio:.1%}")
    for warning in warnings:
        print("WARN  " + warning)
    for failure in failures:
        print("FAIL  " + failure)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
