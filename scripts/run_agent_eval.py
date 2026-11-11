#!/usr/bin/env python3
"""round50：回归 / 边界用例集的活体跑器（判分 0 token，推理按次计费）。

默认**不发任何聊天请求**：先跑结构 + 变异自检，然后逐条记 `SKIP_NO_LLM`。
本机 8082 是 dev 档（qwen-plus），每次聊天都产生真实费用，所以必须显式：

    python scripts/run_agent_eval.py --include-llm --llm-budget 20 --set edge --split dev

跑器只做三件事：
  ① 取演示令牌（`/auth/demo-login`），按用例的 tenant 建**每条用例独立**的会话号；
  ② 多轮用例先静默发送前置 turns，再判末轮响应；
  ③ 把响应装成 `agent_eval_cases.score()` 的形状，判分本身不调模型、不花钱。

输出：`eval/results/agent-eval-<runid>.csv`（逐条）、`.meta.json`（环境、预算、token 用量、
dev/holdout 分开的通过数、未覆盖项）。有 FAIL 时退出码 1；只有 SKIP 时退出码 0 并在 meta 里写明原因。
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCRIPTS = REPO / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import agent_eval_cases as shared  # noqa: E402

RESULTS = REPO / "eval" / "results"
TOKEN_ROLES = {"buyer", "agent", "admin"}


def request_json(base, method, path, payload=None, token=None, headers=None, timeout=240):
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(base + path, data=body, method=method)
    if body is not None:
        request.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
            status = response.status
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", "replace")
        status = error.code
    try:
        parsed = json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        parsed = None
    return status, parsed, raw


def demo_token(base, tenant, role="buyer"):
    status, payload, raw = request_json(base, "POST", "/auth/demo-login",
                                        {"tenantId": tenant, "role": role}, timeout=30)
    if status != 200 or not isinstance(payload, dict) or not payload.get("token"):
        raise RuntimeError(f"demo-login {tenant}/{role} 失败：HTTP {status} {raw[:200]}")
    return payload["token"]


def chat(base, token, conversation, query, timeout):
    headers = {"X-Conversation-Id": conversation}
    return request_json(base, "POST", "/api/v1/support/chat", {"query": query},
                        token=token, headers=headers, timeout=timeout)


def run_case(base, case, tokens, run_id, timeout):
    """返回 (result, reasons, observation, tokens_used)。异常不外抛，交给调用方归因。"""
    tenant = case.get("tenant")
    if tenant not in tokens:
        return "SKIP_ENV", [f"没有 {tenant} 的演示令牌"], None, 0
    conversation = f"r50-{run_id}-{case['id'].lower()}"
    used = 0
    for turn in case.get("turns") or []:
        status, payload, raw = chat(base, tokens[tenant], conversation, turn["query"], timeout)
        used += int((payload or {}).get("promptTokens") or 0) + int((payload or {}).get("completionTokens") or 0)
        if status != 200:
            return "SKIP_ENV", [f"前置轮 HTTP {status}：{raw[:120]}"], None, used
    status, payload, raw = chat(base, tokens[tenant], conversation, case["query"], timeout)
    used += int((payload or {}).get("promptTokens") or 0) + int((payload or {}).get("completionTokens") or 0)
    if status == 429:
        return "SKIP_ENV", ["命中准入限流（429），这一轮观测不可解释"], None, used
    if status != 200 or not isinstance(payload, dict):
        return "FAIL", [f"HTTP {status}：{raw[:200]}"], None, used
    obs = shared.observation_from_chat(payload, status, raw)
    verdict = shared.score(case, obs)
    return ("PASS" if verdict["ok"] else "FAIL"), verdict["reasons"], obs, used


def selfcheck(selected):
    problems = []
    for case in selected:
        problems += shared.selfcheck(case)
    return problems


def summarise(rows, key):
    table = {}
    for row in rows:
        bucket = table.setdefault(row[key] or "-", Counter())
        bucket[row["result"]] += 1
    return {name: dict(counts) for name, counts in sorted(table.items())}


def main() -> int:
    parser = argparse.ArgumentParser(description="round50 回归/边界活体跑器（判分 0 token）")
    parser.add_argument("--base", default="http://127.0.0.1:8082", help="网关地址")
    parser.add_argument("--set", choices=["both", "regression", "edge"], default="both")
    parser.add_argument("--split", choices=["all", "dev", "holdout"], default="all")
    parser.add_argument("--only-ids", default="", help="逗号分隔；只跑这些 id")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--include-llm", action="store_true", help="真的发聊天请求（产生推理费用）")
    parser.add_argument("--llm-budget", type=int, default=0, help="本次最多发起的聊天请求数（含前置轮）")
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--run-id", default=dt.datetime.now().strftime("%m%d%H%M%S"))
    parser.add_argument("--out-dir", default=str(RESULTS))
    args = parser.parse_args()

    selected = []
    if args.set in ("both", "regression"):
        selected += shared.load_cases("regression")
    if args.set in ("both", "edge"):
        selected += shared.load_cases("edge")
    if args.split != "all":
        selected = [case for case in selected if case.get("split") == args.split]
    if args.only_ids:
        wanted = {part.strip() for part in args.only_ids.split(",") if part.strip()}
        selected = [case for case in selected if case.get("id") in wanted]
    if args.limit:
        selected = selected[:args.limit]

    problems = selfcheck(selected)
    if problems:
        print("结构/变异自检失败，未发任何请求：")
        for problem in problems:
            print("  - " + problem)
        return 1

    print(f"用例 {len(selected)} 条；判分 0 token；"
          f"{'推理已开启，预算 ' + str(args.llm_budget) + ' 次' if args.include_llm else '默认不推理'}")

    tokens = {}
    env_gaps = []
    rows = []
    used_calls = 0
    used_tokens = 0

    if args.include_llm and args.llm_budget > 0:
        for tenant in sorted({case.get("tenant") for case in selected}):
            try:
                tokens[tenant] = demo_token(args.base, tenant)
            except Exception as error:  # noqa: BLE001 - 环境不可达要记成 SKIP，不是用例失败
                env_gaps.append(str(error))

    for case in selected:
        if not args.include_llm:
            rows.append({"set": "regression" if case["id"].startswith("REG-") else "edge",
                         "id": case["id"], "split": case["split"], "category": case.get("kind") or case.get("category"),
                         "result": "SKIP_NO_LLM", "reasons": "未加 --include-llm", "tokens": 0,
                         "intent": "", "answer": ""})
            continue
        if args.llm_budget <= 0:
            rows.append({"set": "regression" if case["id"].startswith("REG-") else "edge",
                         "id": case["id"], "split": case["split"], "category": case.get("kind") or case.get("category"),
                         "result": "SKIP_BUDGET", "reasons": "--include-llm 需要同时给 --llm-budget > 0",
                         "tokens": 0, "intent": "", "answer": ""})
            continue
        if not tokens:
            rows.append({"set": "regression" if case["id"].startswith("REG-") else "edge",
                         "id": case["id"], "split": case["split"], "category": case.get("kind") or case.get("category"),
                         "result": "SKIP_ENV", "reasons": "；".join(env_gaps) or "没有可用令牌", "tokens": 0,
                         "intent": "", "answer": ""})
            continue
        calls = 1 + len(case.get("turns") or [])
        if used_calls + calls > args.llm_budget:
            rows.append({"set": "regression" if case["id"].startswith("REG-") else "edge",
                         "id": case["id"], "split": case["split"], "category": case.get("kind") or case.get("category"),
                         "result": "SKIP_BUDGET", "reasons": f"预算 {args.llm_budget} 次已用 {used_calls}",
                         "tokens": 0, "intent": "", "answer": ""})
            continue
        try:
            result, reasons, obs, case_tokens = run_case(args.base, case, tokens, args.run_id, args.timeout)
        except Exception as error:  # noqa: BLE001
            result, reasons, obs, case_tokens = "SKIP_ENV", [f"请求失败：{error}"], None, 0
        used_calls += calls
        used_tokens += case_tokens
        rows.append({"set": "regression" if case["id"].startswith("REG-") else "edge",
                     "id": case["id"], "split": case["split"], "category": case.get("kind") or case.get("category"),
                     "result": result, "reasons": "；".join(reasons), "tokens": case_tokens,
                     "intent": (obs or {}).get("intent", ""), "answer": ((obs or {}).get("answer") or "")[:160]})

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    stem = out_dir / f"agent-eval-{args.run_id}"
    with stem.with_suffix(".csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["set", "id", "split", "category", "result", "reasons",
                                                    "tokens", "intent", "answer"])
        writer.writeheader()
        writer.writerows(rows)

    counts = Counter(row["result"] for row in rows)
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
        "by_split": summarise(rows, "split"),
        "by_category": summarise(rows, "category"),
        "env_gaps": env_gaps,
        "note": "判分确定性、0 token；inference_tokens 是网关回报的 prompt+completion，只作成本记录，不参与判定。",
    }
    stem.with_suffix(".meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n",
                                              encoding="utf-8", newline="\n")

    for row in rows:
        suffix = f"：{row['reasons']}" if row["reasons"] else ""
        print(f"{row['result']:12s} {row['id']}{suffix}")
    def shown(path):
        try:
            return path.relative_to(REPO)
        except ValueError:
            return path

    print(f"\n{shown(stem.with_suffix('.csv'))} / {shown(stem.with_suffix('.meta.json'))}")
    print(f"结果：{dict(counts)}；推理 {used_calls} 次 / {used_tokens} token；判分 0 token")
    return 1 if counts.get("FAIL") else 0


if __name__ == "__main__":
    sys.exit(main())
