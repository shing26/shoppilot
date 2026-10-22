#!/usr/bin/env python3
"""生成 docs/STATUS.md 的“当前状态”块：测试数、覆盖率、HEAD、指标口径。

为什么要有它：README / DELIVERY / EVIDENCE / design-walkthrough 里的 596 / 591 / 505
已经漂移过；手抄数字迟早再漂。这里从 surefire/jacoco 产物直接读，--check 在 CI 里红。

用法：
  python scripts/gen_status.py            # 写 docs/STATUS.md
  python scripts/gen_status.py --check    # 与仓内文件比对，不一致退出 1
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATUS = ROOT / "docs" / "STATUS.md"
METRICS = ROOT / "docs" / "current-metrics.json"
MODULES = ("shoppilot-tool-api", "shoppilot-biz-mock", "shoppilot-gateway", "shoppilot-ticket")
BEGIN = "<!-- STATUS:BEGIN -->"
END = "<!-- STATUS:END -->"
# JaCoCo 的行覆盖在不同 OS/JDK/测试顺序下会有零点几个百分点的漂移；
# 状态文件是快照，不是门禁。覆盖率门槛仍由 check_coverage.py 单独守。
COVERAGE_TOLERANCE = 1.5


def git(*args: str) -> str:
    try:
        return subprocess.check_output(["git", *args], cwd=ROOT, text=True, stderr=subprocess.DEVNULL).strip()
    except Exception:  # noqa: BLE001 - 没有 git 时仍允许本地生成
        return "unknown"


def surefire() -> tuple[int, int, int, int, int]:
    tests = failures = errors = skipped = suites = 0
    for module in MODULES:
        for path in (ROOT / module / "target" / "surefire-reports").glob("TEST-*.xml"):
            root = ET.parse(path).getroot()
            tests += int(root.get("tests", "0"))
            failures += int(root.get("failures", "0"))
            errors += int(root.get("errors", "0"))
            skipped += int(root.get("skipped", "0"))
            suites += 1
    return tests, failures, errors, skipped, suites


def jacoco() -> dict[str, float]:
    result: dict[str, float] = {}
    for module in MODULES:
        path = ROOT / module / "target" / "site" / "jacoco" / "jacoco.xml"
        if not path.exists():
            continue
        root = ET.parse(path).getroot()
        line = next((c for c in root.findall("counter") if c.get("type") == "LINE"), None)
        if line is None:
            continue
        missed = int(line.get("missed", "0"))
        covered = int(line.get("covered", "0"))
        result[module] = 100.0 * covered / (missed + covered) if missed + covered else 0.0
    return result


def metrics() -> list[dict]:
    if not METRICS.exists():
        return []
    return json.loads(METRICS.read_text(encoding="utf-8")).get("metrics", [])


def render() -> str:
    tests, failures, errors, skipped, suites = surefire()
    coverage = jacoco()
    lines = [
        BEGIN,
        "> 自动生成，勿手改。命令：`python scripts/gen_status.py`；CI 用 `--check` 防漂移。",
        "",
        f"- JVM：**{tests}** tests / {failures} failures / {errors} errors / {skipped} skipped（{suites} suites）",
        "- 覆盖率（LINE）：" + " / ".join(
            f"{name.replace('shoppilot-', '')} **{value:.2f}%**" for name, value in coverage.items()),
        "- CI：[ci-subset](https://github.com/shing26/shoppilot/actions/workflows/ci-subset.yml)（badge 为准）",
        "",
        "| 指标 | 当前值 | 判据 | 状态 | 条件 / 证据 |",
        "| --- | --- | --- | --- | --- |",
    ]
    for item in metrics():
        lines.append("| {name} | {value} | {target} | {status} | {condition} |".format(**item))
    lines.append(END)
    return "\n".join(lines) + "\n"


def strip_coverage(block: str) -> str:
    return "\n".join(line for line in block.splitlines() if not line.startswith("- 覆盖率（LINE）："))


def coverage_pairs(block: str) -> dict[str, float]:
    line = next((item for item in block.splitlines() if item.startswith("- 覆盖率（LINE）：")), "")
    return {name: float(value) for name, value in re.findall(r"([a-z0-9-]+) \*\*([0-9.]+)%\*\*", line)}


def test_count(block: str) -> int | None:
    match = re.search(r"JVM：\*\*(\d+)\*\* tests", block)
    return int(match.group(1)) if match else None


def coverage_close(current: str, expected: str) -> bool:
    actual = coverage_pairs(current)
    want = coverage_pairs(expected)
    if actual.keys() != want.keys() or not want:
        return False
    return all(abs(actual[name] - value) <= COVERAGE_TOLERANCE for name, value in want.items())


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    block = render()
    if args.check:
        if not STATUS.exists():
            print(f"missing {STATUS}; run python scripts/gen_status.py")
            return 1
        text = STATUS.read_text(encoding="utf-8")
        start = text.find(BEGIN)
        end = text.find(END)
        current = text[start:end + len(END)] + "\n" if start >= 0 and end >= 0 else ""
        if current == block:
            print("STATUS ok")
            return 0
        # 覆盖率允许平台漂移，但测试数、失败/跳过数、指标行必须逐字一致。
        if (strip_coverage(current) == strip_coverage(block)
                and test_count(current) == test_count(block)
                and coverage_close(current, block)):
            print(f"STATUS ok（覆盖率在 ±{COVERAGE_TOLERANCE}pp 内）")
            return 0
        print("STATUS.md is stale; run python scripts/gen_status.py and commit")
        return 1
    existing = STATUS.read_text(encoding="utf-8") if STATUS.exists() else "# ShopPilot Status\n\n"
    start = existing.find(BEGIN)
    end = existing.find(END)
    if start >= 0 and end >= 0:
        updated = existing[:start] + block + existing[end + len(END) + 1:]
    else:
        updated = existing.rstrip() + "\n\n" + block
    STATUS.write_text(updated, encoding="utf-8", newline="\n")
    print(f"wrote {STATUS}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
