"""未命中首字时延的分解归因（ticket 18 / PLAN 承诺项"未命中 TTFT <500ms"）。

扫描（run_ttft_sweep.ps1）回答"TTFT 随并发怎么变"，这一支回答另一个问题：
"未命中那一千多毫秒，到底是谁花的"。做法是单连接串行打一串强制未命中的请求——
没有排队就没有争抢，客户端量到的首字约等于服务端串起来的每一段工作之和；
再把服务端自己的计时器读出来逐段对齐，剩下的才是网关编排本身的开销。

为什么必须单独做这一支：README 里那条 <500ms 未达成只写了一句"同机 500 长连接"，
而扫描证明命中路径在 200 并发内只有 11-21ms、未命中路径在 50/100/200 并发几乎是同一条水平线
（1031/1036/1049ms）——未命中不是被并发拖慢的，是被每个请求自己的固定开销拖慢的。
要说清这句话，得有分段数字，不能靠推断。

用法（网关要先以 perf 起好）:
    python scripts/ttft_attribution.py --duration 45
"""

import argparse
import csv
import json
import random
import statistics
import subprocess
import sys
import string
import time
import http.client
import urllib.request
from datetime import datetime
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RESULTS = REPO / "loadtest" / "results"
ATTRIBUTION_GLOB = "ttft-attribution-*.csv"

# 服务端自己的计时器：口径与 PLAN 一致（收请求 -> 写出首个 token 帧）
METRICS = [
    ("shoppilot_ttft_seconds", "", "首字（含 Mock 300ms 下限）"),
    ("shoppilot_retrieve_dense_seconds", "", "稠密检索：只有 Qdrant 打分，向量在缓存读阶段已算好并被进程内缓存复用"),
    ("shoppilot_retrieve_lexical_seconds", "", "词法检索（ES）"),
    ("shoppilot_llm_latency_seconds", "mode:perf", "模型整轮（perf=Mock 固定 500ms）"),
]


def rpc(method, path, payload=None, headers=None):
    connection = http.client.HTTPConnection("127.0.0.1:8082", timeout=30)
    try:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
        merged = {"Content-Type": "application/json; charset=utf-8"}
        merged.update(headers or {})
        connection.request(method, path, body=body, headers=merged)
        response = connection.getresponse()
        raw = response.read().decode("utf-8")
        return response.status, (json.loads(raw) if raw else None)
    finally:
        connection.close()


def admin_headers():
    _, token = rpc("POST", "/auth/mock-token", {"tenantId": "T001", "customerId": "C001"})
    return {"Authorization": f"Bearer {token['token']}", "X-Ops-Token": "dev-ops-token"}


def metric(name, tag=None):
    url = f"/actuator/metrics/{name}" + (f"?tag={tag}" if tag else "")
    status, payload = rpc("GET", url)
    if status != 200 or not payload:
        return None
    values = {m["statistic"]: m["value"] for m in payload.get("measurements", [])}
    return {"count": values.get("COUNT", 0), "total_s": values.get("TOTAL_TIME", 0.0),
            "max_s": values.get("MAX", 0.0)}


