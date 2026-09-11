#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""回滚一次"把一次性跑器重跑了一遍"造成的 3 处重复段落。

2026-09-12 第四轮收尾时，为了验证 pass1/pass2 是否幂等而真跑了一遍 —— 结果它们**不幂等**：
有几处替换的 new 里原样含着 old，重跑时 `count(old)` 依然是 1，于是又应用一次，把三处文字重复了一遍。
这个脚本按 `count==1` 断言把多出来的那一份摘掉（只摘重复，不动任何其他内容），跑完 pass1/pass2 再跑它应当报 `count=0`。

跑法：`cd D:\\ShopPilot; python .scratch/shoppilot-mvp/round3-doc-fix-repair-dupes.py`
"""
import io
import os
import sys

if not os.path.isfile(".scratch/shoppilot-mvp/issues/20-action-order-attribution.md"):
    print("必须在仓库根目录跑（当前目录下找不到 ticket 20）", file=sys.stderr)
    sys.exit(2)

R = "README.md"
P = ".scratch/shoppilot-mvp/round3-plan.md"

# 两类修法：
#   ADJACENT：不重打原文，只在磁盘上找出"同一段紧邻出现两次"的形状并摘掉第二份（首选手法，避免我自己抄错字）；
#   BLOCK：按整段文本比对（P4/P7 那两处用这个，因为它们本来就是我打进去的）。
ADJACENT = [
    # README：pass1 的 new 里原样含着 old，重跑时又在第一份之后接了一份
    (R, "这两轮的**一次生活体读数**", "缺的这次复验连同 `verify-polarity.ps1` 的前置判定一起并入那张另开的票。",
     "README：20:00/20:18 两轮的活体读数与缺复验声明"),
]

BLOCKS = [
    ("计划 P4：挪走限定语后追加在证据侧的那两句",
     "  - 本项判据原文一字未动。收口审计在这里只追加两件事：把「引用」限定为**把产物当证据依赖**（文档里明写成被删对象身份的删除记录不算依赖，"
     "但必须逐名钉出、不得放开成一类），并加一条反向断言「豁免表无死条目」（审计 F2b）——名字一旦不再出现在文档里，豁免就红。\n"
     "  - 第四轮审查指出这两句当时写在「通过判据」正下方，读起来像事后改判据；措辞挪到证据侧，判据本体保持原样。\n"),
    ("计划 P7：第四轮记账那一行",
     "  - **第四轮**（fixed point `9eede6d`，Standards 6 条 + Spec 5 条）与**第五轮**（fixed point 见 P10）的账记在 P10 与本票「第三轮收尾」第 10 条，"
     "不并到本行，免得把两轮审查的成立/驳回混成一锅。\n"),
]

fails = 0

# ADJACENT：不重打原文（重打就会抄错字，刚才就是这么失败的）。取法：
#   第一份 = 标记词第 1 次出现 → 第 2 次出现之间的整段；第二份 = 从第 2 次出现起等长的一段；
#   两者必须逐字相同才动手，把第二份整段摘掉。
for path, marker, anchor, label in ADJACENT:
    txt = io.open(path, encoding="utf-8", newline="").read()
    i1 = txt.find(marker)
    i2 = txt.find(marker, i1 + 1)
    if i1 >= 0 and i2 < 0 and txt.count(marker) == 1:
        print(f"skip 已经是单份（本处先前已摘掉）  {path}  {label}")
        continue
    elif i1 < 0 or i2 < 0:
        print(f"FAIL 没找到两份 {path}  {label}（第一份={i1} 第二份={i2}）")
        fails += 1
        continue
    first = txt[i1:i2]
    second = txt[i2:i2 + len(first)]
    if first != second:
        print(f"FAIL 两份不逐字相同，拒绝动手 {path}  {label}（第一份 {len(first)} 字 / 第二份 {len(second)} 字）")
        fails += 1
        continue
    io.open(path, "wb").write((txt[:i2] + txt[i2 + len(first):]).encode("utf-8"))
    left = io.open(path, encoding="utf-8", newline="").read().count(marker)
    print(f"ok 摘掉紧邻的重复 1 份（{len(first)} 字）-> 标记词剩 {left} 份  {path}  {label}")
    if left != 1:
        fails += 1

for path, label, block in [(P, BLOCKS[0][0], BLOCKS[0][1]), (P, BLOCKS[1][0], BLOCKS[1][1])]:
    txt = io.open(path, encoding="utf-8", newline="").read()
    doubled = block + block
    n2, n1 = txt.count(doubled), txt.count(block)
    if n2 == 0 and n1 == 1:
        print(f"skip 已经是单份（本处先前已摘掉）  {path}  {label}")
        continue
    elif n2 != 1:
        print(f"FAIL 重复段未找到（doubled={n2} 单份={n1}） {path}  {label}")
        fails += 1
        continue
    io.open(path, "wb").write(txt.replace(doubled, block, 1).encode("utf-8"))
    left = io.open(path, encoding="utf-8", newline="").read().count(block)
    print(f"ok 摘掉重复 1 份 -> 剩 {left} 份  {path}  {label}")
    if left != 1:
        fails += 1
print(f"处理 {len(ADJACENT) + len(BLOCKS)} 处，失败 {fails}")
sys.exit(1 if fails else 0)
