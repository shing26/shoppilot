"""阶梯压测编排（ticket 18、ADR 0011）。

每一档做三件事：读网关计数器 -> 跑 Locust -> 再读计数器。
拦截率必须由计数器差值算出来，不能靠"流量模型里写了 55% 热点"就宣称拦住了 55%。

每组结果都带一份环境记录（CPU、内存、JDK、git commit、容器内存），
因为这台机器上还跑着别的项目，不带环境记录的压测数字没有可比性。

用法:
  python scripts/run_loadtest.py --model l1 --profile perf
  python scripts/run_loadtest.py --model l1 --profile no-virtual --steps 50,100,200
"""

import argparse
import csv
import json
import os
import platform
import subprocess
import sys
import time
import urllib.request
from datetime import datetime
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RESULTS = REPO / "loadtest" / "results"
LOCUSTFILE = REPO / "loadtest" / "locustfile.py"

COUNTERS = {
    "requests": ["shoppilot_requests_total"],
    "admitted": ["shoppilot_cache_admitted_total"],
    # requests_total 在限流判定之前累加，所以被 429 的请求也在里面；
    # PLAN 的分母口径是"有效咨询请求"，必须把 429 减掉，否则拦截率会被拒流量稀释。
    "rate_limited": ["shoppilot_rate_limited_total"],
    "l1": ['shoppilot_cache_hit_total?tag=layer:L1'],
    "l2": ['shoppilot_cache_hit_total?tag=layer:L2'],
    "flight": ["shoppilot_singleflight_merged_total"],
    # \u547d\u4e2d\u8def\u5f84\u8981\u6c42\u96f6\u6a21\u578b\u8c03\u7528\u4e0e\u96f6 embedding \u8c03\u7528\uff0c\u4e24\u8005\u90fd\u5355\u72ec\u53d6\u5dee\u503c
    "llm": ["shoppilot_llm_calls_total?tag=kind:complete", "shoppilot_llm_calls_total?tag=kind:stream"],
    "embed_remote": ["shoppilot_embedding_calls_total?tag=result:remote"],
    "embed_cached": ["shoppilot_embedding_calls_total?tag=result:in-process-cache"],
}


def locust_python() -> str:
    """\u538b\u6d4b\u4e13\u7528 venv\uff1bAnaconda \u81ea\u5e26\u7684\u65e7 urllib3 \u4f1a\u628a locust \u6253\u7206\u3002"""
    for candidate in (REPO / ".venv-loadtest" / "Scripts" / "python.exe",
                      REPO / ".venv-loadtest" / "bin" / "python"):
        if candidate.exists():
            return str(candidate)
    return sys.executable


def counter(base, metric):
    url = f"{base}/actuator/metrics/{metric}"
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            payload = json.loads(response.read().decode("utf-8"))
        return sum(m["value"] for m in payload["measurements"])
    except Exception:
        return 0.0


def snapshot(base):
    return {name: sum(counter(base, metric) for metric in metrics)
            for name, metrics in COUNTERS.items()}


def env_record():
    info = {
        "timestamp": datetime.now().isoformat(timespec="seconds"),
        "os": f"{platform.system()} {platform.release()}",
        "python": platform.python_version(),
        "machine": platform.node(),
        "cpu_count": os.cpu_count(),
    }
    try:
        info["gitCommit"] = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=REPO,
                                           capture_output=True, text=True, timeout=15).stdout.strip()
    except Exception:
        info["gitCommit"] = "unknown"
    try:
        out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}}={{.MemUsage}}"],
                             capture_output=True, text=True, timeout=30).stdout
        info["containers"] = [line for line in out.splitlines() if line.startswith("shoppilot-")]
    except Exception:
        info["containers"] = []
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-Command",
                              "(Get-CimInstance Win32_OperatingSystem | "
                              "ForEach-Object { [math]::Round($_.FreePhysicalMemory/1MB,1) })"],
                             capture_output=True, text=True, timeout=30).stdout.strip()
        info["freeMemGB"] = out
    except Exception:
        info["freeMemGB"] = "unknown"
    return info


def read_locust_stats(prefix):
    """返回 {locust name -> 指标}，含 Aggregated。

    按流量类型分别取分位数是这张表最有用的部分：缓存命中路径的 TP99 要对着 30ms 的 SLO 报，
    混在 Aggregated 里会被未命中路径（含 mock 的固定模型延迟）彻底淹没。
    """
    # locust 2.x headless 落的是 _stats.csv；旧文档里叫 _statistics.csv，两种都认
    path = next((Path(f"{prefix}_{n}.csv") for n in ("stats", "statistics")
                 if Path(f"{prefix}_{n}.csv").exists()), None)
    if path is None:
        return {}
    result = {}
    for target in csv.DictReader(path.open(encoding="utf-8")):
        name = (target.get("Name") or "").strip()
        if not name:
            continue

        def number(key):
            raw = (target.get(key) or "").strip()
            return raw.replace("*", "").strip()

        result[name] = {
            "request_count": number("Request Count") or number("# Requests"),
            "failure_count": number("Failure Count") or number("# Fails"),
            "qps": number("Requests/s"),
            "p50": number("50%"),
            "p95": number("95%"),
            "p99": number("99%"),
            "max": number("Max Response Time") or number("Max"),
        }
    return result


