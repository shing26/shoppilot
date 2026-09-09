"""把 SSE 分桶扫描画成一张两栏图（未命中 TTFT 判据的配图）。

左图是判据本体：同一批自然流量下，命中 / 未命中知识 / 动作三条路径的 TTFT P50 随并发怎么变，
虚线是任务书 500 ms 的线。看点是"未命中知识"那条几乎是水平线——它不随并发涨，
所以慢不是因为排队，是每个请求自己就值这么多毫秒。
右图是 miss 臂（每请求都是全新问法）：新问句的远程向量化在 Ollama 上排队，
100 并发起 TTFT 直接进秒级，这条曲线就是 ADR 0011 那句话的图像版。

标注全用 ASCII，理由与 plot_loadtest_curves.py 相同：本机 matplotlib 没有中文字体。

用法: python scripts/plot_ttft_sweep.py
"""
import csv
import glob
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

REPO = Path(__file__).resolve().parent.parent
RESULTS = REPO / "loadtest" / "results"
OUT = REPO / "docs" / "ttft-sweep.png"


def load(pattern):
    rows = []
    for path in sorted(glob.glob(str(RESULTS / pattern))):
        with open(path, encoding="utf-8", newline="") as handle:
            for row in csv.DictReader(handle):
                rows.append(row)
    return rows


def by_connections(rows):
    return sorted(rows, key=lambda r: int(float(r["connections"])))


def value(row, key):
    raw = (row.get(key) or "").strip()
    return float(raw) if raw not in ("", None) else float("nan")


def main():
    mix = by_connections(load("sse-ttft-*-sweepmix.csv"))
    miss = by_connections(load("sse-ttft-*-sweepmiss.csv"))
    attrib = by_connections(load("sse-ttft-*-attrib.csv"))
    if not mix:
        raise SystemExit("没有 sse-ttft-*-sweepmix.csv，先跑 pwsh -File scripts/run_ttft_sweep.ps1")

    fig, axes = plt.subplots(1, 2, figsize=(12.5, 4.8))

    ax = axes[0]
    ax.set_title("SSE TTFT P50 by path (natural traffic, perf mode)")
    for key, label in (("hit_ttft_p50_ms", "cache hit (L1/L2/flight)"),
                       ("knowledge_ttft_p50_ms", "cache miss -> hybrid RAG"),
                       ("action_ttft_p50_ms", "cache miss -> tool loop (2 rounds)")):
        ax.plot([int(float(r["connections"])) for r in mix], [value(r, key) for r in mix],
                marker="o", label=label)
    if attrib:
        ax.plot([1] * len(attrib), [value(r, "knowledge_ttft_p50_ms") for r in attrib],
                marker="x", linestyle="", label="miss, 1 conn (attribution run)")
    ax.axhline(500, color="red", linestyle="--", linewidth=1, label="charter target 500 ms")
    ax.axhline(300, color="grey", linestyle=":", linewidth=1, label="MockLLM first-token floor 300 ms")
    ax.set_xlabel("concurrent long-lived SSE connections")
    ax.set_ylabel("TTFT P50 (ms)")
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.grid(True, which="both", alpha=0.3)
    ax.legend(fontsize=7, loc="lower left")

    ax = axes[1]
    ax.set_title("Novel phrasing every request: embedding queue dominates")
    conns = [int(float(r["connections"])) for r in miss]
    ax.plot(conns, [value(r, "knowledge_ttft_p50_ms") for r in miss], marker="o",
            label="miss P50 (every query novel)")
    ax.plot(conns, [value(r, "knowledge_ttft_p90_ms") for r in miss], marker="^",
            label="miss P90")
    ax.plot(conns, [value(r, "knowledge_ttft_p99_ms") for r in miss], marker="v",
            label="miss P99")
    ax.plot([int(float(r["connections"])) for r in mix],
            [value(r, "knowledge_ttft_p50_ms") for r in mix], marker="s", linestyle="--",
            label="miss P50 (repeat queries)")
    ax.axhline(500, color="red", linestyle="--", linewidth=1, label="target 500 ms")
    ax.set_xlabel("concurrent long-lived SSE connections")
    ax.set_ylabel("TTFT (ms)")
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.grid(True, which="both", alpha=0.3)
    # 这一栏四条曲线铺满画面，图例放框内任何一角都会压线，干脆挪到图下面
    ax.legend(fontsize=7, loc="upper center", bbox_to_anchor=(0.5, -0.14), ncol=3, frameon=False)

    fig.tight_layout(rect=(0, 0.06, 1, 1))
    fig.savefig(OUT, dpi=140)
    print(f"写出 {OUT.relative_to(REPO)}")


if __name__ == "__main__":
    main()
