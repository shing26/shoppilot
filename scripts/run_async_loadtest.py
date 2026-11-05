"""用轻量发压器复测吞吐（票 208 的「发压器敏感性」实验）。

口径与 `run_loadtest.py` 保持一致：同一流量模型、同一网关配置、同一台机器、
同一组计数器差值；唯一变量是发压侧从「4 × Locust(gevent)」换成
「单进程 JDK HttpClient + 虚拟线程」。目的不是拿新数字替换旧数字，
而是把「服务端能力」与「同机发压机先饱和」拆开。

用法（网关需已用 perf 档启动）:
  python scripts/run_async_loadtest.py --base http://127.0.0.1:8083 \
      --model mix80 --steps 400,800,1200 --duration 45
"""

import argparse
import csv
import json
import os
import subprocess
import sys
import tempfile
import time
from datetime import datetime
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RESULTS = REPO / "loadtest" / "results"
GENERATOR = REPO / "loadtest" / "AsyncLoadGen.java"

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_loadtest import (COUNTERS, cpu_sampler, env_record, gateway_healthy,  # noqa: E402
                          read_cpu_sample, snapshot)


def java_executable():
    """源码启动要用 JDK 21；PATH 上的 java 可能是 18（本机就是）。"""
    home = os.environ.get("SHOPPILOT_JDK") or os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.exists():
            return str(candidate)
    return "java"


def run_step(base, model, concurrency, duration, warmup):
    before = snapshot(base)
    with tempfile.NamedTemporaryFile(prefix="async-cpu-", suffix=".txt", delete=False) as handle:
        cpu_path = Path(handle.name)
    sampler, cpu_handle = cpu_sampler(cpu_path, warmup + duration + 3)
    started = time.perf_counter()
    process = subprocess.run(
        [java_executable(), str(GENERATOR), "--base", base, "--model", model,
         "--concurrency", str(concurrency), "--duration", str(duration), "--warmup", str(warmup)],
        capture_output=True, text=True, timeout=warmup + duration + 120)
    elapsed = time.perf_counter() - started
    if cpu_handle:
        cpu_handle.close()
    if sampler:
        try:
            sampler.wait(timeout=15)
        except subprocess.TimeoutExpired:
            sampler.kill()
    if process.returncode != 0:
        raise RuntimeError(f"发压器退出 {process.returncode}: {process.stderr[-800:]}")
    after = snapshot(base)
    reading = json.loads(process.stdout.strip().splitlines()[-1])

    stats = {
        "concurrency": concurrency,
        "wall_s": round(elapsed, 1),
        "qps": reading["qps"],
        "p50_ms": reading["p50_ms"],
        "p95_ms": reading["p95_ms"],
        "p99_ms": reading["p99_ms"],
        "ok": reading["ok"],
        "failed": reading["failed"],
        "samples": reading["samples"],
    }
    for name in ("requests", "admitted", "rate_limited", "l1", "l2", "flight",
                 "llm", "embed_remote", "embed_cached", "tokens"):
        stats[f"{name}_delta"] = round(after[name] - before[name])
    valid = stats["requests_delta"] - stats["rate_limited_delta"]
    hits = stats["l1_delta"] + stats["l2_delta"] + stats["flight_delta"]
    stats["valid_requests_delta"] = valid
    stats["interception_total"] = round(hits / valid, 4) if valid else ""
    stats["interception_cache_only"] = (
        round((stats["l1_delta"] + stats["l2_delta"]) / valid, 4) if valid else "")
    stats.update(read_cpu_sample(cpu_path))
    cpu_path.unlink(missing_ok=True)
    stats["gateway_alive"] = gateway_healthy(base)
    return stats


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8083")
    parser.add_argument("--model", default="mix80", choices=("l1", "l2", "mix80", "biz"))
    parser.add_argument("--steps", default="400,800,1200")
    parser.add_argument("--duration", type=int, default=45)
    parser.add_argument("--warmup", type=int, default=5)
    parser.add_argument("--tag", default="")
    args = parser.parse_args()

    if not gateway_healthy(args.base):
        print(f"网关 {args.base} 未就绪（actuator/health/readiness 非 UP）", file=sys.stderr)
        return 2
    steps = [int(item) for item in args.steps.split(",") if item.strip()]
    tag = args.tag or f"async-{args.model}-{datetime.now():%Y%m%d-%H%M%S}"
    rows = []
    for step in steps:
        stats = run_step(args.base, args.model, step, args.duration, args.warmup)
        rows.append(stats)
        print(json.dumps(stats, ensure_ascii=False))
        if not stats["gateway_alive"]:
            print("网关中途消失，停止阶梯", file=sys.stderr)
            break

    RESULTS.mkdir(parents=True, exist_ok=True)
    ladder = RESULTS / f"ladder-{tag}.csv"
    fields = ["concurrency", "wall_s", "qps", "p50_ms", "p95_ms", "p99_ms", "ok", "failed",
              "samples", "requests_delta", "admitted_delta", "ratelimited_delta", "valid_requests_delta",
              "l1_delta", "l2_delta", "flight_delta", "interception_total", "interception_cache_only",
              "llm_delta", "embed_remote_delta", "embed_cached_delta", "tokens_delta",
              "cpu_mean_pct", "cpu_max_pct", "freemem_min_gb", "gateway_alive"]
    with ladder.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    env_file = RESULTS / f"env-{tag}.json"
    env_file.write_text(json.dumps({
        "run": tag,
        "trafficModel": args.model,
        "generator": "loadtest/AsyncLoadGen.java（JDK HttpClient + 虚拟线程，单进程）",
        "durationPerStepS": args.duration,
        "warmupS": args.warmup,
        "steps": rows,
        "env": env_record(),
        "counters": COUNTERS,
        "note": "对照 run_loadtest.py 的同档 Locust 阶梯；发压器不同，其余口径相同",
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n阶梯结果 {ladder.relative_to(REPO)}")
    print(f"环境记录 {env_file.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
