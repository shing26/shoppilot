#!/usr/bin/env python3
"""round50：安全黑盒用例的活体跑器（判分 0 token，推理按次计费）。

跑的是**已经部署起来的那一个网关**，不 import 任何业务代码：
每条用例按步骤发真实 HTTP，用 `agent_eval_cases.score()` 判状态码 / 响应体 / 答案泄漏面。

纪律：
  - **正对照先行**：`control` 步骤失败时，同用例的攻击步骤不可解释，整条记 `SKIP_ENV`，
    不是 FAIL——否则一个没部署好的端点会被写成“越权被挡住了”。
  - **环境相关步骤**：`/auth/mock-token` 在回环档返回 200 是设计内行为（ADR 0029），记 SKIP_ENV。
  - **minted 令牌**：从 `SHOPPILOT_JWT_SECRET`（或 `docker exec` 读同值）现场签，绝不打印、绝不落盘。
  - **推理闸门**：任何 llm 步骤都要 `--include-llm` + `--llm-budget`；否则整条 SKIP_NO_LLM。
  - 判分本身不调模型；`inference_tokens` 只作成本记录。

用法：
    python scripts/run_security_eval.py                       # 只跑非 llm 用例（默认 0 费用）
    python scripts/run_security_eval.py --include-llm --llm-budget 12
    python scripts/run_security_eval.py --category role --split all

输出：`eval/results/security-eval-<runid>.csv` / `.jsonl`（逐步原文）/ `.meta.json`。
有 FAIL 退出码 1；只有 SKIP 退出码 0，原因写进 meta。
"""

from __future__ import annotations

import argparse
import base64
import csv
import datetime as dt
import hashlib
import hmac
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCRIPTS = REPO / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import agent_eval_cases as shared  # noqa: E402

RESULTS = REPO / "eval" / "results"
FORGED = ("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
          "eyJzdWIiOiJDOTk5IiwidGlkIjoiVDAwMSIsImNpZCI6IkM5OTkiLCJleHAiOjQxMDI0NDQ4MDB9."
          "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
DEMO_ROLE = {"buyer": "buyer", "agent": "agent", "admin": "admin"}


def post(url, payload=None, token=None, headers=None, timeout=240, raw_body=None, method=None):
    if raw_body is not None:
        body = raw_body.encode("utf-8")
    else:
        body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    verb = method or ("POST" if body is not None else "GET")
    request = urllib.request.Request(url, data=body, method=verb)
    if body is not None:
        request.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


def stream_abort(url, payload, token, conversation, timeout=240):
    """读到首帧就断开；返回 (HTTP 状态, 首块文本)。断开本身不记为错误。"""
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=body, method="POST")
    request.add_header("Content-Type", "application/json; charset=utf-8")
    request.add_header("Accept", "text/event-stream")
    request.add_header("X-Conversation-Id", conversation)
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        response = urllib.request.urlopen(request, timeout=timeout)
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")
    status = response.status
    try:
        chunk = response.read(64).decode("utf-8", "replace")
    except Exception:  # noqa: BLE001 - 首帧后断开是预期行为
        chunk = ""
    finally:
        response.close()
    return status, chunk


def demo_token(base, tenant, role):
    status, raw = post(base + "/auth/demo-login", {"tenantId": tenant, "role": role}, timeout=30)
    try:
        payload = json.loads(raw or "{}")
    except json.JSONDecodeError:
        payload = {}
    if status != 200 or not payload.get("token"):
        raise RuntimeError(f"demo-login {tenant}/{role} 失败：HTTP {status}")
    return payload["token"]


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def mint(secret: str, tenant: str, customer: str, ttl: int = 1800) -> str:
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {"sub": customer, "tid": tenant, "cid": customer, "iat": now, "exp": now + ttl}
    signing = (b64url(json.dumps(header, separators=(",", ":")).encode())
               + "." + b64url(json.dumps(payload, separators=(",", ":")).encode()))
    signature = hmac.new(secret.encode(), signing.encode(), hashlib.sha256).digest()
    return f"{signing}.{b64url(signature)}"


