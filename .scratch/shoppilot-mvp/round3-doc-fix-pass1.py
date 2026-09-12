# -*- coding: utf-8 -*-
"""第四轮收尾 · 第一批定向替换（16 处：README 4 / ticket 20 5 / 计划 7）。

跑法：`cd D:\\ShopPilot; python .scratch/shoppilot-mvp/round3-doc-fix-pass1.py`（路径是仓库相对的，必须在仓库根跑）。
它是**一次性补丁的记录**：这批编辑已经落进仓库，现在重跑应当 16 处全 `skip`、退出码 0。
它入仓的第一个理由是让文档里「每处 `count==1` 断言」这句话有对应产物可核；第二个理由是它带**三态幂等守卫**，
而这条守卫是被自己踩出来的：原先没有守卫时重跑了一遍，`new` 里原样含着 `old` 的那几处又被应用一次，
把 README 一段 454 字与计划两段贴成了重复内容（由 `round3-doc-fix-repair-dupes.py` 按"两份逐字相同且紧邻"摘干净）。
注意边界：重跑全 `skip` 只证明脚本没坏，**不证明**这批编辑当时真的落过——那要看 ticket 20 第 10 条与 diff。
"""
import io
import os
import sys

# 路径是仓库相对的：不在仓库根跑就是 FileNotFoundError / 或更坏的"读到了别处的同名文件"，这里前置断掉。
if not os.path.isfile(".scratch/shoppilot-mvp/issues/20-action-order-attribution.md"):
    print("必须在仓库根目录跑（当前目录下找不到 ticket 20）", file=sys.stderr)
    sys.exit(2)

EDITS = []


def sub(path, old, new):
    EDITS.append((path, old, new))


R = "README.md"
T = ".scratch/shoppilot-mvp/issues/20-action-order-attribution.md"
P = ".scratch/shoppilot-mvp/round3-plan.md"

# ---- README 1：那句全称否定是假的 ----
sub(R,
    "同机全量矩阵里这两步实测 80s / 12s（`69s / 13s` 那个旧读数与本机任何一份落盘矩阵都不符，第三轮审查抓出来随换轮一并改）",
    "同机全量矩阵里这两步实测 80s / 12s（索引行原来写的 `69s / 13s` 与第三轮任何一次落点跑法都不符，随换轮一并改；"
    "**但第三轮订正时我把那句话说过头了**——写成「与本机任何一份落盘矩阵都不符」，而 09-09 的 `logs/acceptance-run5.log` "
    "里 `stack 0 ok / 69s` + `demo 0 ok / 13s` 是成对在的：它是更早一天的真读数，只是不属于这一轮任何一次跑法。"
    "收口审计 H2 钉住那一对确实存在，H2b 钉住 README 里不许再出现那种全称否定）")

# ---- README 2：logs 不入库这条取证面口径 ----
sub(R,
    "矩阵现在由脚本自己落盘（`logs/acceptance-run-<时间戳>.log`，本机不入库）：",
    "矩阵现在由脚本自己落盘（`logs/acceptance-run-<时间戳>.log`；`logs/` 整目录与 `*.log` 在 `.gitignore` 第 9-10 行里，"
    "**干净克隆里没有这些日志**，入仓的机器证据只有 `eval/results/` 的 CSV/meta、`docs/console.png` 与 `.scratch/` 里那份收口审计读数）：")

# ---- README 3：② 那轮的脏工作树照登 + 一次性读数与缺的复验 ----
sub(R,
    "外加 `console` 红在那条打字机分块断言上（实测 `3 chunks / 60 chars`，判据要 `> 60` 个字）。",
    "外加 `console` 红在那条打字机分块断言上（当时打印 `3 chunks / 60 chars`，判据要 `> 60` 个字）；"
    "这一轮日志头两行记的是 `开跑时工作树=dirty（4 个未提交改动）`，那 4 项是 `docs/console.png` 与 `195822` 那一轮冒烟产物的三份文件"
    "（都是前一轮门禁自己写出来的生成物），按上面「记的始终是脏的是什么」的口径照登在这一行。")

sub(R,
    "可用内存回到 3.3 GB 之后重跑就是上面那个 17/17。",
    "可用内存回到 3.3 GB 之后重跑就是上面那个 17/17。"
    "这两轮的**一次生活体读数**（可用内存 GB 数、`embed_unavailable_total` 累计 13、冷加载 5.43 s、`3 chunks / 60 chars`）"
    "当时是从命令行与 `logs/acceptance/*.log` 上手抄的，`logs/` 不入库、逐步日志又被 21:11 那轮覆盖，**现在复原不出来**；"
    "能复原的只有这三份 `acceptance-run-*.log` 步骤矩阵本身、落点轮的 `polarity.log` 增量 1 与 `console.log` 的 `5 chunks / 103 chars`。"
    "更要紧的一条：本计划 P2 第 47 行要求的「栈不动单跑 `verify-polarity.ps1`、exit 0 才算一次性」这两轮**当时都没做**，"
    "所以「成因是机器不是防线」是推断（依据是同码的 21:11 那轮在内存够时 17/17），不是实测结论；"
    "缺的这次复验连同 `verify-polarity.ps1` 的前置判定一起并进那张另开的票。")

