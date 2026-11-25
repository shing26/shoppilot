"""仓库 .env 读取：进程环境（非空）优先，其次仓库根 .env。

2026-11-25 票 230 的 R16：验收脚本内置 dev 凭证，而 bootstrap_env.py 生成随机值，
fresh clone 跑 full 会在鉴权步骤整片红。所有 Python 验收脚本共用这一份口径。
"""
from __future__ import annotations

import os
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


def env_value(key: str, default: str = "") -> str:
    value = os.environ.get(key, "").strip()
    if value:
        return value
    env = REPO / ".env"
    if not env.exists():
        return default
    for line in env.read_text(encoding="utf-8").splitlines():
        text = line.strip()
        if not text or text.startswith("#") or "=" not in text:
            continue
        name, _, raw = text.partition("=")
        if name.strip() == key:
            return raw.strip().strip('"').strip("'")
    return default