def jwt_secret():
    """先读环境变量，再读运行中的网关容器；两个来源都不打印。"""
    if os.environ.get("SHOPPILOT_JWT_SECRET"):
        return os.environ["SHOPPILOT_JWT_SECRET"]
    try:
        done = subprocess.run(["docker", "exec", "shoppilot-gateway-1", "printenv", "SHOPPILOT_JWT_SECRET"],
                              capture_output=True, text=True, timeout=20)
    except Exception:  # noqa: BLE001
        return ""
    return done.stdout.strip() if done.returncode == 0 else ""


def run_scoped(value, run_id):
    """把 {{RUN_ID}} 换成本次运行号：幂等令牌必须每轮唯一，否则第二轮起全是重放。"""
    if isinstance(value, str):
        return value.replace("{{RUN_ID}}", run_id)
    if isinstance(value, list):
        return [run_scoped(item, run_id) for item in value]
    if isinstance(value, dict):
        return {key: run_scoped(item, run_id) for key, item in value.items()}
    return value


def env_skipped(step, obs):
    """步骤声明的“这个环境不可解释”条件是否命中。只放宽环境归因，不碰断言。"""
    condition = step.get("env_skip_if") or {}
    if not condition:
        return False
    for tool, wanted in (condition.get("plan_status") or {}).items():
        got = next((entry.get("status") for entry in obs["plan"] if entry.get("tool") == tool), None)
        if got != wanted:
            return False
    if "status" in condition:
        allowed = condition["status"] if isinstance(condition["status"], list) else [condition["status"]]
        if obs["http_status"] not in allowed:
            return False
    return bool(condition.get("reason"))


def resolve(step, token, variables, base, conversation, timeout, llm_enabled, run_id):
    path = step["path"]
    for name, value in variables.items():
        path = path.replace("{" + name + "}", str(value))
    url = base + path
    method = step["method"]
    if step.get("llm") and not llm_enabled:
        return None
    if step.get("stream"):
        payload = run_scoped(step.get("body") or {}, run_id)
        return stream_abort(url, payload, token, conversation, timeout)
    if method == "GET":
        request = urllib.request.Request(url, method="GET")
        if token:
            request.add_header("Authorization", "Bearer " + token)
        for key, value in (step.get("headers") or {}).items():
            request.add_header(key, value)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode("utf-8", "replace")
    headers = dict(step.get("headers") or {})
    if step.get("conversation"):
        headers["X-Conversation-Id"] = conversation
    body = run_scoped(step.get("body"), run_id)
    raw = run_scoped(step.get("body_raw"), run_id)
    return post(url, body, token, headers, timeout, raw, method=method)


def judge(step, status, raw):
    try:
        payload = json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        payload = {}
    if step.get("conversation") or step.get("stream") or "/chat" in step["path"]:
        obs = shared.observation_from_chat(payload if isinstance(payload, dict) else {}, status, raw)
        if step.get("stream"):
            # SSE 首块是 `data:` 帧，没有 ChatResponse 字段；只判状态码。
            obs = shared.observation_from_response(status, {}, raw)
    else:
        obs = shared.observation_from_response(status, payload if isinstance(payload, dict) else {}, raw)
    return shared.score(shared.security_step_case({"id": step.get("_case", "")}, step), obs), obs, payload


