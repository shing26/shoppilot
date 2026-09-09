"""SSE 长连接下的 TTFT 与堆占用（ticket 18、ADR 0011）。

QPS 与长连接是两个维度，混测无意义：这一路只回答"500 个人同时挂着流式连接，
第一个字多久出来、堆吃了多少"。Locust 那条曲线负责 QPS 与 TP99。

TTFT 口径：客户端发出请求 -> 收到第一个 `event: token` 帧。含网络往返（本机为环回，可忽略），
不含前端渲染。与 PLAN 的"服务端收请求 -> 写出首个 token 帧"略有差别，差的是环回与客户端调度，
这里按客户端侧报，因为要量的是用户实际感受到的首字。

09-09 改了测量本身，旧产物（sse-ttft-20260909-001822-500-perf.csv）仍保留但只代表首帧：
1. 旧版把"收到第一个 SSE 分片"当成首字。服务端在检索与模型之前就会推 status/meta 帧，
   所以旧数字量的是首帧不是首字，命中路径上两者差一个量级。本版两个都记。
2. 旧版把所有样本混在一个分位数里。"未命中 TTFT <500ms"这条判据必须只看未命中的纯知识路径：
   动作意图按设计要走两轮工具调用（perf 的 Mock 每轮各 300ms），拿它去对 500ms 是要素不对。
   本版按 meta.cacheLayer 与 meta.intent 拆成 hit / knowledge / action 三桶分别报分位数。

--force-miss：给每次提问追加随机字母后缀，让 L1 精确哈希必然不命中。后缀只用字母——
凑出 5-8 位数字会被订单号正则抓走，意图就变了。注意它挡不住 L2：实测同一个问题加不同的
随机后缀仍能以 >=0.95 的余弦互相命中，所以本脚本按"实际发生的层"报数，不按"想让它走哪条"报数。

用法: python scripts/run_sse_ttft.py --connections 500 --duration 60
      python scripts/run_sse_ttft.py --connections 200 --duration 60 --force-miss
"""

import argparse
import concurrent.futures
import csv
import http.client
import json
import random
import statistics
import string
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
# 含数字的那条是动作意图（订单号），走的是两轮工具调用，与纯知识路径不是一个预算；
# --policy-only 用它把动作查询摘掉，让归因的样本池和分母对得上。
POLICY_QUERIES = [query for query in QUERIES if not any(char.isdigit() for char in query)]
TENANTS = [("T001", "C001"), ("T002", "C200"), ("T003", "C300")]
HIT_LAYERS = ("L1", "L2", "FLIGHT")
BUCKETS = ("hit", "knowledge", "action")


def get_json(url, headers=None):
    parsed = urllib.parse.urlsplit(url)
    connection = http.client.HTTPConnection(parsed.netloc, timeout=15)
    try:
        connection.request("GET", parsed.path or "/", headers=headers or {})
        response = connection.getresponse()
        return json.loads(response.read().decode("utf-8"))
    finally:
        connection.close()


def post_json(url, payload, headers=None):
    parsed = urllib.parse.urlsplit(url)
    connection = http.client.HTTPConnection(parsed.netloc, timeout=60)
    try:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        merged = {"Content-Type": "application/json; charset=utf-8", **(headers or {})}
        connection.request("POST", parsed.path or "/", body=body, headers=merged)
        response = connection.getresponse()
        return json.loads(response.read().decode("utf-8"))
    finally:
        connection.close()


def heap_used_mb(base):
    """收尾那一次采样最容易撞上本机临时端口耗尽（旧产物里 heap_after 就是 -1），重试三次。"""
    for _ in range(3):
        try:
            payload = get_json(f"{base}/actuator/metrics/jvm.memory.used?tag=area:heap")
            return round(sum(m["value"] for m in payload["measurements"]) / 1048576, 1)
        except Exception:
            time.sleep(0.5)
    return -1.0


def bucket_of(layer, intent):
    if layer in HIT_LAYERS:
        return "hit"
    return "action" if (intent or "").startswith("ACTION_") else "knowledge"


