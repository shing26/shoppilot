#!/usr/bin/env python3
"""一条命令演示：起容器档（demo 档 + 真实 bge-m3）并跑三个场景。

用法：
  python scripts/demo.py --up          # 生成 .env、docker compose 构建并起栈、跑三场景
  python scripts/demo.py --prebuilt    # 用宿主机已构建的 jar 起运行时镜像（Docker Hub/Maven 网络不可用时）
  python scripts/demo.py --scenario cache
  python scripts/demo.py --down        # 停栈（保留卷）
  python scripts/demo.py --base-url http://127.0.0.1:8082

设计约束：
  - 只用标准库；Windows / macOS / Linux 同一份；
  - 容器档默认 SHOPPILOT_LLM_MODE=demo（意图感知的确定性答案），只要求宿主 Ollama 的 bge-m3；
  - 认证走 /auth/login（容器档 mock-token 不注册），账号由 .env 的
    SHOPPILOT_IDENTITY_DEMO_PASSWORD 播种；
  - 场景断言沿用 scripts/demo.ps1 的三条，不另造一套口径。
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = ROOT / ".env"


class DemoError(RuntimeError):
    pass


def env_values() -> dict[str, str]:
    values: dict[str, str] = {}
    if not ENV_FILE.exists():
        raise DemoError("missing .env; run python scripts/bootstrap_env.py")
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, _, value = stripped.partition("=")
        values[key.strip()] = value.strip()
    return values


def http(method: str, path: str, body: dict | None = None, token: str = "", ops: str = "",
         timeout: float = 120.0) -> tuple[int, dict | list | str]:
    url = path if path.startswith("http") else BASE_URL + path
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    if ops:
        request.add_header("X-Ops-Token", ops)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            status = response.status
    except urllib.error.HTTPError as failure:
        raw = failure.read().decode("utf-8", errors="replace")
        status = failure.code
    try:
        parsed: dict | list | str = json.loads(raw)
    except json.JSONDecodeError:
        parsed = raw
    return status, parsed


def login(tenant: str, username: str, password: str) -> str:
    status, body = http("POST", "/auth/login",
                        {"tenantId": tenant, "username": username, "password": password})
    if status != 200 or not isinstance(body, dict) or not body.get("token"):
        raise DemoError(f"login failed tenant={tenant} user={username} status={status} body={body}")
    return body["token"]


def chat(token: str, query: str, conversation: str) -> dict:
    status, body = http("POST", "/api/v1/support/chat",
                        {"query": query, "idempotencyToken": conversation},
                        token=token)
    if status != 200 or not isinstance(body, dict):
        raise DemoError(f"chat failed status={status} body={body}")
    return body


def metric(name: str) -> float:
    status, body = http("GET", "/actuator/metrics/" + name)
    if status != 200 or not isinstance(body, dict):
        return -1.0
    for measurement in body.get("measurements", []):
        if measurement.get("statistic") == "COUNT":
            return float(measurement.get("value", 0))
    return -1.0


def require_tools() -> None:
    missing = [tool for tool in ("docker", "ollama") if shutil.which(tool) is None]
    if missing:
        raise DemoError("missing tools: " + ", ".join(missing)
                        + "（Docker 与 Ollama 是演示前置；见 README 快速开始）")
    result = subprocess.run(["ollama", "list"], capture_output=True, text=True, timeout=30)
    if result.returncode != 0:
        raise DemoError("ollama 服务不可用：先启动 Ollama")
    if "bge-m3" not in result.stdout:
        raise DemoError("缺 embedding 模型：先执行 ollama pull bge-m3（约 1.2GB）")


def wait_ready(seconds: int = 240) -> None:
    deadline = time.time() + seconds
    last = ""
    while time.time() < deadline:
        try:
            status, body = http("GET", "/actuator/health", timeout=5)
            last = f"status={status} body={body}"
            if status == 200 and isinstance(body, dict) and body.get("status") == "UP":
                return
        except Exception as failure:  # noqa: BLE001 - 就绪前任何异常都等价于"还没好"
            last = str(failure)
        time.sleep(3)
    raise DemoError(f"gateway not ready in {seconds}s (last={last})")


def wait_login_ready(seconds: int = 90) -> None:
    """compose --wait 只看健康检查；biz-mock 的 seed 可能还在跑，登录会先吃 502。"""
    password = env_values().get("SHOPPILOT_IDENTITY_DEMO_PASSWORD", "")
    deadline = time.time() + seconds
    last = ""
    while time.time() < deadline:
        try:
            login("T001", "buyer", password)
            return
        except DemoError as failure:
            last = str(failure)
        time.sleep(2)
    raise DemoError(f"login not ready in {seconds}s (last={last})")


def flush_cache(buyer: str, ops: str) -> None:
    status, body = http("POST", "/api/v1/support/ops/cache/flush", token=buyer, ops=ops)
    if status != 200:
        raise DemoError(f"cache flush failed status={status} body={body}")


def demo_cache(buyer: str, ops: str) -> None:
    print("\n== 场景 1：缓存拦截（热点不进模型） ==")
    flush_cache(buyer, ops)
    before = metric("shoppilot_llm_calls_total")
    first = chat(buyer, "发什么快递", "demo-cache-a-" + str(time.time_ns()))
    middle = metric("shoppilot_llm_calls_total")
    print(f"  第一次 cache={first.get('cacheLayer')} 模型增量={middle - before:.0f}")

    # 缓存写回是异步的，且 CPU-only bge-m3 下向量化可能慢：轮询到可见再断言，
    # 不要把“写回还没落”误报成“缓存没工作”。
    hit = None
    deadline = time.time() + 30
    while time.time() < deadline:
        candidate = chat(buyer, "发什么快递", "demo-cache-probe-" + str(time.time_ns()))
        if candidate.get("cacheLayer") in ("L1", "L2"):
            hit = candidate
            break
        time.sleep(2)
    if hit is None:
        raise DemoError("30s 内没有看到缓存命中（检查 embedding 与写回）")

    before_hit = metric("shoppilot_llm_calls_total")
    final = chat(buyer, "发什么快递", "demo-cache-final-" + str(time.time_ns()))
    after_hit = metric("shoppilot_llm_calls_total")
    print(f"  命中 cache={hit.get('cacheLayer')}；终检 cache={final.get('cacheLayer')} 模型增量={after_hit - before_hit:.0f}")
    if final.get("cacheLayer") not in ("L1", "L2"):
        raise DemoError("终检未命中缓存")
    if after_hit - before_hit != 0:
        raise DemoError("命中路径仍然调用了模型")
    print("  PASS：写回可见后命中，模型调用增量为 0")


def plan_status(result: dict, tool: str = "queryLogistics") -> str | None:
    for step in result.get("plan") or []:
        if step.get("tool") == tool:
            return str(step.get("status"))
    return None


def demo_isolation(buyer_t1: str, buyer_t2: str) -> None:
    print("\n== 场景 2：串号防线（跨店查不到） ==")
    query = "查一下订单 90001 的物流"
    own = chat(buyer_t1, query, "demo-own-" + str(time.time_ns()))
    cross = chat(buyer_t2, query, "demo-cross-" + str(time.time_ns()))
    own_status = plan_status(own)
    cross_status = plan_status(cross)
    # perf/MockLLM 的正文是固定模板，不能拿 answer 文本判隔离；plan 里的工具结果才是真实业务面。
    print(f"  T001 queryLogistics={own_status}  fallback={own.get('fallbackReason')}")
    print(f"  T002 queryLogistics={cross_status}  fallback={cross.get('fallbackReason')}")
    if own_status == "NOT_FOUND":
        raise DemoError("T001 自己店里的 90001 不应 NOT_FOUND")
    if cross_status != "NOT_FOUND":
        raise DemoError(f"T002 看到了 T001 的订单：plan={cross.get('plan')}")
    status, _ = http("POST", "/api/v1/support/chat",
                     {"query": query}, token="forged.token.not.real")
    if status != 401:
        raise DemoError(f"伪造 token 未被拒：status={status}")
    print("  PASS：T002 对 T001 的订单得到 NOT_FOUND；伪造 token 401")


def demo_fallback(buyer: str, ops: str) -> None:
    print("\n== 场景 3：降级转人工（模型挂了不是 500） ==")
    # 用冷缓存 + LLM 故障注入，不依赖 MockLLM 的工具循环（perf 档下轨迹不可控）。
    flush_cache(buyer, ops)
    status, body = http("PUT", "/api/v1/support/ops/llm-fault",
                        {"mode": "timeout"}, token=buyer, ops=ops)
    if status != 200:
        raise DemoError(f"llm fault injection failed status={status} body={body}")
    try:
        result = chat(buyer, "发什么快递", "demo-fallback-" + str(time.time_ns()))
        reason = result.get("fallbackReason")
        ticket = result.get("ticketId")
        print(f"  fallbackReason={reason} ticketId={ticket}")
        if reason != "LLM_TIMEOUT":
            raise DemoError(f"没有拿到 LLM_TIMEOUT 降级：{reason}")
        if not ticket:
            raise DemoError("降级没有落可查工单")
        print("  PASS：LLM_TIMEOUT 降级原因与可查工单都在")
    finally:
        http("PUT", "/api/v1/support/ops/llm-fault", {"mode": "none"},
             token=buyer, ops=ops)


def run_scenarios(which: str) -> None:
    values = env_values()
    password = values.get("SHOPPILOT_IDENTITY_DEMO_PASSWORD", "")
    ops = values.get("SHOPPILOT_OPS_TOKEN", "")
    if not password or not ops:
        raise DemoError("missing SHOPPILOT_IDENTITY_DEMO_PASSWORD / SHOPPILOT_OPS_TOKEN; "
                        "run python scripts/bootstrap_env.py --force")
    buyer_t1 = login("T001", "buyer", password)
    if which in ("all", "cache"):
        demo_cache(buyer_t1, ops)
    if which in ("all", "isolation"):
        buyer_t2 = login("T002", "buyer", password)
        demo_isolation(buyer_t1, buyer_t2)
    if which in ("all", "fallback"):
        demo_fallback(buyer_t1, ops)


def up() -> None:
    # --force 只替换占位符/缺失项；已有真实值会保留，所以对已配置的 .env 也安全。
    subprocess.run([sys.executable, str(ROOT / "scripts" / "bootstrap_env.py"), "--force"], check=True)
    require_tools()
    print("starting docker compose --profile full (first build may take 5-15 min) ...")
    try:
        subprocess.run(["docker", "compose", "--profile", "full", "up", "-d", "--build", "--wait"],
                       check=True, cwd=ROOT)
    except subprocess.CalledProcessError as failure:
        raise DemoError(
            "docker compose up/build 失败。若是 Docker Hub 401/限流，先 docker login 或改用本机 JVM 路径"
            "（README 快速开始；本机 JVM 下可用 --scenario 直接跑）。") from failure
    wait_ready()
    wait_login_ready()
    run_scenarios("all")


def prebuilt() -> None:
    """Docker Hub/Maven 网络不可用时的降级：宿主机 jar + JRE 运行时镜像，不走 Dockerfile 的 Maven stage。"""
    subprocess.run([sys.executable, str(ROOT / "scripts" / "bootstrap_env.py"), "--force"], check=True)
    require_tools()
    jars = {
        "gateway": ROOT / "shoppilot-gateway/target/shoppilot-gateway-0.1.0-SNAPSHOT.jar",
        "biz-mock": ROOT / "shoppilot-biz-mock/target/shoppilot-biz-mock-0.1.0-SNAPSHOT.jar",
        "ticket": ROOT / "shoppilot-ticket/target/shoppilot-ticket-0.1.0-SNAPSHOT.jar",
    }
    missing = [name for name, jar in jars.items() if not jar.exists()]
    if missing:
        raise DemoError("missing jars: " + ", ".join(missing)
                        + "；先运行 mvnw -B -ntp -DskipTests package")
    for name, jar in jars.items():
        stage = ROOT / "logs" / "runtime" / name
        stage.mkdir(parents=True, exist_ok=True)
        shutil.copy2(jar, stage / "app.jar")
        subprocess.run(["docker", "build", "-f", str(ROOT / "Dockerfile.runtime"),
                        "-t", f"shoppilot/{name}:local", str(stage)], check=True)
    print("starting docker compose --profile full --no-build ...")
    try:
        subprocess.run(["docker", "compose", "--profile", "full", "up", "-d", "--no-build", "--wait"],
                       check=True, cwd=ROOT)
    except subprocess.CalledProcessError as failure:
        raise DemoError("docker compose up 失败；看 docker compose logs 与 RUNBOOK 的容器排障表") from failure
    wait_ready()
    wait_login_ready()
    run_scenarios("all")


def down() -> None:
    subprocess.run(["docker", "compose", "--profile", "full", "down"], check=True, cwd=ROOT)


BASE_URL = "http://127.0.0.1:8082"


def main(argv: list[str]) -> int:
    global BASE_URL
    parser = argparse.ArgumentParser()
    parser.add_argument("--up", action="store_true")
    parser.add_argument("--prebuilt", action="store_true")
    parser.add_argument("--down", action="store_true")
    parser.add_argument("--scenario", choices=("cache", "isolation", "fallback", "all"), default="all")
    parser.add_argument("--base-url", default="http://127.0.0.1:8082")
    args = parser.parse_args(argv)
    BASE_URL = args.base_url.rstrip("/")
    try:
        if args.down:
            down()
        elif args.prebuilt:
            prebuilt()
        elif args.up:
            up()
        else:
            run_scenarios(args.scenario)
    except DemoError as failure:
        print(f"DEMO FAILED: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
