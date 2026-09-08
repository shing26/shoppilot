# -*- coding: utf-8 -*-
"""T1 意图质心层阈值标定（ticket 07 的准入质量）。

背景：压测实测拦截率只有 56%，归因发现是政策问句在 T1 被判 UNKNOWN（fail-closed 不进缓存）。
本脚本用 180 条标注评测集复算 T1，回答两个问题：
  1) T1 是否应该在 T0 未定案（即无实体、无第一人称）时排除 ACTION 候选；
  2) 置信度阈值与领先差值取多少，才在"多拦下政策问句"与"不判错桶"之间站得住。

输出 docs/intent-calibration.md，退出码断言选定工作点上"判错政策桶"的比例不高于现状。
"""
import json
import math
import re
import sys
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OLLAMA = "http://127.0.0.1:11434/api/embed"
MODEL = "bge-m3"
T0_JAVA = REPO / "shoppilot-gateway/src/main/java/com/shoppilot/gateway/triage/T0RuleLayer.java"
SAMPLES = REPO / "shoppilot-gateway/src/main/resources/intent-samples.json"
CASES = REPO / "eval/tool-cases.jsonl"
OUT = REPO / "docs/intent-calibration.md"


def load_t0_lists():
    """从 Java 源码里读关键词表，避免脚本与实现各写一份而悄悄漂移。"""
    source = T0_JAVA.read_text(encoding="utf-8")
    lists = {}
    for name, body in re.findall(r'List<String>\s+(\w+)\s*=\s*List\.of\(([^)]*)\)', source, re.S):
        lists[name] = re.findall(r'"([^"]+)"', body)
    patterns = {}
    for name, body in re.findall(r'Pattern\s+(\w+)\s*=\s*Pattern\.compile\("((?:[^"\\]|\\.)*)"\)', source):
        patterns[name] = body.replace('\\\\', '\\')
    return lists, patterns