# ---- ticket 20 1：Status 行的"三次" ----
sub(T, "以及同日另外三次没当落点的跑法见文末「第三轮收尾」",
    "以及同日另外几轮没当落点的跑法（一次冻在起栈、两轮未全绿、一轮 17/17 但让位）见文末「第三轮收尾」")

# ---- ticket 20 2/3：两处行号引用与函数体行数 ----
sub(T, '命中 2 行（`run_tool_eval.py:152`、`:156`）',
    '命中 2 行（`run_tool_eval.py:156`、`:160`；第四轮审查前这里写的是 152/156，收口审计 H3 按磁盘行把它钉成 156/160）')
sub(T, '断言 `rescore_details()`（94 行）体内不含',
    '断言 `rescore_details()`（起于 `run_tool_eval.py:421`、函数体 92 行）体内不含')

# ---- ticket 20 4：取证命令块补一条零额度复读说明（命令条数仍是 4 条 python）----
sub(T, "  --rescore-expected-diff ACT-ORD-09,ACT-ORD-11,ACT-ORD-16,ACT-ORD-17\n```",
    "  --rescore-expected-diff ACT-ORD-09,ACT-ORD-11,ACT-ORD-16,ACT-ORD-17\n"
    "# 另有一条零额度复读（不是评测命令）：GET /api/v1/support/ops/circuit 的 tokensUsedToday，"
    "取法见 run-dev-guardcheck.ps1 的 Get-Circuit\n```")

# ---- ticket 20 5：收口脚本入仓后，第 9 条那句"不入仓"要改 ----
sub(T, "   收口脚本钉的是当轮常数（落点轮、耗时、sha 前缀、104 用例数），改落点就得改它，故不入仓；可复现物仍是本票文末「取证命令」那 5 条。",
    "   收口脚本按第四轮审查的意见**入仓**：`.scratch/shoppilot-mvp/round3-closeout-audit.py`（放 `.scratch` 不放 `scripts/`，"
    "否则按 P7 口径改了 `scripts/` 就得重跑 17 步门禁，而这台机器今晚只剩 1.1 GB 可用内存），"
    "读数产物同目录 `round3-closeout-audit.txt`。它钉的是当轮常数（落点轮、耗时、sha 前缀、104 用例数），换落点就得同步改它——"
    "这与 `verify_eval_judge.py` 里 `EOL_BASELINE` 同一类「有意钉死的基线」。可复现物是本票文末「取证命令」那 4 条 python 命令 + 1 条零额度复读 + 这一支审计。")

# ---- 计划 1：P9 标题（计数口径那一行已单独用 apply_patch 改掉，不在此批内）----
sub(P, "### P9（计划外新增）机器内存压力挡住 17/17 那一轮 —— 待用户处置",
    "### P9（计划外新增）机器内存压力挡住 17/17 那一轮（已解决，结论在本节末的状态行）")

# ---- 计划 2：P4 的留库理由与扫描数字口径 ----
sub(P, "  - 留库：`185410-local-smoke*`（README 索引行 + 第 11 条）",
    "  - 留库：`185410-local-smoke*`（第 11 条那 1 格抖动的对照面；README 索引行在 `d8beb32` 换轮后已改引 `211809`，"
    "第四轮审查抓到「README 索引行」这个理由已经过期）")
sub(P, "    **共 27 个具体引用，MISSING 0**（22:5x 收口审计复扫）。",
    "    **扫描口径由审计 F1 自己打印：出现次数 / 去重后不同名数 / MISSING 数**（第四轮审查前这里写的是「27 个具体引用」，"
    "但两份独立复算各得 25 与 46——同一个名字集合按不同去重口径会给不同数，所以这一格改成只引用审计的机器读数，不再手写数字）。")

# ---- 计划 3：P4/P8 的限定语从"通过判据"下方挪到证据侧 ----
sub(P, "- **通过判据**：`git ls-files eval/results` 里每个 20260911 文件都被 README 或某张 ticket 引用；每条引用都指向在库文件。\n"
       "  - （收口审计补的限定，不是放宽）「引用」指把某产物当证据依赖；文档里明写成**被删对象身份**的记录不算引用，但必须逐名钉出，不得放开成一类。\n"
       "    钉死豁免表 = 下面那两条，且审计断言「豁免表无死条目」——名字一旦不再出现在文档里，豁免就红，防止它变成永久免检通道。\n",
       "- **通过判据**：`git ls-files eval/results` 里每个 20260911 文件都被 README 或某张 ticket 引用；每条引用都指向在库文件。\n")