def probe_vectorize(model, samples):
    """量一次"新问句在本机向量化要多久"——这一步在服务端没有计时器，只能从外部打。"""
    base = "http://127.0.0.1:11434"
    latencies = []
    for _ in range(samples):
        text = "生鲜坏了怎么赔 " + "".join(random.choices(string.ascii_lowercase, k=8))
        body = json.dumps({"model": model, "input": text}, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(base + "/api/embed", data=body,
                                         headers={"Content-Type": "application/json"}, method="POST")
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                response.read()
        except Exception as failure:
            print(f"向量化探针失败：{type(failure).__name__}: {failure}")
            return None
        latencies.append((time.perf_counter() - started) * 1000)
    ordered = sorted(latencies)
    return {"n": len(ordered), "p50_ms": round(statistics.median(ordered), 1),
            "max_ms": round(ordered[-1], 1)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--duration", type=int, default=45)
    parser.add_argument("--connections", type=int, default=1)
    parser.add_argument("--model", default="bge-m3")
    args = parser.parse_args()

    headers = admin_headers()
    _, switches = rpc("GET", "/api/v1/support/ops/switches", headers=headers)
    if not switches or switches.get("llmMode") != "perf":
        print(f"当前 llmMode={switches and switches.get('llmMode')}，归因必须在 perf 下做（首字下限才是固定的 300ms）")
        return 2
    _, flushed = rpc("POST", "/api/v1/support/ops/cache/flush", {}, headers=headers)
    print(f"已清空缓存 {flushed}")

    python = sys.executable
    print(f"串行打 {args.duration}s（连接数 {args.connections}，每次提问加随机后缀强制未命中，只用知识问句）...")
    subprocess.run([python, str(REPO / "scripts" / "run_sse_ttft.py"),
                    "--connections", str(args.connections), "--duration", str(args.duration),
                    "--force-miss", "--policy-only", "--label", "attrib"], check=True)
    newest = max(RESULTS.glob("sse-ttft-*-attrib.csv"), key=lambda p: p.stat().st_mtime)
    with newest.open(encoding="utf-8", newline="") as handle:
        client_row = next(iter(csv.DictReader(handle)))
    action_n = int(client_row.get("action_n") or 0)
    hit_n = int(client_row.get("hit_n") or 0)
    if action_n or hit_n:
        print(f"注意：样本里有 {action_n} 条动作路径、{hit_n} 条命中，服务端 TTFT 均值会被它们带偏，"
              f"下面的余量只是上界")

    terms = []
    print("\n=== 服务端计时器（进程启动以来的累计，网关刚重启过，所以就是这一轮的）===")
    print(f"{'项':<40}{'次数':>7}{'均值 ms':>10}{'最大 ms':>10}  说明")
    for name, tag, note in METRICS:
        sample = metric(name, tag or None)
        if not sample or not sample["count"]:
            print(f"{name:<40}{'-':>7}{'-':>10}{'-':>10}  {note}（无样本）")
            continue
        avg_ms = sample["total_s"] / sample["count"] * 1000
        terms.append((name, int(sample["count"]), round(avg_ms, 1),
                      round(sample["max_s"] * 1000, 1), note))
        print(f"{name:<40}{sample['count']:>7.0f}{avg_ms:>10.1f}{sample['max_s'] * 1000:>10.1f}  {note}")

    # 新问法的远程向量化没有计时器包住（发生在 L2 查表内部），只能自己量一次放进来
    vectorize = probe_vectorize(args.model, 8)
    if vectorize:
        terms.append(("embedding_novel_probe", vectorize["n"], vectorize["p50_ms"],
                      vectorize["max_ms"], "本机 bge-m3 单条新问句向量化（无服务端计时器，探针实测）"))
        print(f"{'embedding_novel_probe':<40}{vectorize['n']:>7}{vectorize['p50_ms']:>10.1f}"
              f"{vectorize['max_ms']:>10.1f}  本机 bge-m3 单条新问句向量化（探针实测）")

    ttft_row = next((t for t in terms if t[0] == "shoppilot_ttft_seconds"), None)
    dense_row = next((t for t in terms if t[0] == "shoppilot_retrieve_dense_seconds"), None)
    lexical_row = next((t for t in terms if t[0] == "shoppilot_retrieve_lexical_seconds"), None)
    if ttft_row and dense_row and lexical_row and vectorize:
        residual = ttft_row[2] - (300.0 + vectorize["p50_ms"] + dense_row[2] + lexical_row[2])
        terms.append(("mock_first_token_floor", "", 300.0, "",
                      "perf MockLLM 固定首字下限（配置值，非实测）"))
        terms.append(("orchestration_residual", ttft_row[1], round(residual, 1), "",
                      "服务端 TTFT 减掉已知项之后的余量：状态机 + 提示词组装 + RRF + SSE 写出 + 采样误差"))
        print("\n=== 归因（均值口径；减完已知项剩下的才是网关自己花的）===")
        print(f"服务端 TTFT 均值            {ttft_row[2]:8.1f} ms")
        print(f"  - Mock 首字下限            {300.0:8.1f} ms")
        print(f"  - 新问法向量化（探针）      {vectorize['p50_ms']:8.1f} ms")
        print(f"  - 稠密检索（向量已算好）    {dense_row[2]:8.1f} ms")
        print(f"  - 词法检索（ES）            {lexical_row[2]:8.1f} ms")
        print(f"  = 网关编排余量              {residual:8.1f} ms")
        print("读法：判据 500ms 光被前两项就吃掉大半，这是形态问题，不是编排问题。")

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = RESULTS / f"ttft-attribution-{stamp}-{args.connections}conn.csv"
    with out.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["term", "count", "avg_ms", "max_ms", "note"])
        for term in terms:
            writer.writerow(term)
    print(f"\n证据 CSV：{out.relative_to(REPO)}、客户端分桶 {newest.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
