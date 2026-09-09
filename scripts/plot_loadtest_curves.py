"""把阶梯压测 CSV 画成两张图：QPS 拐点与分位数时延。

为什么单独一个脚本而不是塞进 build_loadtest_report.py：报告生成器要保持零第三方依赖，
谁都能在没有 matplotlib 的机器上重跑出 markdown；画图是可选增强。

图上的标注刻意全用 ASCII：这台机器的 matplotlib 没有中文字体，中文标题会渲成方框，
而一张满是方框的图比没有图更糟。

用法: python scripts/plot_loadtest_curves.py
"""
from pathlib import Path

import csv

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

REPO = Path(__file__).resolve().parent.parent
RESULTS = REPO / "loadtest" / "results"
OUT = REPO / "docs" / "loadtest-curves.png"

CURVES = [
    ("L1-dominant", "ladder-l1-perf-20260908-231233-final.csv"),
    ("mix80 (charter shape)", "ladder-mix80-perf-20260908-233023-final.csv"),
    ("L2-dominant", "ladder-l2-perf-20260909-000535-l2.csv"),
    ("L2, embedding per request", "ladder-l2-perf-20260909-012151-l2emb.csv"),
    ("L1, platform threads", "ladder-l1-perf,no-virtual-20260909-011351-novirtual.csv"),
    # 向量服务停用（ticket 18 最后一格）：同模型同并发，只是 embedding 不可用。
    # 图上这条与 L1-dominant 的间距就是"向量服务是大促单点"这件事的形状。
    ("L1, embedding service down", "ladder-l1-perf,no-ollama-20260909-123509-noollama.csv"),
]


def load(name):
    path = RESULTS / name
    if not path.exists():
        raise SystemExit(f"缺少数据文件 {path.relative_to(REPO)}")
    # 必须走 csv 而不是 split(",")：profile 列的值本身带逗号（perf,no-virtual），
    # 手工切分会让该行右侧所有列整体错位，图上就冒出 QPS=0 这种假数据。
    with path.open(encoding="utf-8", newline="") as handle:
        return [row for row in csv.DictReader(handle)
                if any((value or "").strip() for value in row.values())]


def column(rows, key):
    values = []
    for row in rows:
        raw = row.get(key, "")
        values.append(float(raw) if raw not in ("", None) else float("nan"))
    return values


def main():
    fig, axes = plt.subplots(1, 2, figsize=(12.5, 4.8))
    loaded = [(label, load(name)) for label, name in CURVES]

    ax = axes[0]
    ax.set_title("QPS vs concurrent users (perf mode, gateway only)")
    ax.set_xlabel("concurrent users")
    ax.set_ylabel("QPS")
    for label, rows in loaded:
        ax.plot(column(rows, "users"), column(rows, "qps"), marker="o", label=label)
    ax.set_yscale("log")
    ax.grid(True, which="both", alpha=0.3)
    ax.legend(fontsize=8)

    ax = axes[1]
    ax.set_title("Latency percentiles, L1-dominant curve")
    ax.set_xlabel("concurrent users")
    ax.set_ylabel("ms")
    l1 = dict(loaded)["L1-dominant"]
    users = column(l1, "users")
    for key, style in (("p50", "-o"), ("p95", "-s"), ("p99", "-^"), ("max", "--x")):
        ax.plot(users, column(l1, key), style, label=key.upper(), markersize=4)
    hits = column(l1, "hit_p99")
    ax.plot(users, hits, "-o", label="cache-hit P99", markersize=4)
    ax.axhline(500, color="grey", linestyle=":", label="charter TTFT target 500ms")
    ax.set_yscale("log")
    ax.grid(True, alpha=0.3)
    ax.legend(fontsize=8)

    fig.tight_layout()
    fig.savefig(OUT, dpi=140)
    print(f"写出 {OUT.relative_to(REPO)}")
    for label, rows in loaded:
        peak = max(column(rows, "qps"))
        users_at = column(rows, "users")[column(rows, "qps").index(peak)]
        print(f"  {label:<28} {len(rows)} 档，QPS 峰值 {peak:.1f} @ {int(users_at)} 并发")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