def cpu_sampler(path, seconds):
    """采样本机 CPU 与空闲内存。不带环境采样的压测数字没有可比性：
    这台机器上还跑着别的项目的容器，发压进程自己也要吃 CPU。"""
    script = ('$end = (Get-Date).AddSeconds($env:LM_SAMPLE_S); '
              'while ((Get-Date) -lt $end) { '
              '$c = (Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name=\'_Total\'").PercentProcessorTime; '
              '$f = [math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB,1); '
              'Write-Output ((Get-Date -Format HH:mm:ss) + "=" + $c + "=" + $f); '
              'Start-Sleep -Seconds 5 }')
    handle = open(path, "w", encoding="utf-8")
    environment = dict(os.environ)
    environment["LM_SAMPLE_S"] = str(seconds)
    try:
        process = subprocess.Popen(["powershell", "-NoProfile", "-Command", script],
                                   stdout=handle, stderr=subprocess.DEVNULL, env=environment)
        return process, handle
    except OSError:
        handle.close()
        return None, None


def read_cpu_sample(path):
    if not path.exists():
        return {}
    loads, mems = [], []
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        parts = line.strip().split("=")
        if len(parts) != 3:
            continue
        try:
            loads.append(float(parts[1]))
            mems.append(float(parts[2]))
        except ValueError:
            continue
    if not loads:
        return {}
    return {"cpu_mean_pct": round(sum(loads) / len(loads), 1), "cpu_max_pct": max(loads),
            "freemem_min_gb": round(min(mems) / 1024.0, 2)}


TRAFFIC_COLUMNS = (("chat[hot]", "hot"), ("chat[para]", "para"),
                   ("chat[action]", "action"), ("chat[long]", "long"))


def aggregate_locust(per_worker_stats):
    """多进程发压时合并：请求数与 QPS 相加，分位数取最差 worker。
    合并分位数需要原始样本，这里不做假精确，取上界并写明。"""
    rows = [row for row in per_worker_stats if row]
    if not rows:
        return {}
    merged = {}
    for name in set().union(*(set(row) for row in rows)):
        parts = [row[name] for row in rows if name in row]
        if len(parts) == 1:
            merged[name] = parts[0]
            continue

        def total(key):
            return sum(int(float(part.get(key) or 0)) for part in parts)

        def worst(key):
            return max((float(part.get(key) or 0) for part in parts), default=0.0)

        merged[name] = {
            "request_count": str(total("request_count")),
            "failure_count": str(total("failure_count")),
            "qps": f"{sum(float(part.get('qps') or 0) for part in parts):.2f}",
            "p50": str(int(worst("p50"))),
            "p95": str(int(worst("p95"))),
            "p99": str(int(worst("p99"))),
            "max": str(int(worst("max"))),
        }
    return merged