sub(P, "  - 记一条踩坑：第一版扫描器报 26 条 MISSING",
    "  - 本项判据原文一字未动。收口审计在这里只追加两件事：把「引用」限定为**把产物当证据依赖**（文档里明写成被删对象身份的删除记录不算依赖，"
    "但必须逐名钉出、不得放开成一类），并加一条反向断言「豁免表无死条目」（审计 F2b）——名字一旦不再出现在文档里，豁免就红。\n"
    "  - 第四轮审查指出这两句当时写在「通过判据」正下方，读起来像事后改判据；措辞挪到证据侧，判据本体保持原样。\n"
    "  - 记一条踩坑：第一版扫描器报 26 条 MISSING")

# ---- 计划 4：P8 的"一键复现"限定语同样挪走 + 54 项改成审计读数 ----
sub(P, "- **通过判据**：P0-P7 全绿、`tokensUsedToday` 差值 0、证据链可一键复现，才 `update_goal complete`。\n"
       "  - 「一键复现」的可复现物是本票文末「取证命令」那一块（5 条命令，全离线 0 token）；收口审计是**一次性跑器**，\n"
       "    它钉的是当轮常数（落点轮、耗时、sha 前缀、`104` 用例数），改落点就得改它，所以**不入仓**，避免留下一个自我过期的假防线。\n",
       "- **通过判据**：P0-P7 全绿、`tokensUsedToday` 差值 0、证据链可一键复现，才 `update_goal complete`。\n")
sub(P, "- 状态：**Pass**（22:5x 收口审计 **54/54 全 PASS、退出码 0**、审计期间 `tokensUsedToday` 前后均 0、跑完 `git status --porcelain` 复空）",
    "- 状态：**Pass**（收口审计全 PASS、退出码 0，项数以审计产物 `round3-closeout-audit.txt` 末行为准；审计期间 `tokensUsedToday` 前后均 0、跑完 `git status --porcelain` 复空）\n"
    "  - 本项判据原文一字未动；「可一键复现」在这条判据下指的是 ticket 20 文末那 4 条 python 取证命令 + 1 条零额度复读，"
    "第四轮审查指出我原先把这句解释写在判据正下方、读起来像改判据，现挪到证据侧。"
    "收口审计本身也按审查意见入仓（`round3-closeout-audit.py` + `round3-closeout-audit.txt`），"
    "免得「审计全绿」这句话在仓库里没有对应产物可核——那正是本票一直在治的病，不该由收口动作自己犯。")

fails = 0
applied = 0
skipped = 0
for path, old, new in EDITS:
    raw = io.open(path, "rb").read()
    crlf = raw.count(b"\r\n")
    # 匹配一律在 LF 形态上做：EDITS 里的模式串换行是 LF，而 `core.autocrlf=true` 的克隆上检出是 CRLF，
    # 不规范化就会把「已应用」误判成「漂移」。2026-09-12 干净克隆复测实抓到 4 处 FAIL + 1 处误应用。
    # 第七轮 Standards 轴抓到另一半：`if crlf` 对**混合换行**的文件会把裸 LF 整体翻成 CRLF，
    # 原先只打一句「注」不判失败——现在直接拒绝在这种文件上写回。
    _lone_lf = raw.count(b"\n") - crlf
    if crlf and _lone_lf:
        print(f"FAIL 混合换行：{path} 有 {crlf} 个 CRLF 与 {_lone_lf} 个裸 LF，跑器拒绝在这种文件上写回")
        fails += 1
        continue
    txt = raw.decode("utf-8").replace("\r\n", "\n")
# 三态判定，不许把"看不懂"当成"已通过"（2026-09-12 自己踩出来的：重跑跑器把三段话重复贴了一遍）：
#   new 已在文中            -> 已应用，跳过（有几处的 new 原样含着 old，所以 old 在不在不能当判据）
#   old 恰一次且 new 不在    -> 未应用，应用一次
#   其余（old 零次或多次）   -> 文档漂到跑器不认识的样子，报 FAIL 停下来查
    if new in txt:
        print(f"skip 已应用  {path}  <<{new[:50]}>>")
        skipped += 1
        continue
    n = txt.count(old)
    if n != 1:
        print(f"FAIL 漂移：old 命中 {n} 次且 new 不在文中  {path}  <<{old[:60]}>>")
        fails += 1
        continue
    out = txt.replace(old, new)
    # 写回保持该文件原有的换行形态：CRLF 检出上不许被跑器悄悄改成 LF，LF 检出上不许被改成 CRLF。
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