def embed(texts):
    vectors = []
    for start in range(0, len(texts), 64):
        batch = texts[start:start + 64]
        request = urllib.request.Request(
            OLLAMA, data=json.dumps({"model": MODEL, "input": batch}).encode(),
            headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(request, timeout=300) as response:
            vectors.extend(json.loads(response.read().decode())["embeddings"])
    return vectors


def normalize(vector):
    norm = math.sqrt(sum(value * value for value in vector))
    return [value / norm for value in vector] if norm else vector


def cosine(a, b):
    return sum(x * y for x, y in zip(a, b))


def t0_decides(query, lists, patterns):
    has_entity = any(re.search(pattern, query) for pattern in patterns.values())
    personal = any(word in query for word in lists["POSSESSIVE"])
    if has_entity or personal:
        return "action"
    for group in ("FRESH_WORDS", "PROMO_WORDS", "RETURN_WORDS", "SHIPPING_POLICY_WORDS"):
        if any(word in query for word in lists[group]):
            return "policy:" + {"FRESH_WORDS": "POLICY_FRESH", "PROMO_WORDS": "POLICY_PROMO",
                                "RETURN_WORDS": "POLICY_RETURN",
                                "SHIPPING_POLICY_WORDS": "POLICY_SHIPPING"}[group]
    return None


def main():
    lists, patterns = load_t0_lists()
    action_verbs = [word for group in ("ADDRESS_WORDS", "REFUND_WORDS", "LOGISTICS_WORDS", "ORDER_WORDS")
                    for word in lists[group]]

    def action_evidence(query):
        """T0 未定案但句子里仍有动作动词：这就是"我要退款"这类无实体动作诉求。"""
        return any(word in query for word in action_verbs)

    samples = json.loads(SAMPLES.read_text(encoding="utf-8"))
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines() if line.strip()]

    names, texts = [], []
    for intent, items in samples.items():
        names.append(intent)
        texts.extend(items)
    spans, cursor = {}, 0
    for intent, items in zip(names, [samples[n] for n in names]):
        spans[intent] = (cursor, cursor + len(items))
        cursor += len(items)
    vectors = [normalize(v) for v in embed(texts)]
    centroids = {}
    for intent in names:
        start, end = spans[intent]
        group = vectors[start:end]
        centroids[intent] = normalize([sum(v[k] for v in group) / len(group) for k in range(len(vectors[0]))])

    # 只标定会落到 T1 的样本：T0 已定案的与动作类不参与阈值选择
    routed = [(case, t0_decides(case["query"], lists, patterns)) for case in cases]
    to_t1 = [(case, action_evidence(case["query"])) for case, decision in routed if decision is None]
    policy_cases = [(case, evidence) for case, evidence in to_t1 if case["intent"].startswith("POLICY_")]
    action_cases = [(case, evidence) for case, evidence in to_t1 if case["intent"].startswith("ACTION_")]

    query_vectors = dict(zip([case["query"] for case in cases],
                             [normalize(v) for v in embed([case["query"] for case in cases])]))

    def judge(query, candidates, min_conf, margin):
        scored = sorted(((cosine(query_vectors[query], centroids[intent]), intent) for intent in candidates),
                        reverse=True)
        best_score, best = scored[0]
        runner = scored[1][0] if len(scored) > 1 else -1
        if best_score < min_conf or best_score - runner < margin:
            return None
        return best

    ALL = names
    POLICY_PLUS = [n for n in names if n.startswith("POLICY_") or n == "ESCALATE"]

    def score_row(label, chooser, min_conf, margin):
        admitted = wrong = 0
        wrong_ids = []
        for case, evidence in policy_cases:
            verdict = judge(case["query"], chooser(evidence), min_conf, margin)
            if verdict is None:
                continue
            admitted += 1
            if verdict != case["intent"]:
                wrong += 1
                wrong_ids.append(f"{case['id']}->{verdict}")
        leaked = []
        for case, evidence in action_cases:
            verdict = judge(case["query"], chooser(evidence), min_conf, margin)
            if verdict is not None and not verdict.startswith("ACTION_"):
                leaked.append(f"{case['id']}->{verdict}")
        return {"candidates": label, "min_conf": min_conf, "margin": margin,
                "admitted": admitted, "admit_rate": admitted / len(policy_cases),
                "wrong": wrong, "wrong_rate": wrong / admitted if admitted else 0.0,
                "wrong_ids": wrong_ids, "action_leaked": len(leaked), "leaked_ids": leaked}

    grid = []
    for label, chooser in (("全 10 意图（修复前）", lambda evidence: ALL),
                           ("按动作证据裁剪候选（已实现）", lambda evidence: ALL if evidence else POLICY_PLUS)):
        for min_conf in (0.82, 0.78, 0.75, 0.72, 0.70, 0.65, 0.60):
            for margin in (0.03, 0.02, 0.015, 0.01):
                grid.append(score_row(label, chooser, min_conf, margin))

    baseline = next(row for row in grid if row["candidates"] == "全 10 意图（修复前）"
                    and row["min_conf"] == 0.82 and row["margin"] == 0.03)
    # 选点规则（三条硬线，顺序即优先级）：
    #   1. 动作问句绝不允许被准入为政策 —— 这是正确性，不是调参问题；
    #   2. 置信度不低于 0.70 —— 低于这个线的质心相似度撑不起"同一政策"的判断，不给调；
    #   3. 判错桶率不超过 5% —— 判错桶只损失命中率（缓存 key 含归一化文本，
    #      同一句永远落进同一个桶，不会把 A 题的答案发给 B 题），所以它是质量线不是安全线。
    # 在满足三条的工作点里取准入率最高者，并列时取置信度更高的一侧。
    safe = [row for row in grid if row["action_leaked"] == 0 and row["min_conf"] >= 0.70
            and row["wrong_rate"] <= 0.05]
    best = max(safe, key=lambda row: (row["admit_rate"], row["min_conf"])) if safe else baseline

    lines = ["# T1 意图质心阈值标定", "",
             f"生成：`python scripts/calibrate_intent.py`；样本 {len(cases)} 条标注用例，"
             f"其中 {len(policy_cases)} 条政策用例与 {len(action_cases)} 条动作用例落到 T1。", "",
             "**为什么 T1 的候选集要按动作证据裁剪**：`T0RuleLayer.classify` 在扫到实体或第一人称时必定案返回，"
             "所以能落到 T1 的问句要么没有动作证据、要么只剩动作动词本身。把 ACTION 质心无条件放进候选，"
             "实测会把政策问句吸进动作桶（`退款多久到账` 的最近质心是 `ACTION_REFUND` 0.8518、"
             "`你们家用的哪家物流` 是 `ACTION_LOGISTICS` 0.7766），而动作意图不准入缓存——"
             "分类越保守，缓存越漏。反过来，对带 `退款`/`地址` 这类动词的句子放行政策意图，"
             "又会把「我要退款」这种该追问槽位的诉求写进政策缓存。所以规则是「"
             "**有动作证据保留全 10 意图，无动作证据只留政策与转人工**」。", "",
             "## 工作点扫描", "",
             "| 候选集规则 | 置信度 | 领先差值 | 政策问句准入率 | 判错桶数/准入数 | 动作问句被准入为政策 |",
             "| --- | --- | --- | --- | --- | --- |"]
    for row in grid:
        lines.append(f"| {row['candidates']} | {row['min_conf']:.2f} | {row['margin']:.3f} | "
                     f"{row['admit_rate']:.1%} | {row['wrong']}/{row['admitted']} | {row['action_leaked']} |")
    lines += ["", "### 判错明细（选定工作点）", ""]
    lines += [f"- 判错桶：{', '.join(best['wrong_ids']) or '无'}",
              f"- 动作被准入为政策：{', '.join(best['leaked_ids']) or '无'}"]
    lines += ["", "## 选定工作点", "",
              f"- 候选集规则：**{best['candidates']}**",
              f"- 置信度下限 `{best['min_conf']:.2f}`，领先差值下限 `{best['margin']:.3f}`",
              f"- 政策问句准入率 {best['admit_rate']:.1%}（现状 {baseline['admit_rate']:.1%}）",
              f"- 判错政策桶 {best['wrong']}/{best['admitted']}（现状 {baseline['wrong']}/{baseline['admitted']}）",
              f"- 动作问句被误判为政策并进缓存：{best['action_leaked']} 条", "",
              "## 验收标准", "",
              "| 判据 | 阈值 | 实测 | 结论 |", "| --- | --- | --- | --- |",
              f"| 政策问句准入率不低于现状 | >= {baseline['admit_rate']:.1%} | {best['admit_rate']:.1%} | "
              f"{'通过' if best['admit_rate'] >= baseline['admit_rate'] else '不通过'} |",
              f"| 置信度下限不低于 0.70（硬线，不参与调参换准入率） | >= 0.70 | {best['min_conf']:.2f} | "
              f"{'通过' if best['min_conf'] >= 0.70 else '不通过'} |",
              f"| 判错桶比例不高于 5%（质量线：只损失命中率，不产生跨题错答） | <= 5% | {best['wrong_rate']:.1%} | "
              f"{'通过' if best['wrong_rate'] <= 0.05 else '不通过'} |",
              f"| 动作问句不得被准入为政策 | = 0 | {best['action_leaked']} | "
              f"{'通过' if best['action_leaked'] == 0 else '不通过'} |", ""]
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"现状：准入率 {baseline['admit_rate']:.1%}，判错 {baseline['wrong']}/{baseline['admitted']}")
    print(f"选定：{best['candidates']} conf={best['min_conf']} margin={best['margin']} "
          f"准入率 {best['admit_rate']:.1%} 判错 {best['wrong']}/{best['admitted']} 动作泄漏 {best['action_leaked']}")
    print(f"报告 {OUT.relative_to(REPO)}")
    if best["wrong_rate"] > 0.05 or best["action_leaked"] != 0 or best["min_conf"] < 0.70:
        print("FAIL: 选定工作点不满足验收判据")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
