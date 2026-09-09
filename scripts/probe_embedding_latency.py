"""单条问句向量化的真实耗时（ADR 0011 与未命中 TTFT 归因的证据）。

服务端计时器里看不到这一段：T0 关键词层定案的问句不带向量进缓存读，
于是"新问法"的远程向量化发生在 CacheService 的 L2 查表里（AgentStateMachine:126 -> CacheService:124），
那一步没有任何 Timer 包住它。未命中首字里最大的一块就在这儿，所以单独量。

两种口径分开报：
  repeat  —— 同一句连打，命中 Ollama 自己的前缀缓存，量的是"热着的时候多快"；
  novel   —— 每句加随机后缀，必然全新输入，量的是真实新问法的代价。

用法: python scripts/probe_embedding_latency.py --samples 12
"""

import argparse
import json
import random
import statistics
import string
import sys
import time
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:11434"
TEXT = "生鲜坏了怎么赔"


def embed(text, model):
    body = json.dumps({"model": model, "input": text}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(BASE + "/api/embed", data=body,
                                     headers={"Content-Type": "application/json"}, method="POST")
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=120) as response:
        payload = json.loads(response.read().decode("utf-8"))
    took_ms = (time.perf_counter() - started) * 1000
    vector = payload["embeddings"][0]
    return took_ms, len(vector)


def report(label, values):
    ordered = sorted(values)
    def at(share):
        return ordered[min(len(ordered) - 1, int(round(share * (len(ordered) - 1))))]
    print(f"{label:<14} n={len(ordered):<4} p50 {at(0.5):7.1f}ms  p90 {at(0.9):7.1f}ms  "
          f"max {ordered[-1]:7.1f}ms")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=12)
    parser.add_argument("--model", default="bge-m3")
    args = parser.parse_args()

    try:
        warm, dims = embed(TEXT, args.model)
        print(f"模型 {args.model} 维度 {dims}，首次调用（含载入）{warm:.0f}ms")
    except Exception as failure:
        print(f"打不开 {BASE}：{type(failure).__name__}: {failure}")
        return 2

    # 第一次之后模型常驻，repeat 与 novel 的差才是"新输入"本身的代价
    repeats = [embed(TEXT, args.model)[0] for _ in range(args.samples)]
    report("同句重复", repeats)
    novels = [embed(TEXT + " " + "".join(random.choices(string.ascii_lowercase, k=8)), args.model)[0]
              for _ in range(args.samples)]
    report("新问法", novels)
    print(f"中位数之比 新问法/同句重复 = {statistics.median(novels) / statistics.median(repeats):.2f}")
    print("口径：本机 Ollama /api/embed 单线程串行，环回网络，无并发排队；"
          "生产形态下这一步要么进批处理服务，要么由意图网关把可缓存问法先吃掉。")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
