# -*- coding: utf-8 -*-
"""flush 与在线检索的竞态探针（一次性复现 + 分类计数）。

背景：调试台的「清缓存」按钮打的是 `/ops/cache/flush`，实现是把 Qdrant 的 answer_cache 表删掉再建。
删与建之间那段表真的不存在，撞进去的 L2 检索会拿 404。本脚本把 flush 与并发问答混打若干轮，量三件事：

1. flush 本身有没有 5xx（曾经因为把两步并成 `recreate=true` 而整端点 500 —— Qdrant 1.12.4 不认这个参数）；
2. 竞态发生时日志里是「L2 检索失败」（读起来像存储坏了）还是被按空缓存分类（debug + 计数器）；
3. 风暴过后 answer_cache 还在不在、还是不是 green。

用法（网关与中间件已在跑）：

    python scripts/probe_flush_race.py --rounds 12 --profile local

退出码 0 = flush 全程 2xx、没有一行「L2 检索失败」WARN、表仍是 green。
"""
import argparse
import concurrent.futures as cf
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QUERIES = [
    "这个能退吗", "这个能退么", "七天无理由怎么算", "生鲜坏了怎么赔",
    "默认发什么快递", "定金膨胀几倍", "跨店满减怎么凑", "发票开了没",
]


def http_json(url, payload=None, headers=None, timeout=120):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(url, data=data, method="POST" if data is not None else "GET")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


def metrics(base):
    body = http_json(base + "/actuator/prometheus")[1]
    wanted = ("shoppilot_cache_l2_total", "shoppilot_cache_l2_missing_total",
              "shoppilot_embedding_failure_total")
    values = {}
    for line in body.splitlines():
        for name in wanted:
            if line.startswith(name):
                values[line] = float(line.rsplit(" ", 1)[1])
    return values


def diff(before, after):
    out = {}
    for key, value in after.items():
        delta = value - before.get(key, 0.0)
        if delta:
            out[key] = delta
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8082")
    parser.add_argument("--profile", default="local")
    parser.add_argument("--rounds", type=int, default=12)
    parser.add_argument("--qdrant", default="http://127.0.0.1:16333")
    parser.add_argument("--ops-token", default="dev-ops-token")
    args = parser.parse_args()

    log_path = os.path.join(ROOT, "logs", "gateway-%s.out" % args.profile)
    if not os.path.exists(log_path):
        print("找不到网关日志 %s；用 --profile 指对文件名" % log_path)
        return 2

    token = json.loads(http_json(args.base + "/auth/mock-token",
                                 {"tenantId": "T001", "customerId": "C001"})[1])["token"]
    auth = {"Authorization": "Bearer " + token}
    ops = dict(auth)
    ops["X-Ops-Token"] = args.ops_token
    mark = os.path.getsize(log_path)
    before = metrics(args.base)
    statuses = Counter()

    def ask(index):
        query = QUERIES[index % len(QUERIES)]
        headers = dict(auth)
        headers["X-Conversation-Id"] = "race-%d-%d" % (random.randint(0, 9999), index)
        status, _ = http_json(args.base + "/api/v1/support/chat", {"query": query}, headers)
        return status

    def flush():
        status, body = http_json(args.base + "/api/v1/support/ops/cache/flush", {}, ops)
        statuses["flush-%s" % status] += 1
        if status >= 500:
            print("  flush 返回 %s：%s" % (status, body[:180]))
        return status

    with cf.ThreadPoolExecutor(max_workers=8) as pool:
        for round_index in range(args.rounds):
            futures = [pool.submit(ask, round_index * len(QUERIES) + i) for i in range(len(QUERIES))]
            futures.append(pool.submit(flush))
            for future in futures:
                future.result()
            time.sleep(0.5)

    with open(log_path, encoding="utf-8", errors="replace") as handle:
        handle.seek(mark)
        tail = handle.read()
    outage_like = [line for line in tail.splitlines() if "L2 检索失败" in line]
    after = metrics(args.base)
    deltas = diff(before, after)
    print("%d 轮（每轮 %d 路问答 + 1 次 flush）之后：" % (args.rounds, len(QUERIES)))
    print("  flush 结果         ", dict(statuses))
    print("  「L2 检索失败」WARN ", len(outage_like), "行")
    for line in outage_like[:3]:
        print("    ", line[:200])
    for key in sorted(deltas):
        print("  delta", key, int(deltas[key]))
    status, body = http_json(args.qdrant + "/collections/answer_cache")
    payload = json.loads(body)
    state = payload.get("result", {}).get("status") or payload.get("status", {}).get("error")
    print("  风暴后 answer_cache", status, state)

    failed_flush = sum(value for key, value in statuses.items() if not key.endswith("200"))
    if failed_flush or outage_like or state != "green":
        print("FAIL  flush 有非 200 / 还有存储故障样 WARN / 表状态 %s" % state)
        return 1
    print("PASS  flush 全程 2xx，竞态全部按空缓存分类，表在风暴后仍是 green")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
