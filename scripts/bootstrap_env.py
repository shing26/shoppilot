#!/usr/bin/env python3
"""生成/检查 .env：把 .env.example 的占位符换成随机值。

为什么需要它：
  - 干净克隆没有 .env；compose 的 postgres 需要 SHOPPILOT_DB_PASSWORD；
  - .env.example 的 replace-with-* 不是"已覆盖"，但 PostureGuard 只认仓库默认值，
    照抄模板会以 0.0.0.0 起容器并放行公开令牌（评审 P0）。
  - 这里一次生成安全值，并让 --check 在占位符残留时响亮失败。

用法：
  python scripts/bootstrap_env.py                 # 无 .env 则生成；有则检查
  python scripts/bootstrap_env.py --check         # 只检查，不写
  python scripts/bootstrap_env.py --render out.env # 生成到指定文件，供 CI compose config
  python scripts/bootstrap_env.py --force         # 覆盖已有 .env（慎用）

只用标准库。不读取、不打印任何已有密钥值。
"""
from __future__ import annotations

import argparse
import secrets
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_ENV = REPO / ".env"
DEFAULT_EXAMPLE = REPO / ".env.example"

# 需要随机化的键：出现占位符就替换/报错。
SECRET_KEYS = (
    "SHOPPILOT_DB_PASSWORD",
    "SHOPPILOT_JWT_SECRET",
    "SHOPPILOT_INTERNAL_TOKEN",
    "SHOPPILOT_OPS_TOKEN",
    "SHOPPILOT_IDENTITY_DEMO_PASSWORD",
)
PLACEHOLDERS = ("replace-with-", "sk-replace-me")
# 演示默认：MockLLM，只要求宿主 Ollama 的 bge-m3；真模型用 .env 覆盖。
DEMO_LLM_MODE = "perf"


def _is_placeholder(value: str) -> bool:
    return any(p in value for p in PLACEHOLDERS)


def _parse(text: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, _, value = stripped.partition("=")
        values[key.strip()] = value.strip()
    return values


def _replace_values(text: str, replacements: dict[str, str]) -> str:
    out: list[str] = []
    seen: set[str] = set()
    for line in text.splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#") and "=" in stripped:
            key = stripped.partition("=")[0].strip()
            if key in replacements:
                out.append(f"{key}={replacements[key]}")
                seen.add(key)
                continue
        out.append(line)
    for key, value in replacements.items():
        if key not in seen:
            out.append(f"{key}={value}")
    return "\n".join(out).rstrip() + "\n"


def _generated(existing: dict[str, str]) -> dict[str, str]:
    """已有非占位符值保留；占位符/缺失才生成。"""
    gen: dict[str, str] = {}
    for key in SECRET_KEYS:
        current = existing.get(key, "")
        if current and not _is_placeholder(current):
            continue
        if key == "SHOPPILOT_JWT_SECRET":
            gen[key] = secrets.token_urlsafe(32)
        elif key == "SHOPPILOT_IDENTITY_DEMO_PASSWORD":
            gen[key] = secrets.token_urlsafe(12)
        elif key == "SHOPPILOT_DB_PASSWORD":
            gen[key] = secrets.token_urlsafe(24)
        else:
            gen[key] = secrets.token_urlsafe(24)
    # 只有显式 perf 时才写；已有 dev/local 的用户不被覆盖。
    if existing.get("SHOPPILOT_LLM_MODE", "").strip() in ("", "local"):
        gen["SHOPPILOT_LLM_MODE"] = DEMO_LLM_MODE
    return gen


def _write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {path} (keys={len(_parse(text))})")


def _check(path: Path) -> int:
    if not path.exists():
        print(f"missing {path}；先运行 python scripts/bootstrap_env.py")
        return 1
    values = _parse(path.read_text(encoding="utf-8"))
    bad = [k for k in SECRET_KEYS if _is_placeholder(values.get(k, ""))]
    if bad:
        print("placeholder values still present: " + ", ".join(bad))
        print("run python scripts/bootstrap_env.py --force (or edit .env manually)")
        return 1
    print(f"ok {path} (no placeholders in {len(SECRET_KEYS)} secret keys)")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", type=Path, default=DEFAULT_ENV)
    parser.add_argument("--example", type=Path, default=DEFAULT_EXAMPLE)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--render", type=Path)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args(argv)

    if args.check:
        return _check(args.env)

    if not args.example.exists():
        print(f"missing template {args.example}")
        return 1
    template = args.example.read_text(encoding="utf-8")

    target = args.render or args.env
    if target.exists() and not args.render and not args.force:
        existing = _parse(target.read_text(encoding="utf-8"))
        missing = [k for k in SECRET_KEYS if not existing.get(k) or _is_placeholder(existing.get(k, ""))]
        if missing:
            print("existing .env has placeholders/missing: " + ", ".join(missing))
            print("run --force to regenerate (existing non-placeholder values are kept)")
            return 1
        return 0

    existing_text = target.read_text(encoding="utf-8") if target.exists() else template
    existing = _parse(existing_text)
    replacements = _generated(existing)
    # 关键：已有 .env 时以**它**为基底，不能拿模板重渲染——否则真实值会被模板占位符覆盖，
    # 且非 SECRET_KEYS 的配置（LLM 模式、端口等）会整段丢失。--force 只改占位符/缺失项。
    rendered = _replace_values(existing_text, replacements)
    _write(target, rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
