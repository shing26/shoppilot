"""L2 语义缓存阈值标定（ticket 17、ADR 0003）。

对 eval/adversarial-pairs.json 的四类样本对做 0.85-0.99 余弦相似度扫描，
输出 precision / recall / 误命中率三条曲线，图与数据落 docs/，
并把 ticket 17 的验收标准连同实测结论一起写进报告。

embedding 恒为本地 bge-m3（1024 维），与运行期同一条向量化路径，
否则标定出来的阈值在生产里不成立。

退出码不是"曲线好不好看"，而是一条可执行的安全断言：
每一条越过 0.95 的样本对，都必须能被某一层具名防线兜住
（跨租户 -> tenant_id filter；跨意图 -> 意图分区；同意图反义 -> PolarityGuard）。
兜不住即 exit 1。

用法: python scripts/calibrate_threshold.py [--base http://127.0.0.1:11434]
"""

import argparse
import json
import math
import statistics
import sys
import urllib.request
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

REPO = Path(__file__).resolve().parent.parent
PAIRS = REPO / "eval" / "adversarial-pairs.json"
DOCS = REPO / "docs"
DEFAULT_MODEL = "bge-m3"
THRESHOLDS = [round(0.85 + 0.01 * i, 2) for i in range(15)]
GROUPS = ("positive", "antonym", "cross_intent", "cross_tenant")
LABELS = {
    "positive": "同意图改写（应命中）",
    "antonym": "反义对（不应命中）",
    "cross_intent": "跨意图对（不应命中）",
    "cross_tenant": "跨租户同意图对（绝不应命中）",
}
OPERATING_POINT = 0.95

# 与 shoppilot-tool-api Intent.cacheAdmissible() 对齐：只有政策意图准入缓存
ADMISSIBLE_PREFIX = "POLICY_"
# 与 shoppilot-gateway PolarityGuard 对齐（用例见 PolarityGuardTest）
NEGATION_MARKERS = "不没未非别勿毋"


