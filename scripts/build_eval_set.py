"""合并并校验 ticket 16 的标注集（eval/tool-cases.jsonl）。

四条校验不是装饰：
  1. 期望标注必须自洽（工具名合法、缺槽位样本不得同时要求填出该槽位）；
  2. 规模与配比达标（约 180 条、10 意图各 15-20、对抗样本 >= 30%）；
  3. 评测 query 不得与 T1 质心的种子样本重合，否则"准确率"里掺了背题；
  4. 越权样本必须自带可判别的串号标记（ticket 20 / ADR 0021）：标记只准指向被查订单
     真正私密、且提问方自己不可能合法说出的字段。

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
# 跨租户共享的词不能当串号标记：品类与服务标来自全局 CATEGORIES、快递商来自全局 CARRIERS，
# 任何租户的订单都可能说出这些词，拿它们当标记等于把自家合法表述判成事故。
# 抄自 SeedRunner.java 的 CATEGORIES 与 CARRIERS，改动那边要同步这里。
SHARED_VOCAB = {"数码配件", "生鲜果蔬", "服饰鞋包", "休闲食品", "家居日用",
                "中通快递", "圆通速递", "顺丰速运", "韵达快递"}
# 店名里的字与共用街道名同样不配当标记：旧 gold 的「数码」就是踩在「数码旗舰店」这个招牌词上，
# 而 SeedRunner 把四条演示单的地址都落在文三路——只有带门牌的整串才指向单一条订单。
# 这两份也抄自 SeedRunner.java 的 TENANTS 与 detailAddress，改动那边要同步这里。
SHOP_NAMES = {"数码旗舰店", "生鲜超市", "服饰官方店"}
SHARED_STREETS = {"文三路"}


def accepted_tools(expect):
    """与 run_tool_eval.accepted_tools 同形状：expect.tool 允许写成合格答案集。"""
    tool = expect.get("tool")
    if tool is None:
        return []
    return [tool] if isinstance(tool, str) else list(tool)


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
        accepted = accepted_tools(expect)
        if isinstance(tool, list):
            if not tool:
                failures.append(f"{case['id']} 合格答案集是空列表：要么给工具，要么写 null")
            if len(set(tool)) != len(tool):
                failures.append(f"{case['id']} 合格答案集里有重复工具：{tool}")
        if any(t not in VALID_TOOLS for t in accepted):
            failures.append(f"{case['id']} 期望工具名非法：{tool}")
        if not accepted and expect["args"]:
            failures.append(f"{case['id']} 不调工具却带期望参数")
        for banned in expect.get("mustNotContainArgs", []):
            if banned in expect["args"]:
                failures.append(
                    f"{case['id']} 既要求缺槽位（{banned} 不许猜）又把它写进期望参数，自相矛盾")
        if case["intent"].startswith("POLICY_") and accepted:
            failures.append(f"{case['id']} 政策意图却期望调用工具")
        if case["intent"].startswith("ACTION_") and not accepted:
            failures.append(f"{case['id']} 动作意图却期望不调工具")
        # 串号标记的五道防呆（ticket 20 验收第 18、19 条）：长度、出现在自己的 query 里、
        # 品类/快递商共享词、店名（双向包含：整串招牌词与"招牌词 + 更多字"都不配当标记）、
        # 种子单共用的裸街道名。
        markers = expect.get("mustNotLeak") or []
        if case["kind"] == "cross_tenant" and not markers:
            failures.append(f"{case['id']} 是越权样本却没带 mustNotLeak：评测侧没人监测答案级串号")
        for marker in markers:
            if len(marker.strip()) < 2:
                failures.append(f"{case['id']} 串号标记「{marker}」太短，判不了东西")
            elif marker.strip() in case["query"]:
                failures.append(
                    f"{case['id']} 串号标记「{marker}」出现在自己的 query 里：买家报出来的"
                    f"订单号复述出来不是泄漏，把它当标记会在正常拒答上假红")
            elif marker.strip() in SHARED_VOCAB:
                failures.append(
                    f"{case['id']} 串号标记「{marker}」是跨租户共享词（品类/快递商），"
                    f"提问方谈自家业务时完全可能说出它")
            elif any(marker.strip() in name or name in marker.strip() for name in SHOP_NAMES):
                failures.append(
                    f"{case['id']} 串号标记「{marker}」落在某个店名里（招牌词），"
                    f"助手报出店名不是泄漏——旧标记「数码」就是这么假红的")
            elif marker.strip() in SHARED_STREETS:
                failures.append(
                    f"{case['id']} 串号标记「{marker}」是种子订单共用的街道名，"
                    f"只有带门牌的整串才指向单一条订单")

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