def main() -> int:
    parser = argparse.ArgumentParser(description="round50 安全黑盒跑器（判分 0 token）")
    parser.add_argument("--base", default="http://127.0.0.1:8082")
    parser.add_argument("--category", action="append", default=[], help="可重复；默认全部")
    parser.add_argument("--split", choices=["all", "dev", "holdout"], default="all")
    parser.add_argument("--only-ids", default="")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--include-llm", action="store_true")
    parser.add_argument("--llm-budget", type=int, default=0)
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--run-id", default=dt.datetime.now().strftime("%m%d%H%M%S"))
    parser.add_argument("--out-dir", default=str(RESULTS))
    args = parser.parse_args()

    selected = shared.load_cases("security")
    if args.category:
        selected = [case for case in selected if case.get("category") in set(args.category)]
    if args.split != "all":
        selected = [case for case in selected if case.get("split") == args.split]
    if args.only_ids:
        wanted = {part.strip() for part in args.only_ids.split(",") if part.strip()}
        selected = [case for case in selected if case.get("id") in wanted]
    selected.sort(key=lambda case: (case.get("priority", 99), case["id"]))
    if args.limit:
        selected = selected[:args.limit]

    problems = []
    for case in selected:
        problems += shared.selfcheck_security(case)
    if problems:
        print("结构/变异自检失败，未发任何请求：")
        for problem in problems:
            print("  - " + problem)
        return 1

    needs_minted = any(step["auth"].startswith("minted:")
                       for case in selected for step in case["steps"])
    secret = jwt_secret() if needs_minted else ""
    tokens: dict[str, str] = {}
    env_gaps: list[str] = []
    if needs_minted and not secret:
        env_gaps.append("minted 令牌不可用：没有 SHOPPILOT_JWT_SECRET，也读不到网关容器")

    def token_for(auth):
        if auth == "none":
            return ""
        if auth == "forged":
            return FORGED
        if auth.startswith("minted:"):
            _, tenant, customer = auth.split(":")
            if not secret:
                raise RuntimeError("缺签名密钥")
            return mint(secret, tenant, customer)
        role, tenant = auth.split(":")
        key = f"{role}:{tenant}"
        if key not in tokens:
            tokens[key] = demo_token(args.base, tenant, DEMO_ROLE[role])
        return tokens[key]

    rows, logs = [], []
    used_calls = 0
    used_tokens = 0
    for case in selected:
        llm_steps = [step for step in case["steps"] if step.get("llm")]
        case_result = {"id": case["id"], "category": case["category"], "split": case["split"],
                       "result": "PASS", "reasons": "", "steps": []}
        if llm_steps and not args.include_llm:
            case_result.update(result="SKIP_NO_LLM", reasons="含 llm 步骤，未加 --include-llm")
            rows.append({"case": case["id"], "step": "-", "category": case["category"],
                         "split": case["split"], "result": "SKIP_NO_LLM",
                         "reasons": case_result["reasons"], "status": 0, "body": ""})
            continue
        if llm_steps and used_calls + len(llm_steps) > args.llm_budget:
            case_result.update(result="SKIP_BUDGET",
                               reasons=f"预算 {args.llm_budget} 次已用 {used_calls}，还需 {len(llm_steps)}")
            rows.append({"case": case["id"], "step": "-", "category": case["category"],
                         "split": case["split"], "result": "SKIP_BUDGET",
                         "reasons": case_result["reasons"], "status": 0, "body": ""})
            continue

        variables: dict[str, str] = {}
        conversation = f"r50sec-{args.run_id}-{case['id'].lower()}"
        control_failed = False
        step_results = []
        for step in case["steps"]:
            step["_case"] = case["id"]
            try:
                token = token_for(step["auth"])
            except Exception as error:  # noqa: BLE001
                step_results.append((step["name"], "SKIP_ENV", f"取令牌失败：{error}", 0, ""))
                continue
            if step.get("llm"):
                used_calls += 1
            try:
                answered = resolve(step, token, variables, args.base, conversation, args.timeout,
                                   args.include_llm, args.run_id)
            except Exception as error:  # noqa: BLE001 - 连不上是环境，不是用例失败
                step_results.append((step["name"], "SKIP_ENV", f"请求失败：{error}", 0, ""))
                continue
            if answered is None:
                step_results.append((step["name"], "SKIP_NO_LLM", "llm 步骤未开启", 0, ""))
                continue
            status, raw = answered
            if "/auth/mock-token" in step["path"] and status == 200:
                step_results.append((step["name"], "SKIP_ENV",
                                     "回环档 mock-token 合法返回 200（ADR 0029），不记 FAIL", status, raw))
                continue
            if status == 429:
                step_results.append((step["name"], "SKIP_ENV", "命中准入限流（429），本轮观测不可解释",
                                     status, raw))
                continue
            verdict, obs, payload = judge(step, status, raw)
            used_tokens += obs["tokens"]
            result = "PASS" if verdict["ok"] else "FAIL"
            if result == "FAIL" and env_skipped(step, obs):
                # 断言没被放宽：只是这个环境下到不了被测分支（例：旧种子超出退款窗口）。
                step_results.append((step["name"], "SKIP_ENV",
                                     str((step.get("env_skip_if") or {}).get("reason")), status, raw))
                continue
            if step.get("control") and result == "FAIL":
                control_failed = True
                result = "CONTROL_FAILED"
            if step.get("save"):
                for variable, field in step["save"].items():
                    value = payload.get(field) if isinstance(payload, dict) else None
                    if value:
                        variables[variable] = value
            step_results.append((step["name"], result, "；".join(verdict["reasons"]), status, raw))

        if control_failed:
            case_result["result"] = "SKIP_ENV"
            case_result["reasons"] = "正对照未成立，攻击步骤不可解释"
        elif any(item[1] == "FAIL" for item in step_results):
            case_result["result"] = "FAIL"
        elif any(item[1] in ("SKIP_ENV", "SKIP_NO_LLM") for item in step_results):
            case_result["result"] = "SKIP_ENV"
            case_result["reasons"] = "；".join(item[2] for item in step_results if item[1] == "SKIP_ENV")
        for name, result, reasons, status, raw in step_results:
            case_result["steps"].append({"name": name, "result": result, "reasons": reasons,
                                         "status": status})
            rows.append({"case": case["id"], "step": name, "category": case["category"],
                         "split": case["split"], "result": result, "reasons": reasons,
                         "status": status, "body": raw[:300]})
            logs.append({"case": case["id"], "step": name, "result": result, "reasons": reasons,
                         "http_status": status, "conversation": conversation, "body": raw})
        if not case_result["reasons"] and case_result["result"] == "FAIL":
            case_result["reasons"] = "；".join(item[2] for item in step_results if item[1] == "FAIL")
        rows.append({"case": case["id"], "step": "<case>", "category": case["category"],
                     "split": case["split"], "result": case_result["result"],
                     "reasons": case_result["reasons"], "status": 0, "body": ""})

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    stem = out_dir / f"security-eval-{args.run_id}"
    with stem.with_suffix(".csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["case", "step", "category", "split", "result",
                                                    "reasons", "status", "body"])
        writer.writeheader()
        writer.writerows(rows)
    with stem.with_suffix(".jsonl").open("w", encoding="utf-8", newline="\n") as handle:
        for entry in logs:
            handle.write(json.dumps(entry, ensure_ascii=False) + "\n")

    cases = [row for row in rows if row["step"] == "<case>"]
    counts = Counter(row["result"] for row in cases)
    meta = {
        "round": 50,
        "run_id": args.run_id,
        "started_at": dt.datetime.now().isoformat(timespec="seconds"),
        "base": args.base,
        "git_head": subprocess.run(["git", "-C", str(REPO), "rev-parse", "HEAD"], capture_output=True,
                                   text=True).stdout.strip(),
        "include_llm": args.include_llm,
        "llm_budget": args.llm_budget,
        "llm_calls": used_calls,
        "inference_tokens": used_tokens,
        "judge_tokens": 0,
        "counts": dict(counts),
        "by_category": {name: dict(Counter(row["result"] for row in cases if row["category"] == name))
                        for name in sorted({row["category"] for row in cases})},
        "by_split": {name: dict(Counter(row["result"] for row in cases if row["split"] == name))
                     for name in sorted({row["split"] for row in cases})},
        "env_gaps": env_gaps,
        "note": "正对照失败记 SKIP_ENV；mock-token 回环 200 记 SKIP_ENV；判分 0 token。",
    }
    stem.with_suffix(".meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n",
                                              encoding="utf-8", newline="\n")

    for row in cases:
        suffix = f"：{row['reasons']}" if row["reasons"] else ""
        print(f"{row['result']:14s} {row['case']}{suffix}")
    def shown(path):
        try:
            return path.relative_to(REPO)
        except ValueError:
            return path

    print(f"\n{shown(stem.with_suffix('.csv'))} / {shown(stem.with_suffix('.jsonl'))}"
          f" / {shown(stem.with_suffix('.meta.json'))}")
    print(f"结果：{dict(counts)}；推理 {used_calls} 次 / {used_tokens} token；判分 0 token")
    return 1 if counts.get("FAIL") else 0


if __name__ == "__main__":
    sys.exit(main())
