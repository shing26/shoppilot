#!/usr/bin/env python3
"""检查 README 与 docs/ 下 Markdown 的本地图片/相对链接是否存在。

只做本地文件存在性检查，不访问网络；外链、锚点与站点绝对路径跳过。
票 226 的验收命令之一：缺失目标返回非 0。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parent.parent
SCOPE = [Path("README.md")] + sorted(Path("docs").rglob("*.md"))

_FENCE = re.compile(r"^```.*?^```", re.MULTILINE | re.DOTALL)
_INLINE_CODE = re.compile(r"`[^`\n]*`")
_LINK = re.compile(
    r"!?\[[^\]]*\]\(\s*(<[^>]+>|[^)\s]+)(?:\s+['\"][^'\"]*['\"])?\s*\)"
)
_HTML_IMG = re.compile(r"<img\b[^>]*\bsrc\s*=\s*['\"]([^'\"]+)['\"]", re.IGNORECASE)
_SCHEME = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.-]*:")


def _targets(text: str):
    text = _FENCE.sub("", text)
    text = _INLINE_CODE.sub("", text)
    for match in _LINK.finditer(text):
        yield match.group(1).strip("<>")
    for match in _HTML_IMG.finditer(text):
        yield match.group(1).strip()


def _local_path(target: str) -> str | None:
    target = target.strip()
    if not target or target.startswith(("#", "//", "/")):
        return None
    if _SCHEME.match(target):
        return None
    path = unquote(target.split("#", 1)[0].split("?", 1)[0])
    return path or None


def main() -> int:
    files = [path for path in SCOPE if (ROOT / path).is_file()]
    missing: list[tuple[Path, str]] = []
    checked = 0
    for rel in files:
        text = (ROOT / rel).read_text(encoding="utf-8")
        for target in _targets(text):
            path = _local_path(target)
            if path is None:
                continue
            checked += 1
            resolved = (ROOT / rel.parent / path).resolve()
            if not resolved.exists():
                missing.append((rel, target))

    if missing:
        for rel, target in missing:
            print(f"MISSING {rel}: {target}")
        print(f"DOC LINKS FAILED: {len(missing)} missing / {checked} local links")
        return 1
    print(f"DOC LINKS OK files={len(files)} local_links={checked}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
