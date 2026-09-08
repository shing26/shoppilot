"""SSE 长连接下的 TTFT 与堆占用（ticket 18、ADR 0011）。

QPS 与长连接是两个维度，混测无意义：这一路只回答"500 个人同时挂着流式连接，
第一个字多久出来、堆吃了多少"。Locust 那条曲线负责 QPS 与 TP99。

TTFT 口径：客户端发出请求 -> 收到第一个 `event: token` 帧。含网络往返（本机为环回，可忽略），
不含前端渲染。与 PLAN 的"服务端收请求 -> 写出首个 token 帧"略有差别，差的是环回与客户端调度，
这里按客户端侧报，因为要量的是用户实际感受到的首字。

用法: python scripts/run_sse_ttft.py --connections 500 --duration 60
"""

import argparse
import concurrent.futures
import csv
import http.client
import json
import random
import statistics
import sys
import threading
import time
import urllib.parse
from datetime import datetime
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RESULTS = REPO / "loadtest" / "results"

QUERIES = [
    "发什么快递", "七天无理由怎么算", "跨店满减怎么算", "生鲜坏了怎么赔",
    "你们家用的哪家物流", "下单后几天能寄出", "钱几天能退回我卡上", "90002 的快递到哪了",
]
TENANTS = [("T001", "C001"), ("T002", "C200"), ("T003", "C300")]


def get_json(url, headers=None):
    parsed = urllib.parse.urlsplit(url)
    connection = http.client.HTTPConnection(parsed.netloc, timeout=15)
    try:
        connection.request("GET", parsed.path or "/", headers=headers or {})
        response = connection.getresponse()
        body = response.read().decode("utf-8")
        return json.loads(body)
    finally:
        connection.close()


def post_json(url, payload, headers=None):
    parsed = urllib.parse.urlsplit(url)
    connection = http.client.HTTPConnection(parsed.netloc, timeout=20)
    try:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        merged = {"Content-Type": "application/json; charset=utf-8", **(headers or {})}
        connection.request("POST", parsed.path or "/", body=body, headers=merged)
        response = connection.getresponse()
        raw = response.read().decode("utf-8")
        return json.loads(raw)
    finally:
        connection.close()


def heap_used_mb(base):
    try:
        payload = get_json(f"{base}/actuator/metrics/jvm.memory.used?tag=area:heap")
        return round(sum(m["value"] for m in payload["measurements"]) / 1048576, 1)
    except Exception:
        return -1.0


def one_connection(base, token, index, deadline):
    """打开一条 SSE 长连接，记录首字时延，然后在窗口内持续重连读完整流。"""
    parsed = urllib.parse.urlsplit(base)
    results = []
    while time.perf_counter() < deadline:
        connection = http.client.HTTPConnection(parsed.netloc, timeout=60)
        started = time.perf_counter()
        ttft = None
        frames = 0
        try:
            body = json.dumps({"query": random.choice(QUERIES)}, ensure_ascii=False).encode("utf-8")
            connection.request("POST", "/api/v1/support/chat/stream", body=body, headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json; charset=utf-8",
                "X-Conversation-Id": f"sse-{index}-{int(started)}",
            })
            response = connection.getresponse()
            if response.status != 200:
                results.append({"ok": False, "ttft_ms": None, "frames": 0,
                                "error": f"HTTP {response.status}"})
                break
            buffer = ""
            pending_event = None
            while True:
                chunk = response.read(512)
                if not chunk:
                    break
                if ttft is None:
                    ttft = (time.perf_counter() - started) * 1000
                buffer += chunk.decode("utf-8", "replace")
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    line = line.strip()
                    if line.startswith("event:"):
                        pending_event = line.split(":", 1)[1].strip()
                        if pending_event == "token":
                            frames += 1
                    elif line.startswith("data:") and pending_event == "done":
                        pending_event = None
            results.append({"ok": True, "ttft_ms": round(ttft, 1) if ttft is not None else None,
                            "frames": frames, "error": ""})
        except Exception as failure:
            results.append({"ok": False, "ttft_ms": None, "frames": 0,
                            "error": f"{type(failure).__name__}: {failure}"[:160]})
            break
        finally:
            connection.close()
    return results


def percentile(values, share):
    if not values:
        return float("nan")
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round(share * (len(ordered) - 1)))))
    return ordered[index]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8082")
    parser.add_argument("--connections", type=int, default=500)
    parser.add_argument("--duration", type=int, default=60)
    parser.add_argument("--label", default="")
    args = parser.parse_args()

    tokens = []
    for tenant, customer in TENANTS:
        token = post_json(f"{args.base}/auth/mock-token",
                          {"tenantId": tenant, "customerId": customer})["token"]
        tokens.append((tenant, customer, token))
    print(f"并发长连接 {args.connections}，持续 {args.duration}s，身份池 {len(tokens)} 组")
    heap_before = heap_used_mb(args.base)

    deadline = time.perf_counter() + args.duration
    collected = []
    peak_heap = heap_before
    sampler_stop = threading.Event()

    def sampler():
        nonlocal peak_heap
        while not sampler_stop.wait(2.0):
            sample = heap_used_mb(args.base)
            if sample > peak_heap:
                peak_heap = sample

    watcher = threading.Thread(target=sampler, daemon=True)
    watcher.start()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.connections) as pool:
        futures = [pool.submit(one_connection, args.base, tokens[i % len(tokens)][2], i, deadline)
                   for i in range(args.connections)]
        for future in concurrent.futures.as_completed(futures):
            collected.extend(future.result())
    sampler_stop.set()
    watcher.join(timeout=3)
    heap_after = heap_used_mb(args.base)

    ok = [row for row in collected if row["ok"]]
    failed = [row for row in collected if not row["ok"]]
    ttfts = [row["ttft_ms"] for row in ok if row["ttft_ms"] is not None]
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    RESULTS.mkdir(parents=True, exist_ok=True)
    out = RESULTS / f"sse-ttft-{stamp}-{args.connections}{('-' + args.label) if args.label else ''}.csv"
    with out.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["connections", "duration_s", "streams", "ok", "failed",
                         "ttft_p50_ms", "ttft_p90_ms", "ttft_p99_ms", "ttft_max_ms",
                         "heap_before_mb", "heap_peak_mb", "heap_after_mb", "errors"])
        writer.writerow([args.connections, args.duration, len(collected), len(ok), len(failed),
                         round(percentile(ttfts, 0.50), 1), round(percentile(ttfts, 0.90), 1),
                         round(percentile(ttfts, 0.99), 1), round(max(ttfts), 1) if ttfts else "",
                         heap_before, peak_heap, heap_after,
                         "; ".join(sorted({r["error"] for r in failed})[:3])])

    print(f"完成流式请求 {len(ok)} 条，失败 {len(failed)} 条")
    if ttfts:
        print(f"TTFT p50 {percentile(ttfts, 0.5):.0f}ms / p90 {percentile(ttfts, 0.9):.0f}ms / "
              f"p99 {percentile(ttfts, 0.99):.0f}ms / max {max(ttfts):.0f}ms")
        print(f"中位每流 token 帧数 {statistics.median([r['frames'] for r in ok]):.0f}")
    print(f"堆内存 起始 {heap_before}MB -> 峰值 {peak_heap}MB -> 结束 {heap_after}MB")
    print(f"落盘 {out.relative_to(REPO)}")
    if failed:
        print("样例错误：" + (sorted({r['error'] for r in failed})[0] if failed else ""))
    return 0 if len(ok) > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