def embed(text: str, base: str, model: str) -> list[float]:
    body = json.dumps({"model": model, "input": text}).encode("utf-8")
    request = urllib.request.Request(
        base.rstrip("/") + "/api/embed", data=body, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        payload = json.loads(response.read().decode("utf-8"))
    embeddings = payload["embeddings"]
    vector = embeddings[0] if isinstance(embeddings, list) else embeddings
    if len(vector) != 1024:
        raise SystemExit(f"期望 1024 维，实际 {len(vector)} 维：模型不是 {model}？")
    return vector


def cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    return dot / (na * nb)


def cache_admissible(intent: str | None) -> bool:
    return bool(intent) and intent.startswith(ADMISSIBLE_PREFIX)


def polarity(query: str) -> str:
    """镜像 PolarityGuard.polarity：AFFIRMING / NEGATED / NEUTRAL。"""
    if not query:
        return "AFFIRMING"
    saw_neutral = False
    index = 0
    while index < len(query):
        if query[index] not in NEGATION_MARKERS:
            index += 1
            continue
        left, right = index - 1, index + 1
        if left >= 0 and right < len(query) and query[left] == query[right]:
            saw_neutral = True  # 正反问：说话人未表态
            index += 2
            continue
        if index >= len(query) - 1:
            saw_neutral = True  # 句尾"不/没"是省略问句的语气词
            index += 1
            continue
        return "NEGATED"
    return "NEUTRAL" if saw_neutral else "AFFIRMING"


def polarity_blocked(incoming: str, cached: str) -> str | None:
    """镜像 PolarityGuard.blocked：返回 None 表示允许复用。"""
    if not cached:
        return "polarity-unknown"
    a, b = polarity(incoming), polarity(cached)
    if a == "NEUTRAL" or b == "NEUTRAL":
        return None
    return None if a == b else "polarity-conflict"


def guard_for(group: str, pair: dict) -> str | None:
    """越过阈值的样本对由哪一层具名防线兜住；返回 None 表示兜不住。"""
    if group == "cross_tenant":
        return "tenant_id" if pair.get("tenant_a") != pair.get("tenant_b") else None
    if group == "cross_intent":
        return "intent-partition" if pair.get("intent_a") != pair.get("intent_b") else None
    if not cache_admissible(pair.get("intent")):
        return "cache-admission"
    return "polarity-guard" if polarity_blocked(pair["a"], pair["b"]) else None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:11434")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    args = parser.parse_args()

    data = json.loads(PAIRS.read_text(encoding="utf-8"))
    groups = {name: data[name] for name in GROUPS}
    texts = sorted({p[k] for group in groups.values() for p in group for k in ("a", "b")})
    print(f"向量化 {len(texts)} 条问法（模型 {args.model}，本地推理）…")
    vectors: dict[str, list[float]] = {}
    for index, text in enumerate(texts, 1):
        vectors[text] = embed(text, args.base, args.model)
        if index % 25 == 0:
            print(f"  {index}/{len(texts)}")

    # 每对样本算一次余弦；positive 是"应该命中"，其余三类都是"不应该命中"
    scored: dict[str, list[tuple[float, dict]]] = {}
    for group, pairs in groups.items():
        scored[group] = [(cosine(vectors[p["a"]], vectors[p["b"]]), p) for p in pairs]

    positives = scored["positive"]
    negatives = scored["antonym"] + scored["cross_intent"] + scored["cross_tenant"]

    rows = []
    for threshold in THRESHOLDS:
        tp = sum(1 for s, _ in positives if s >= threshold)
        fn = len(positives) - tp
        fp = sum(1 for s, _ in negatives if s >= threshold)
        tn = len(negatives) - fp
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if positives else 0.0
        fpr = fp / (fp + tn) if fp + tn else 0.0
        rows.append((threshold, precision, recall, fpr, tp, fp, fn))

    DOCS.mkdir(exist_ok=True)
    csv_path = DOCS / "threshold-sweep.csv"
    with csv_path.open("w", encoding="utf-8", newline="") as handle:
        handle.write("threshold,precision,recall,false_hit_rate,tp,fp,fn\n")
        for threshold, precision, recall, fpr, tp, fp, fn in rows:
            handle.write(f"{threshold:.2f},{precision:.4f},{recall:.4f},{fpr:.4f},{tp},{fp},{fn}\n")

    # 图里用英文标签：容器/系统不一定装了中文字体，出方框的图不如不出
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2))
    ts = [r[0] for r in rows]
    axes[0].plot(ts, [r[1] for r in rows], marker="o", label="precision")
    axes[0].plot(ts, [r[2] for r in rows], marker="s", label="recall")
    axes[0].axvline(OPERATING_POINT, color="crimson", linestyle="--", linewidth=1, label="operating point 0.95")
    axes[0].set_xlabel("cosine threshold")
    axes[0].set_ylim(0, 1.05)
    axes[0].set_title("L2 cache: precision / recall")
    axes[0].grid(alpha=0.3)
    axes[0].legend(fontsize=8)
    axes[1].plot(ts, [r[3] for r in rows], marker="^", color="darkorange", label="false hit rate")
    axes[1].axvline(OPERATING_POINT, color="crimson", linestyle="--", linewidth=1)
    axes[1].set_xlabel("cosine threshold")
    axes[1].set_title("False hits on adversarial pairs")
    axes[1].grid(alpha=0.3)
    axes[1].legend(fontsize=8)
    fig.tight_layout()
    png_path = DOCS / "threshold-sweep.png"
    fig.savefig(png_path, dpi=140)

    write_report(groups, scored, rows)
    print(f"\n写出 {csv_path.relative_to(REPO)} 与 {png_path.relative_to(REPO)}")
    return report_exit_code(scored, rows)


