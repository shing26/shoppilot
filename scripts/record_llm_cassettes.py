#!/usr/bin/env python3
"""录制真实模型 cassette（round48 票 201 / ADR 0083）。

录制本身在 Java 装配层完成（`LlmCassetteRecorder`，包住真实 `LlmClient`），本脚本只做
三件事：检查凭据、显式拉起录制、提示/执行基线换代。**不会**把 Mock 或 demo 输出写成
真实模型证据——录制类只接 `OpenAiCompatibleLlmClient`（dev）。

用法：
  python scripts/record_llm_cassettes.py            # 录制，然后打印下一步
  python scripts/record_llm_cassettes.py --accept   # 录制并更新 baseline.json（差异视为已复核）
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CASSETTES = REPO / "eval" / "llm-cassettes"


def read_env_value(name: str) -> str | None:
    if os.getenv(name):
        return os.environ[name]
    env_file = REPO / ".env"
    if not env_file.is_file():
        return None
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() == name:
            return value.strip().strip('"').strip("'")
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description="录制真实模型 cassette")
    parser.add_argument("--accept", action="store_true",
                        help="录制完成后更新 baseline.json；只在你复核过新回复后使用")
    args = parser.parse_args()

    if not read_env_value("SHOPPILOT_LLM_API_KEY"):
        print("FAIL 缺 SHOPPILOT_LLM_API_KEY（环境变量或 .env）；录制必须是真实模型，"
              "不接受 Mock/模板冒充。", file=sys.stderr)
        return 1

    mvnw = "mvnw.cmd" if os.name == "nt" else "./mvnw"
    env = dict(os.environ, SHOPPILOT_RECORD_CASSETTES="1")
    command = [str(REPO / mvnw), "-B", "-ntp", "-pl", "shoppilot-gateway", "-am",
               "-Dtest=LlmCassetteRecorder", "-Dsurefire.failIfNoSpecifiedTests=false", "test"]
    print("录制命令：", " ".join(command))
    result = subprocess.run(command, cwd=REPO, env=env)
    if result.returncode != 0:
        print("FAIL 录制失败（Java 侧输出见上）", file=sys.stderr)
        return result.returncode

    cassettes = sorted(CASSETTES.glob("*/*/*.json"))
    print(f"已录 {len(cassettes)} 张；目录 {CASSETTES}")
    if args.accept:
        return subprocess.run([sys.executable, str(REPO / "scripts" / "verify_llm_replay.py"),
                               "--update-baseline"], cwd=REPO).returncode
    print("下一步：人工复核新回复，然后显式运行 "
          "`python scripts/verify_llm_replay.py --update-baseline`（或本脚本加 --accept）。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
