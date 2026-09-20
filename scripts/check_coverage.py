#!/usr/bin/env python3
"""覆盖率棘轮：读各模块的 JaCoCo 产物，按模块分别比门槛，低了就红。

round18 票 44 / ADR 0041。为什么是「报告进构建 + 独立脚本判定」而不是 jacoco:check：
本仓既有的 0 token 门禁都是这个形状（verify_eval_judge.py / run_tool_eval.py --rescore /
eval_suites.py），脚本能把实测值与门槛一起打出来供产物引用，jacoco:check 只给一句失败。

口径（ADR 0041 已定，本脚本只执行）：
  * **按模块分别设闸**，不设聚合门槛。gateway 249 条用例与 tool-api 3 条的量级差太大，
    聚合门槛会让量小的模块的回归被掩盖。
  * **LINE 设闸、BRANCH 只报不设闸。**
  * 门槛 = 首次实测值向下取整再留 1pp 余量。**1pp 是抖动余量，不是目标值**——
    棘轮的作用是「只许升不许降」，设成当前值就已经拿到那个要件，设高门槛只会制造
    与功能无关的补测压力，并诱导未来为过闸而写无断言用例。

0 token、无网络、只用标准库。用 `--report` 只打印不判定（有意变更覆盖率后重新取数用）。
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# 门槛表：模块目录名 -> LINE 覆盖率门槛（百分比）。
# 2026-09-21 首次实测（round18 基线）：gateway 55.37% / biz-mock 77.49% / tool-api 41.73%。
# 门槛 = floor(实测) - 1.0。改这张表必须在 EVIDENCE.md 与票 44 的 Handoff 里写明理由。
LINE_FLOORS = {
    "shoppilot-gateway": 54.0,
    "shoppilot-biz-mock": 76.0,
    "shoppilot-tool-api": 40.0,
}

REPORT_REL = Path("target") / "site" / "jacoco" / "jacoco.xml"


def read_counters(path: Path) -> dict[str, tuple[int, int]]:
    """返回 {counter 类型: (missed, covered)}，取 report 根节点上的直接子 counter（全模块汇总）。"""
    root = ET.parse(path).getroot()
    counters: dict[str, tuple[int, int]] = {}
    for counter in root.findall("counter"):
        counters[counter.get("type", "")] = (
            int(counter.get("missed", "0")),
            int(counter.get("covered", "0")),
        )
    return counters


def percent(counters: dict[str, tuple[int, int]], kind: str) -> float:
    missed, covered = counters.get(kind, (0, 0))
    total = missed + covered
    return 100.0 * covered / total if total else 0.0


def main(argv: list[str]) -> int:
    report_only = "--report" in argv
    root = Path(__file__).resolve().parent.parent

    failures: list[str] = []
    lines: list[str] = []

    for module, floor in sorted(LINE_FLOORS.items()):
        artifact = root / module / REPORT_REL
        if not artifact.is_file():
            # 缺产物不是「跳过」而是「没验证」——静默放过等于把门禁变成摆设。
            failures.append(f"{module}: 找不到 {REPORT_REL}（先跑 mvnw verify）")
            continue

        counters = read_counters(artifact)
        line_pct = percent(counters, "LINE")
        branch_pct = percent(counters, "BRANCH")

        verdict = "OK"
        if not report_only and line_pct + 1e-9 < floor:
            verdict = "BELOW"
            failures.append(f"{module}: LINE {line_pct:.2f}% < 门槛 {floor:.2f}%")

        lines.append(
            f"  {module:<22} LINE {line_pct:6.2f}% (门槛 {floor:5.2f}%)  "
            f"BRANCH {branch_pct:6.2f}%（只报不设闸）  {verdict}"
        )

    print("COVERAGE 棘轮（LINE 设闸 / BRANCH 只报，按模块分别判定）")
    for line in lines:
        print(line)

    if report_only:
        print("COVERAGE REPORT-ONLY：只打印，未判定")
        return 0

    if failures:
        print("COVERAGE FAIL：")
        for failure in failures:
            print(f"  - {failure}")
        print("  （门槛表在 scripts/check_coverage.py；调门槛要写明理由，别为了过闸降线。）")
        return 1

    print(f"COVERAGE OK  modules={len(LINE_FLOORS)}")
    return 0


if __name__ == "__main__":
    # 本仓家法：默认 GBK 控制台下打印中文会崩，父子进程一律钉 utf-8。
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    sys.exit(main(sys.argv[1:]))