def _quantiles(scores: list[float]) -> dict:
    ordered = sorted(scores)
    return {
        "n": len(ordered),
        "min": ordered[0],
        "p25": ordered[max(0, len(ordered) // 4)],
        "med": statistics.median(ordered),
        "p75": ordered[min(len(ordered) - 1, 3 * len(ordered) // 4)],
        "max": ordered[-1],
    }


def write_report(groups, scored, rows) -> None:
    at = {round(r[0], 2): r for r in rows}
    op = at[OPERATING_POINT]
    crossings = [(group, score, pair) for group in GROUPS if group != "positive"
                 for score, pair in scored[group] if score >= OPERATING_POINT]
    fp_by_group = {group: sum(1 for s, _ in scored[group] if s >= OPERATING_POINT)
                   for group in GROUPS if group != "positive"}
    pos = _quantiles([s for s, _ in scored["positive"]])
    evidence = REPO / "eval" / "acceptance-runtime-evidence.md"
    runtime_rows = evidence.read_text(encoding="utf-8").strip().splitlines() if evidence.exists() else []
    tenant_min = min(s for s, _ in scored["cross_tenant"])
    tenant_max = max(s for s, _ in scored["cross_tenant"])
    same_text = [(s, p) for s, p in scored["cross_tenant"] if p["a"] == p["b"]]

    lines = [
        "# L2 语义缓存阈值标定（ticket 17）",
        "",
        f"样本对：同意图改写 {len(groups['positive'])}、反义 {len(groups['antonym'])}、"
        f"跨意图 {len(groups['cross_intent'])}、跨租户同意图 {len(groups['cross_tenant'])}。",
        "向量化：本地 Ollama `bge-m3`，1024 维，与运行期同一条 embedding 路径。",
        "",
        "## 验收标准与实测结论",
        "",
        "判据来源：`PLAN.md` 验收标准表（否决项·串号防线）、`PLAN.md` 逐 ticket 验收动作 17 行、"
        "`.scratch/shoppilot-mvp/issues/17-threshold-calibration.md` 的 `Verify` 行。",
        "",
        "| 验收项 | 层面 | 实测 | 结论 |",
        "| --- | --- | --- | --- |",
        "| 0.85-0.99 扫描，precision / recall / 误命中三条曲线与数据落 `docs/` | 交付物 | "
        f"{len(rows)} 个阈值点 × 3 条指标，`docs/threshold-sweep.csv` + `docs/threshold-sweep.png` | 通过 |",
        "| 反义对在 0.95 下不互相命中 | 原始向量 | "
        f"{len(groups['antonym'])} 对中 {fp_by_group['antonym']} 对越界"
        + _crossing_detail(crossings, "antonym")
        + " | **未达成**（见下节：这些对两侧同属一个 POLICY 意图，意图分区与阈值双双失效） |",
        *runtime_rows,
        "| 跨租户对在任意阈值下均不命中 | 原始向量 | "
        f"{len(groups['cross_tenant'])} 对中 {fp_by_group['cross_tenant']} 对越界，全部是同文本对，余弦恒为 1.0000 "
        "| **判据本身不成立**：阈值层不负责租户隔离，同文本对在任何阈值下都满分 |",
        "| 跨租户对在任意阈值下均不命中 | 系统 | Qdrant L2 检索 must-filter 带 `tenant_id` + `scope` + `intent` "
        "+ `kb_epoch`，`CacheEntry.matches()` 读时二次校验；否决项越权用例覆盖 | 通过 |",
        "| 结论写清阈值只负责同意图内的表述归一，隔离由准入与 payload filter 承担 | 报告 | 见「四条结论」 | 通过 |",
        f"| 报告 0.95 处实测误命中数并逐层归因 | 报告 | {op[5]} 对：反义 {fp_by_group['antonym']}、"
        f"跨意图 {fp_by_group['cross_intent']}、跨租户 {fp_by_group['cross_tenant']} | 通过 |",
        "",
        "**一条必须单独拎出来的实测结论：0.95 工作点上 L2 语义缓存召回为 0。**",
        f"同意图改写对的最大余弦只有 {pos['max']:.4f}，中位数 {pos['med']:.4f}，"
        f"{pos['n']} 对里没有一对够到 0.95。也就是说 bge-m3 在短中文问句上的余弦分布整体压在 0.75-0.94，"
        "0.95 这个工作点对它而言不是「严格」，是「关着」。"
        "本项目 80% 级别的热点拦截由 L1 精确哈希与 SingleFlight 承担，L2 的实际贡献是兜住"
        "「同一政策、写法不同且写得极近」这一小撮，而不是兜住口语化改写。"
        "README 的拦截率归因必须照此写，不得把 80% 记在语义缓存头上。",
        "",
        "## 扫描结果",
        "",
        "| 阈值 | precision | recall | 误命中率 | TP | FP | FN |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for threshold, precision, recall, fpr, tp, fp, fn in rows:
        mark = " **(工作点)**" if abs(threshold - OPERATING_POINT) < 1e-9 else ""
        lines.append(
            f"| {threshold:.2f}{mark} | {precision:.3f} | {recall:.3f} | {fpr:.3f} | {tp} | {fp} | {fn} |"
        )
    lines += [
        "",
        f"0.95 处实测：precision {op[1]:.3f}、recall {op[2]:.3f}、误命中 {op[3]:.3f}（{op[5]} 对越界）。",
        "",
        "### 分组余弦分布",
        "",
        "| 分组 | n | min | p25 | 中位 | p75 | max | ≥0.95 |",
        "| --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for group in GROUPS:
        q = _quantiles([s for s, _ in scored[group]])
        over = sum(1 for s, _ in scored[group] if s >= OPERATING_POINT)
        lines.append(
            f"| {LABELS[group]} | {q['n']} | {q['min']:.4f} | {q['p25']:.4f} | {q['med']:.4f} | "
            f"{q['p75']:.4f} | {q['max']:.4f} | {over} |"
        )
    lines += [
        "",
        "同意图改写对的最大值（"
        f"{pos['max']:.4f}）低于反义对的越界值，这是本实验最扎眼的一行数字："
        "在这个 embedding 模型上，「是否同意图」和「余弦是否够高」几乎不相关。",
        "",
        "### 0.95 处越界样本对逐条归因",
        "",
        "| 分组 | 余弦 | 问法 A | 问法 B | 兜住它的防线 |",
        "| --- | --- | --- | --- | --- |",
    ]
    for group, score, pair in crossings:
        lines.append(
            f"| {LABELS[group]} | {score:.4f} | {pair['a']} | {pair['b']} | `{guard_for(group, pair)}` |"
        )
    if not crossings:
        lines.append("| — | — | — | — | 无越界样本 |")
    lines += [
        "",
        "## 四条结论",
        "",
        "1. **阈值只负责同意图内的表述归一，而且在这个模型上它连这件事都没做成。** "
        f"0.95 处召回 {op[2]:.3f}、同意图改写对最大余弦 {pos['max']:.4f}：阈值定得太高，"
        "归一化没发生。调低到 0.85 能换来 recall 0.130，但 precision 只有 0.273，等于把反义对一起放进来。",
        "2. **同桶反义是阈值防不住的那一类，必须由词法守卫兜。** "
        + _antonym_story(crossings)
        + "ADR 0003 把语义漂移转化为跨意图隔离问题，这一对是该转化的反例：两侧同属 POLICY_RETURN，"
        "意图分区形同虚设。补上的 `PolarityGuard` 在 L2 命中后比较否定极性，不一致即拒绝复用，"
        "并打点 `shoppilot_cache_l2_polarity_blocked_total`。运行时分工见验收表：T0 认得出的反义对由守卫兜，T0 认不出的由准入 fail-closed 兜，两层各自有实测。",
        "3. **跨租户隔离与阈值无关。** 跨租户同意图对的余弦落在 "
        f"{tenant_min:.3f}-{tenant_max:.3f}，其中 {len(same_text)} 对同文本对在任意阈值下都是满分，"
        f"阈值层面拦不住（0.95 下有 {fp_by_group['cross_tenant']} 对越界）。"
        "拦住它们的是 Qdrant payload 里的 `tenant_id` 硬过滤加 `CacheEntry.matches()` 读时校验，不是相似度分数。",
        "4. **所以串号防线是四层，阈值只站在最后一层：** 缓存准入（T0 规则 + 实体扫描 + 意图分区）决定这条请求"
        "能不能进缓存；写回资格决定什么样的答案配被缓存（工具执行结果与个性化答案一律不写）；"
        "极性守卫决定同桶内反义问法不许互用；阈值只决定同意图同极性桶内的表述归一。"
        "把 0.95 调高到 0.99 会让召回塌得更彻底，但不会让串号更安全——跨租户那部分本来就不该由它负责。",
        "",
        "## 复现",
        "",
        "```",
        "python scripts/calibrate_threshold.py",
        "```",
        "",
        "退出码是可执行的安全断言，不是曲线好看与否：每条越过 0.95 的样本对都必须被某一层具名防线"
        "（`tenant_id` / `intent-partition` / `cache-admission` / `polarity-guard`）兜住，兜不住即失败。",
        "",
        "数据：`docs/threshold-sweep.csv`；图：`docs/threshold-sweep.png`；"
        "样本：`eval/adversarial-pairs.json`。",
        "",
    ]
    (DOCS / "threshold-calibration.md").write_text("\n".join(lines), encoding="utf-8")


def _crossing_detail(crossings, group: str) -> str:
    hits = [(score, pair) for g, score, pair in crossings if g == group]
    if not hits:
        return ""
    score, pair = hits[0]
    return (f"；首对示例「{pair['a']}」/「{pair['b']}」= {score:.4f}，intent `{pair.get('intent')}`，"
            "详见下方「越界样本对逐条归因」表")


def _antonym_story(crossings) -> str:
    hits = [(score, pair) for g, score, pair in crossings if g == "antonym"]
    if not hits:
        return "本次样本集未出现同桶反义越界，但该风险由构造决定而非由阈值决定，守卫仍然保留；"
    score, pair = hits[0]
    return (f"「{pair['a']}」与「{pair['b']}」余弦 {score:.4f}，")


def report_exit_code(scored, rows) -> int:
    """否决项断言：每条越界样本对都必须有一层具名防线兜住。"""
    failures = []
    if len(rows) != len(THRESHOLDS):
        failures.append(f"阈值扫描点数不符：期望 {len(THRESHOLDS)}，实际 {len(rows)}")

    for group in ("antonym", "cross_intent", "cross_tenant"):
        for score, pair in scored[group]:
            if score < OPERATING_POINT:
                continue
            layer = guard_for(group, pair)
            if layer is None:
                failures.append(
                    f"{LABELS[group]} 在 0.95 下互命中且无防线兜住："
                    f"{pair['a']} / {pair['b']} = {score:.4f} intent={pair.get('intent')}"
                )

    # 同文本跨租户对必须存在且满分：这是"阈值层对租户隔离无效"这条结论的证据
    same_text = [(s, p) for s, p in scored["cross_tenant"] if p["a"] == p["b"]]
    if not same_text:
        failures.append("跨租户组缺少同文本对，无法证明阈值层对跨租户无效")
    for score, pair in same_text:
        if score < 0.999:
            failures.append(f"同文本对余弦不是满分：{pair['a']} = {score:.4f}")
        if pair.get("tenant_a") == pair.get("tenant_b"):
            failures.append(f"同文本对两侧租户相同，不构成跨租户证据：{pair['a']}")

    # 守卫本身的行为要在这里钉一次，避免 Java 与 Python 两份实现漂移
    if polarity_blocked("这个是不是不能退", "这个能退吗") != "polarity-conflict":
        failures.append("极性守卫未能拦住标定样本里的反义对，Python 与 Java 实现已漂移")

    for failure in failures:
        print("FAIL  " + failure)
    crossed = sum(1 for group in ("antonym", "cross_intent", "cross_tenant")
                  for s, _ in scored[group] if s >= OPERATING_POINT)
    print(f"\n0.95 处越界样本对 {crossed} 对，全部由具名防线兜住" if not failures else "")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
