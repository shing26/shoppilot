# -*- coding: utf-8 -*-
"""第四轮收尾 · 第二批定向替换（3 处：ticket 20 2 / 计划 1）。

内容：Standards F5 的措辞撞车消歧（本票第 24 条那两句"两轮"其实指不同的两轮）、计划 P7 追加第四轮记账一行。
它是**一次性补丁的记录**，现在重跑应当 3 处全 `skip`、退出码 0；三态幂等守卫的由来见 `round3-doc-fix-pass1.py`
的说明（同一天我自己重跑没守卫的版本，把三段话贴重复了）。
跑法：`cd D:\\ShopPilot; python .scratch/shoppilot-mvp/round3-doc-fix-pass2.py`。
"""
import io
import os
import sys

# 同 pass1：路径是仓库相对的，不在仓库根跑就前置断掉。
if not os.path.isfile(".scratch/shoppilot-mvp/issues/20-action-order-attribution.md"):
    print("必须在仓库根目录跑（当前目录下找不到 ticket 20）", file=sys.stderr)
    sys.exit(2)

T = ".scratch/shoppilot-mvp/issues/20-action-order-attribution.md"
P = ".scratch/shoppilot-mvp/round3-plan.md"
EDITS = [
    # Standards F5：那两句"两轮"其实指不同的两轮，缺陷是措辞撞车，不是重复内容。消歧而非删除。
    (T,
     "同一批改动在此之前跑过两轮都没收口，两轮的失败原因都记在这儿，不挑一次好看的写：① ",
     "更早还有**另外**两轮同批改动没收口（`121933` 那一轮 16/17，与 C 盘只剩 19 MB 让 `build`+`unit` 两步全红的那一轮），"
     "它们与上一句的 `200038`/`201808` 不是同两次，失败原因都记在这儿，不挑一次好看的写：① "),
    # 同一句尾的"不挑一次好看的写"重复出现，是第四轮审查读成同义反复的直接原因；上一句改成按形状点名。
    (T,
     "同一批代码在换落点之前还有两轮没全绿（`200038`、`201808`），两轮的失败原因记在文末「第三轮收尾」，不挑一次好看的写。",
     "同一批代码在换落点之前还有两轮没全绿（`200038` 16/17、`201808` 15/17），这两轮的失败原因记在文末「第三轮收尾」。"),
    # 计划 P7 追加第四轮一行（闭环位置）
    (P,
     "- 状态：**Pass**（Standards 9 条 + Spec 2 条，逐条核实全部成立，闭环在 `c5e6c2e` / `9eede6d`；最重一条是假的\"零差异\"比对，见本票第 11 条）",
     "- 状态：**Pass**（Standards 9 条 + Spec 2 条，逐条核实全部成立，闭环在 `c5e6c2e` / `9eede6d`；最重一条是假的\"零差异\"比对，见本票第 11 条）\n"
     "  - **第四轮**（fixed point `9eede6d`，Standards 6 条 + Spec 5 条）与**第五轮**（fixed point 见 P10）的账记在 P10 与本票「第三轮收尾」第 10 条，"
     "不并到本行，免得把两轮审查的成立/驳回混成一锅。"),
]

fails = 0
applied = 0
skipped = 0
for path, old, new in EDITS:
    raw = io.open(path, "rb").read()
    crlf = raw.count(b"\r\n")
    # 同 pass1：在 LF 形态上匹配，写回时还原该文件原有的换行形态（CRLF 检出上不误判成漂移）。
    txt = raw.decode("utf-8").replace("\r\n", "\n")
# 同 pass1 的三态判定（new 已在文中 = 已应用；old 恰一次且 new 不在 = 应用；其余 = 漂移报 FAIL）。
    if new in txt:
        print(f"skip 已应用  {path}  <<{new[:50]}>>")
        skipped += 1
        continue
    n = txt.count(old)
    if n != 1:
        print(f"FAIL 漂移：old 命中 {n} 次且 new 不在文中  {path}  <<{old[:50]}>>")
        fails += 1
        continue
    out = txt.replace(old, new)
    io.open(path, "wb").write((out.replace("\n", "\r\n") if crlf else out).encode("utf-8"))
    applied += 1
    now = io.open(path, "rb").read().count(b"\r\n")
    if now != crlf:
        print(f"  注：{path} CRLF 数由 {crlf} 变 {now}")
    if new not in io.open(path, encoding="utf-8", newline="").read().replace("\r\n", "\n"):
        print(f"FAIL 替换后 new 不在文中（自我矛盾）  {path}")
        fails += 1
print(f"共 {len(EDITS)} 处：新应用 {applied}、已应用跳过 {skipped}、失败 {fails}")
sys.exit(1 if fails else 0)