def run_step(base, users, spawn, duration, model, out_prefix, workers=1):
    environment = dict(os.environ)
    environment["SHOPPILOT_TRAFFIC_MODEL"] = model
    environment["SHOPPILOT_BASE_URL"] = base
    # locust 在 Windows 上不支持 --processes，而单进程自己就会先成为瓶颈（实测约 260 QPS/进程）。
    # 高并发档必须多进程发压，否则测到的是发压机而不是网关。
    per_users = max(1, users // workers)
    per_spawn = max(1, spawn // workers)
    prefixes = []
    processes = []
    before = snapshot(base)
    started = time.perf_counter()
    sampler_path = Path(f"{out_prefix}_cpu.txt")
    sampler, sampler_handle = cpu_sampler(sampler_path, duration + 15)
    for index in range(workers):
        prefix = out_prefix if workers == 1 else f"{out_prefix}-w{index + 1}"
        prefixes.append(prefix)
        command = [locust_python(), "-m", "locust", "-f", str(LOCUSTFILE), "--host", base, "--headless",
                   "-u", str(per_users), "-r", str(per_spawn), "-t", f"{duration}s",
                   "--csv", prefix, "--only-summary", "--loglevel", "WARNING"]
        processes.append(subprocess.Popen(command, cwd=REPO, env=environment,
                                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True))
    errors = []
    for process in processes:
        try:
            _, error = process.communicate(timeout=duration * 4 + 240)
            if process.returncode != 0:
                errors.append((error or "")[-200:])
        except subprocess.TimeoutExpired:
            process.kill()
            errors.append("locust timeout")
    if sampler:
        try:
            sampler.wait(timeout=60)
        except subprocess.TimeoutExpired:
            sampler.kill()
    sampler_handle.close()
    elapsed = time.perf_counter() - started
    after = snapshot(base)
    merged = aggregate_locust([read_locust_stats(prefix) for prefix in prefixes])
    stats = dict(merged.get("Aggregated", {}))
    # 分流量类型报分位数：hot 这一路就是"缓存命中 TP99 < 30ms"的直接证据
    for name, column in TRAFFIC_COLUMNS:
        row = merged.get(name)
        if row:
            for metric in ("request_count", "failure_count", "qps", "p50", "p95", "p99"):
                stats[f"{column}_{metric}"] = row[metric]
    stats.update({
        "users": users,
        "workers": workers,
        "per_worker_users": per_users,
        "wall_s": round(elapsed, 1),
        "model": model,
        "requests_delta": round(after["requests"] - before["requests"]),
        "admitted_delta": round(after["admitted"] - before["admitted"]),
        "ratelimited_delta": round(after["rate_limited"] - before["rate_limited"]),
        "l1_delta": round(after["l1"] - before["l1"]),
        "l2_delta": round(after["l2"] - before["l2"]),
        "flight_delta": round(after["flight"] - before["flight"]),
        "llm_delta": round(after["llm"] - before["llm"]),
        "embed_remote_delta": round(after["embed_remote"] - before["embed_remote"]),
        "embed_cached_delta": round(after["embed_cached"] - before["embed_cached"]),
    })
    valid = (stats["requests_delta"] or 0) - (stats["ratelimited_delta"] or 0)
    stats["valid_requests_delta"] = valid
    admitted = stats["admitted_delta"] or 0
    cache_hits = stats["l1_delta"] + stats["l2_delta"]
    hits = cache_hits + stats["flight_delta"]
    # 两个口径分开报：只算缓存命中的是保守值，加上 SingleFlight 合并的是"省下的算力"总值
    stats["interception_total"] = round(hits / valid, 4) if valid else ""
    stats["interception_cache_only"] = round(cache_hits / valid, 4) if valid else ""
    stats["interception_admitted"] = round(hits / admitted, 4) if admitted else ""
    if stats["ratelimited_delta"]:
        stats["rate_limited_nonzero"] = "true"
    stats.update(read_cpu_sample(sampler_path))
    if errors:
        stats["locust_error"] = errors[0]
    return stats


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8082")
    parser.add_argument("--model", choices=("l1", "l2", "mix80"), default="l1")
    parser.add_argument("--profile", default="perf", help="网关启动 profile，仅用于命名与归档")
    parser.add_argument("--steps", default="50,100,200,400,800,1200")
    parser.add_argument("--duration", type=int, default=60)
    parser.add_argument("--spawn", type=int, default=20)
    parser.add_argument("--workers", type=int, default=1,
                        help="locust 进程数；单进程约 260 QPS 就会自饱和，高并发档必须 >1")
    parser.add_argument("--tag", default="", help="附加到结果文件名上的实验标识")
    args = parser.parse_args()

    RESULTS.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    tag = f"{args.model}-{args.profile}-{stamp}" + (f"-{args.tag}" if args.tag else "")
    rows = []
    for users in [int(s) for s in args.steps.split(",") if s.strip()]:
        prefix = str(RESULTS / f"locust-{tag}-u{users}")
        print(f"== 并发 {users}（{args.workers} 进程），时长 {args.duration}s，流量模型 {args.model} ==",
              flush=True)
        row = run_step(args.base, users, args.spawn, args.duration, args.model, prefix, args.workers)
        row["profile"] = args.profile
        rows.append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)

    ladder = RESULTS / f"ladder-{tag}.csv"
    fields = ["users", "workers", "per_worker_users", "model", "profile",
              "request_count", "failure_count", "qps", "p50", "p95",
              "p99", "max", "requests_delta", "admitted_delta", "ratelimited_delta",
              "valid_requests_delta", "l1_delta", "l2_delta",
              "flight_delta", "llm_delta", "embed_remote_delta", "embed_cached_delta",
              "interception_total", "interception_cache_only", "interception_admitted",
              "rate_limited_nonzero",
              "cpu_mean_pct", "cpu_max_pct", "freemem_min_gb",
              "wall_s", "locust_error"]
    for _, column in TRAFFIC_COLUMNS:
        fields += [f"{column}_{metric}" for metric in
                   ("request_count", "failure_count", "qps", "p50", "p95", "p99")]
    with ladder.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    (RESULTS / f"env-{tag}.json").write_text(json.dumps({
        "run": tag, "trafficModel": args.model, "gatewayProfile": args.profile,
        "locustWorkers": args.workers, "stepsCsv": str(ladder.name),
        "durationPerStepS": args.duration, "steps": rows, "env": env_record(),
        "counters": COUNTERS,
        "note": ("perf profile 下调限流配额（见 application.yml perf 段）；"
                 "生成侧为 MockLLM 固定延迟，embedding 仍为真实 bge-m3"),
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"\n阶梯结果 {ladder.relative_to(REPO)}")
    print(f"环境记录 {(RESULTS / f'env-{tag}.json').relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
