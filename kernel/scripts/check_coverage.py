#!/usr/bin/env python3
"""内核覆盖率棘轮：读两个模块的 JaCoCo 产物，按模块分别比 LINE 门槛。

口径与根仓 `scripts/check_coverage.py` 对齐：LINE 设闸、BRANCH 只报；
门槛 = 首次实测值向下取整再留 1.0pp 余量。缺产物不是跳过，而是失败。

0 token、无网络、只用标准库。`--report` 只打印不判定。
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# 2026-11-26 首次实测（票 232）：
# contract 84.53% / shopify 56.35%。门槛 = floor(实测) - 1.0。
LINE_FLOORS = {
    "shoppilot-commerce-contract": 83.0,
    "shoppilot-commerce-shopify": 55.0,
}

REPORT_REL = Path("target") / "site" / "jacoco" / "jacoco.xml"


def counters(path: Path) -> dict[str, tuple[int, int]]:
    root = ET.parse(path).getroot()
    return {
        counter.get("type", ""): (
            int(counter.get("missed", "0")),
            int(counter.get("covered", "0")),
        )
        for counter in root.findall("counter")
    }


def percent(values: dict[str, tuple[int, int]], kind: str) -> float:
    missed, covered = values.get(kind, (0, 0))
    total = missed + covered
    return 100.0 * covered / total if total else 0.0


def main(argv: list[str]) -> int:
    report_only = "--report" in argv
    kernel = Path(__file__).resolve().parent.parent
    failures: list[str] = []

    for module, floor in sorted(LINE_FLOORS.items()):
        artifact = kernel / module / REPORT_REL
        if not artifact.is_file():
            failures.append(f"{module}: 找不到 {REPORT_REL}（先跑 mvnw -f kernel/pom.xml verify）")
            continue

        values = counters(artifact)
        line = percent(values, "LINE")
        branch = percent(values, "BRANCH")
        verdict = "OK"
        if not report_only and line + 1e-9 < floor:
            verdict = "BELOW"
            failures.append(f"{module}: LINE {line:.2f}% < 门槛 {floor:.2f}%")
        print(f"{module}: LINE {line:.2f}% / BRANCH {branch:.2f}% / 门槛 {floor:.2f}% -> {verdict}")

    if report_only:
        return 0
    if failures:
        for failure in failures:
            print(f"FAIL {failure}")
        return 1
    print("KERNEL COVERAGE OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
