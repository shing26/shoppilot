#!/usr/bin/env python3
"""文档真值一致性门禁（round48 补口 / ADR 0083）。

`gen_status.py --check` 只守 `docs/STATUS.md` 自己有没有过期；它管不到 README 的 badge、
复现段的四模块读数、CI 段落里的「700 条」，也管不到 ADR 篇数。审计第 2 条要的正是这一层：
**几处数字必须同时对上产物与磁盘，任何一处漂移 CI 就红。**

口径：
  - 测试数/套件数：直接从四模块 `target/surefire-reports/TEST-*.xml` 现算（与 gen_status 同源）；
  - STATUS：读 `docs/STATUS.md` 的 `JVM：**N** tests ... （M suites）`；
  - README：badge、复现段「四模块 a + b + c + d = N 项」、CI 段「**N 条** JVM 测试（…）」；
  - ADR：badge 的篇数 == `docs/adr/*.md` 实际文件数；
  - TUNING_PLAN：`JVM | **N tests / M suites**` 与 `JVM | N / M 全绿`。

只读、0 token。找不到某个锚点也算失败——锚点被改写成另一种说法时，宁可红一次让人确认，
也不要让检查静默跳过。
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODULES = ("shoppilot-tool-api", "shoppilot-biz-mock", "shoppilot-gateway", "shoppilot-ticket")


# 与 `gen_status.py` 同一口径：只算 surefire 默认 include 的类名。手工 `-Dtest=...`
# 跑的非默认类（如 LlmCassetteRecorder）报告会留在 target/，不能算进 verify 全量。
SUREFIRE_DEFAULT = re.compile(r"^(Test.*|.*Tests?|.*TestCase)$")


def counted_report(path):
    simple = path.stem.removeprefix("TEST-").rsplit(".", 1)[-1]
    return bool(SUREFIRE_DEFAULT.match(simple))


def surefire():
    counts = {}
    total = suites = 0
    for module in MODULES:
        tests = 0
        for path in (ROOT / module / "target" / "surefire-reports").glob("TEST-*.xml"):
            if not counted_report(path):
                continue
            root = ET.parse(path).getroot()
            tests += int(root.get("tests", "0"))
            suites += 1
        counts[module] = tests
        total += tests
    return counts, total, suites


def read(rel):
    path = ROOT / rel
    if not path.exists():
        raise SystemExit(f"缺少 {rel}")
    return path.read_text(encoding="utf-8")


def main() -> int:
    errors = []
    counts, total, suites = surefire()
    if total == 0:
        print("没有 surefire 产物：先跑一次 verify（CI 里本步排在它之后）")
        return 1

    status = read("docs/STATUS.md")
    found = re.search(r"JVM：\*\*(\d+)\*\* tests / .*?（(\d+) suites）", status)
    if not found:
        errors.append("docs/STATUS.md 找不到 `JVM：**N** tests ...（M suites）` 锚点")
    else:
        status_tests, status_suites = int(found.group(1)), int(found.group(2))
        if status_tests != total:
            errors.append(f"STATUS 写 {status_tests} tests，surefire 现算 {total}")
        if status_suites != suites:
            errors.append(f"STATUS 写 {status_suites} suites，surefire 现算 {suites}")

    readme = read("README.md")
    for pattern, label, expected in (
            (r"JVM%20Tests-(\d+)-", "README JVM Tests badge", total),
            (r"Architecture-(\d+)%20ADRs", "README ADR badge", len(list((ROOT / "docs" / "adr").glob("*.md")))),
    ):
        found = re.search(pattern, readme)
        if not found:
            errors.append(f"{label} 找不到（锚点被改写也要显式改这道门禁）")
        elif int(found.group(1)) != expected:
            errors.append(f"{label} 写 {found.group(1)}，实际 {expected}")

    found = re.search(r"四模块 (\d+) \+ (\d+) \+ (\d+) \+ (\d+) = (\d+) 项", readme)
    if not found:
        errors.append("README 复现段找不到「四模块 a + b + c + d = N 项」")
    else:
        actual = [counts["shoppilot-tool-api"], counts["shoppilot-biz-mock"],
                  counts["shoppilot-gateway"], counts["shoppilot-ticket"]]
        listed = [int(found.group(i)) for i in range(1, 5)]
        if listed != actual or int(found.group(5)) != total:
            errors.append(f"README 复现段写 {listed} = {found.group(5)}，实际 {actual} = {total}")

    found = re.search(r"覆盖构建与 \*\*(\d+) 条\*\* JVM 测试"
                      r"（tool-api (\d+) \+ biz-mock (\d+) \+ ticket (\d+) \+ gateway (\d+)）", readme)
    if not found:
        errors.append("README CI 段找不到「**N 条** JVM 测试（tool-api a + biz-mock b + ticket c + gateway d）」")
    else:
        actual = [counts["shoppilot-tool-api"], counts["shoppilot-biz-mock"],
                  counts["shoppilot-ticket"], counts["shoppilot-gateway"]]
        listed = [int(found.group(i)) for i in range(2, 6)]
        if int(found.group(1)) != total or listed != actual:
            errors.append(f"README CI 段写 {found.group(1)} 条 {listed}，实际 {total} 条 {actual}")

    tuning = read("docs/TUNING_PLAN.md")
    for pattern in (r"JVM \| \*\*(\d+) tests / (\d+) suites\*\*", r"JVM \| (\d+) / (\d+) 全绿"):
        found = re.search(pattern, tuning)
        if not found:
            errors.append(f"TUNING_PLAN 找不到锚点 {pattern}")
        elif (int(found.group(1)), int(found.group(2))) != (total, suites):
            errors.append(f"TUNING_PLAN 写 {found.group(1)}/{found.group(2)}，实际 {total}/{suites}")

    found = re.search(r"当前 \*\*(\d+) 个唯一 `shoppilot_\*` 指标名\*\*", readme)
    if not found:
        errors.append("README 指标口径段找不到唯一指标名计数")
    else:
        expected = len(set(re.findall(r"shoppilot_[a-z0-9_]+",
                                      "\n".join(p.read_text(encoding="utf-8")
                                                for m in ("shoppilot-tool-api", "shoppilot-biz-mock",
                                                          "shoppilot-gateway")
                                                for p in (ROOT / m / "src" / "main").rglob("*.java")))))
        if int(found.group(1)) != expected:
            errors.append(f"README 写 {found.group(1)} 个唯一指标名，三模块源码现算 {expected}")

    if errors:
        print("文档真值不一致：")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"文档真值 OK：{total} tests / {suites} suites；README badge、复现段、CI 段、"
          f"TUNING_PLAN、STATUS 与产物一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