def one_connection(base, token, index, deadline, force_miss, queries):
    """一条长连接：窗口内不断重连、读完整流，记录首帧/首字时延与它实际走的缓存层。"""
    parsed = urllib.parse.urlsplit(base)
    results = []
    while time.perf_counter() < deadline:
        connection = http.client.HTTPConnection(parsed.netloc, timeout=60)
        started = time.perf_counter()
        first_frame = None
        ttft = None
        layer = "unknown"
        intent = ""
        frames = 0
        completed = False
        try:
            text = random.choice(queries)
            if force_miss:
                text += " " + "".join(random.choices(string.ascii_lowercase, k=8))
            connection.request("POST", "/api/v1/support/chat/stream",
                               body=json.dumps({"query": text}, ensure_ascii=False).encode("utf-8"),
                               headers={"Authorization": f"Bearer {token}",
                                        "Content-Type": "application/json; charset=utf-8",
                                        "X-Conversation-Id": f"sse-{index}-{int(started)}"})
            response = connection.getresponse()
            if response.status != 200:
                results.append({"ok": False, "bucket": "error", "layer": layer, "ttft_ms": None,
                                "first_frame_ms": None, "frames": 0, "error": f"HTTP {response.status}"})
                break
            buffer = ""
            current_event = None
            while True:
                chunk = response.read(512)
                if not chunk:
                    break
                elapsed_ms = (time.perf_counter() - started) * 1000
                if first_frame is None:
                    first_frame = elapsed_ms
                buffer += chunk.decode("utf-8", "replace")
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    line = line.strip()
                    if line.startswith("event:"):
                        current_event = line.split(":", 1)[1].strip()
                        if current_event == "token":
                            frames += 1
                            # 首字只认第一个 token 帧；status/meta 在检索与模型之前就发出去了
                            if ttft is None:
                                ttft = elapsed_ms
                        elif current_event == "done":
                            completed = True
                    elif line.startswith("data:") and current_event == "meta":
                        try:
                            meta = json.loads(line[5:].strip())
                            layer = meta.get("cacheLayer") or layer
                            intent = meta.get("intent") or intent
                        except ValueError:
                            pass
            # 没收到 done 帧的流不算成功样本：它只读到半截，计入分位数会偏乐观
            results.append({"ok": completed, "bucket": bucket_of(layer, intent), "layer": layer,
                            "ttft_ms": round(ttft, 1) if ttft is not None else None,
                            "first_frame_ms": round(first_frame, 1) if first_frame is not None else None,
                            "frames": frames, "error": "" if completed else "no-done-frame"})
            if not completed:
                break
        except Exception as failure:
            results.append({"ok": False, "bucket": "error", "layer": layer, "ttft_ms": None,
                            "first_frame_ms": None, "frames": 0,
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


def fmt(value):
    return "" if value != value else format(value, ".1f")  # NaN -> 空


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8082")
    parser.add_argument("--connections", type=int, default=500)
    parser.add_argument("--duration", type=int, default=60)
    parser.add_argument("--label", default="")
    parser.add_argument("--force-miss", action="store_true",
                        help="给每次提问加随机后缀，尽量把 L1 打成未命中")
    parser.add_argument("--warm", action="store_true",
                        help="起压前用同步端点把 (租户, 问题) 全走一遍，让混合流量臂每次从相同初态出发")
    parser.add_argument("--policy-only", action="store_true",
                        help="只用不含数字的问句：把动作意图摘出去，归因时样本池才纯净")
    args = parser.parse_args()
    queries = POLICY_QUERIES if args.policy_only else QUERIES

    tokens = []
    for tenant, customer in TENANTS:
        token = post_json(f"{args.base}/auth/mock-token",
                          {"tenantId": tenant, "customerId": customer})["token"]
        tokens.append((tenant, customer, token))
    if args.warm and not args.force_miss:
        # 预热不计入统计：不然开头那一小段低并发会把分位数拉得好看
        warmed = 0
        for _, _, token in tokens:
            for query in QUERIES:
                answer = post_json(f"{args.base}/api/v1/support/chat", {"query": query},
                                   {"Authorization": f"Bearer {token}"})
                warmed += 1 if answer.get("answer") else 0
        print(f"预热 {warmed} 条（同步端点，不计入统计）")
    print(f"并发长连接 {args.connections}，持续 {args.duration}s，身份池 {len(tokens)} 组"
          + ("【强制未命中】" if args.force_miss else "")
          + ("【仅知识问句】" if args.policy_only else ""))
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
        futures = [pool.submit(one_connection, args.base, tokens[i % len(tokens)][2], i, deadline,
                               args.force_miss, queries)
                   for i in range(args.connections)]
        for future in concurrent.futures.as_completed(futures):
            collected.extend(future.result())
    sampler_stop.set()
    watcher.join(timeout=5)
    heap_after = heap_used_mb(args.base)

    ok = [row for row in collected if row["ok"]]
    failed = [row for row in collected if not row["ok"]]
    groups = {"all": ok}
    for bucket in BUCKETS:
        groups[bucket] = [row for row in ok if row["bucket"] == bucket]
    ttfts = {name: [r["ttft_ms"] for r in rows if r["ttft_ms"] is not None]
             for name, rows in groups.items()}
    frames = {name: [r["frames"] for r in rows] for name, rows in groups.items()}

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    RESULTS.mkdir(parents=True, exist_ok=True)
    out = RESULTS / f"sse-ttft-{stamp}-{args.connections}{('-' + args.label) if args.label else ''}.csv"
    header = ["connections", "duration_s", "streams", "ok", "failed",
              "heap_before_mb", "heap_peak_mb", "heap_after_mb", "errors",
              "ttft_p50_ms", "ttft_p90_ms", "ttft_p99_ms", "ttft_max_ms", "frames_median",
              "first_frame_p50_ms"]
    for bucket in BUCKETS:
        header += [f"{bucket}_n", f"{bucket}_ttft_p50_ms", f"{bucket}_ttft_p90_ms",
                   f"{bucket}_ttft_p99_ms", f"{bucket}_frames_median"]
    header += [f"layer_{layer.lower()}_n" for layer in HIT_LAYERS] + ["layer_none_n"]
    layer_counts = {layer: sum(1 for r in ok if r["layer"] == layer)
                    for layer in list(HIT_LAYERS) + ["NONE"]}
    with out.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        row = [args.connections, args.duration, len(collected), len(ok), len(failed),
               heap_before, peak_heap, heap_after,
               "; ".join(sorted({r["error"] for r in failed})[:3]),
               fmt(percentile(ttfts["all"], 0.5)), fmt(percentile(ttfts["all"], 0.9)),
               fmt(percentile(ttfts["all"], 0.99)), fmt(max(ttfts["all"]) if ttfts["all"] else float("nan")),
               fmt(statistics.median(frames["all"]) if frames["all"] else float("nan")),
               fmt(percentile([r["first_frame_ms"] for r in ok if r["first_frame_ms"] is not None], 0.5))]
        for bucket in BUCKETS:
            row += [len(groups[bucket]), fmt(percentile(ttfts[bucket], 0.5)),
                    fmt(percentile(ttfts[bucket], 0.9)), fmt(percentile(ttfts[bucket], 0.99)),
                    fmt(statistics.median(frames[bucket]) if frames[bucket] else float("nan"))]
        row += [layer_counts.get(layer, 0) for layer in HIT_LAYERS] + [layer_counts.get("NONE", 0)]
        writer.writerow(row)

    def describe(name):
        values = ttfts[name]
        if not values:
            return "无样本"
        return (f"p50 {percentile(values, 0.5):.0f}ms / p90 {percentile(values, 0.9):.0f}ms / "
                f"p99 {percentile(values, 0.99):.0f}ms / max {max(values):.0f}ms")

    print(f"完成流式请求 {len(ok)} 条，失败 {len(failed)} 条（含未收到 done 帧的截断流）")
    print(f"  命中 L1={layer_counts['L1']} L2={layer_counts['L2']} FLIGHT={layer_counts['FLIGHT']} "
          f"未命中={layer_counts['NONE']}")
    for bucket in BUCKETS:
        print(f"  {bucket:<10} n={len(groups[bucket]):<6} {describe(bucket)}")
    print(f"堆内存 起始 {heap_before}MB -> 峰值 {peak_heap}MB -> 结束 {heap_after}MB")
    print(f"落盘 {out.relative_to(REPO)}")
    if failed:
        print("样例错误：" + (sorted({r["error"] for r in failed})[0] if failed else ""))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
