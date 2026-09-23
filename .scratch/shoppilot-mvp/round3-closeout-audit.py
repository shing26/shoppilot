# -*- coding: utf-8 -*-
"""ticket 20 第三轮收口审计（0 token）——**本机当轮对账单**，不是干净克隆可复现的防线。

重跑：`python .scratch/shoppilot-mvp/round3-closeout-audit.py`
  退出码 0 = 全绿；3 = 前置不成立（有 SKIP 项，不是防线失效）；1 = 有 FAIL。
读数产物：同目录 `round3-closeout-audit.txt`（用 `... | Tee-Object -FilePath` 落盘，随本轮一起入仓）。

**本机限定这件事必须写在这儿，因为第五轮双轴审查抓到原先那句「重跑…退出码 0 = 全绿」在干净克隆上跑不出来**：
G 组与 H1/H1b/H2 那批断言读 `logs/`，而 `logs/` 整目录在 `.gitignore` 里（H5 钉着「不入库」）；
A3 还要网关在跑。缺这些前置时，本脚本**不再抛 `FileNotFoundError`**，而是把受影响的项逐条打
`SKIP  <项名>` 并以退出码 3 收口——与 `verify-polarity.ps1` 的 exit 3 同族：前置不成立不等于防线失效，
但也绝不静默算绿。J0 那条断言钉的就是这个形状（拿一个空目录喂解析函数，必须返回「不适用」而不是崩）。

规矩：所有文本比较一律大小写敏感（用 os.listdir / git ls-files 的精确集合，不用 Select-String 那种默认不敏感的比对）。
自带对照组：故意塞几个"必须被判成不存在"的探针，防止扫描器自己假绿；每条新断言都配一支**必须能失败**的反证。

钉的是当轮常数（落点轮、耗时、sha 前缀、surefire 用例数）。换落点就得同步改这些常数——
这是有意的：它是一份**当轮对账单**，不是长期防线；长期防线在 scripts/verify_eval_judge.py。
"""
import csv
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

# 本机控制台默认 GBK，断言 detail 里只要有一个 U+FFFD（子进程输出解码失败时的替换符）就整行崩在 print 上，
# 于是"审计跑不出来"这件事会以 UnicodeEncodeError 的面目出现，而不是以某条 FAIL 的面目出现。
# 第五轮双轴审查抓到的是"缺 logs 会抛 FileNotFoundError"，同一族毛病在这里再补一刀：输出流自己也得钉死 UTF-8。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        pass

REPO = Path(__file__).resolve().parents[2]
RESULTS = REPO / "eval" / "results"
LOGS = REPO / "logs"
FIXED_POINT = "11a12ac"  # 第三轮起点
# 对照组的「订正前」基准必须钉死 commit，不能写 HEAD：
#   这些条目断言的是「订正前那份文档里确实存在这句问题话」，而 HEAD 会随本轮提交前移，
#   一旦提交了就永远取不到那句话，对照组反而把自己判红（第四轮收尾实际踩到过，见 ticket 20 第 10 条）。
PRE_FIX = "a6ccdcb"  # 第四轮收口那一笔（对照组要取它**之前**那份文档，见下面 ROUND_FP 的分工）
ROUND_FP = "fb8eacf"  # round18 起点（round17 收口那一笔，含 22 步活体矩阵落点）。逐轮重锚，见 ADR 0023。

FAILS = []
PASSES = []
SKIPS = []
ALL_NAMES = []  # 本轮跑过的每一项（含 SKIP 分支），H16 用它反查「声明了但盘上没这一项」的腐烂
# 推迟登记：有些判据必须看到**最终**计数才能算（H12 就是），它们在普通 check 流里只挂名字、
# 不落地，等所有 check 跑完再统一 flush。DEFERRED_NAMES 让 H16 反查时把「稍后会跑」也算成跑过。
DEFERRED = []
DEFERRED_NAMES = []
# flush 期已落地的推迟项数。必须在**任何** check 之前就有值：`_run_h12` 靠它算「还欠几格未落地」，
# 而克隆反证 CE-5a/5b 会把 `_run_h12` 改回就地调用——那时如果这个数还没初始化，炸出来的是
# `NameError` 而不是防呆该说的那句诊断（ce13 实跑到：exit=1、无汇总行）。
_DEF_LANDED = 0
# 「本机限定项」清单：读 `logs/` 或依赖本机检出环境的断言全部列在这儿，**在跑任何 check 之前**先打出来。
# 第六轮 Spec 轴抓到判据 3 原文要求「在读 logs/ 之前打出具名清单」，而原先只在各项就地打 SKIP、
# 末尾再汇总——顺序与自述不符。这里补上前置声明，并由 H16 钉「声明 ⊇ 实跑 SKIP」，防这张表腐烂。
# G6 的当轮常数：三份 surefire 模块小计。一处定义、三处引用（下面的本机限定清单、那一格的名称、
# 名称里的算式）。「名称写 149 而断言判 148」这种分家，第十三轮票 22 落点靠实跑才抓出来一次，
# 收成一个来源之后它就长不出来了——本项的判据形状（读哪两份日志、比什么）一字未动。
# 换代指针（第十三轮票 24 / 票 25 / 票 26 共用落点）：网关 140 涨到 174。
# 票 24 +18（RequestTraceTest 7、AuthFilterTest 4→8、LogbackRotationTest 5、TraceCorrelationAcrossAsyncTest 2）；
# 票 25 +14（RestErrorEnvelopeTest 10、AuthFilterTest 8→9、DevDefaultsStartupBlockerTest 3），
# 其收尾审查又补 2 条进同一测试类：流式那一支的 400 信封（票 26 的回显断言靠它有东西可显）、
# 「请求体读不出来」那一支仍是 400（那条 catch-all 把 NestedRuntimeException 的后代吸进 500，是本票自己新造的缺陷）；
# 票 26 不加 JVM 用例，它把九处缺陷落成浏览器那一侧的 17 格（console 18 项 → 35 项）。
# 本项判据形状（读哪两份日志、比什么）一字未动，动的只有这一格常数。
# 换代指针（round14 票 27-31 共用落点）：网关 174 涨到 206。
# 票 27 新增 WriteBackPoolTest 5 条；票 28 新增 ConfigValidationTest 22 条；
# 票 29 新增 RuntimeStateMetricsTest 5 条；票 30/31 是文档与脚本票，不加 JVM 用例。
# 本项判据形状仍是一字未动，动的只有这一格常数。
# 换代指针（round17 落点，2026-09-20）：三份 surefire 模块小计 221 → 267，即 `3 + 15 + 249`。
# 票 34-39/41 与风格票的用例都进了 surefire，而本机此前没有 pwsh 7、跑不了全量活体验收，这一格
# 一直与 221 年代的日志自洽。本次用便携版 PowerShell 7.4.20 跑通 22 步矩阵
# （`logs/acceptance-run-20260920-183028.log`，503s，16 步绿 6 步红，红的六步见 round17 spec 的登记），
# `logs/acceptance/{build,unit}.log` 两份都刷新成 `3 + 21 + 249`，常数随之与它们对齐。
# 本项判据形状（读哪两份日志、比什么）一字未动，动的只有这一格常数。
# round18 换代：`3 + 15 + 249`（267）→ `3 + 21 + 249`（273）——票 42 新增 3 条 SchemaMigrationTest、
# 票 43 新增 3 条 SlowQueryPlanTest；gateway 与 tool-api 用例数未动。G6 读的是本机
# `logs/acceptance/` 日志，属 LOCAL_ONLY，干净克隆里判 SKIP。
# 票 45 换代：`3 + 21 + 249`（273）→ `3 + 21 + 250`（274）——只动 gateway：ADR 0042 的回归用例
# `GatewayMainPathJvmTest.explicitEscalationSurvivesAnUrgentSentimentVerdict` 是新增的第 250 条
# （它在修法落地前是红的，不是既有用例改名）。其余两模块与其余用例数未动。
# 票 46 换代：`3 + 21 + 250`（274）→ `3 + 21 + 253`（277）——仍只动 gateway：ADR 0043 新增 3 条
# （`GatewayMainPathJvmTest.cacheHitPathStaysZeroModelCallsInLocalMode` 回归、
# `SentimentGateTest.localModeIsLexiconOnly` 机制、`SentimentGateTest.devModeStillUsesTheLlmLayer` 正对照）。
G6_EXPECT = [3, 21, 253]
G6_CLAIM = "G6 surefire {} = {}（build 与 unit 两份日志的 Results 段各自核过）".format(
    " + ".join(str(x) for x in G6_EXPECT), sum(G6_EXPECT))

LOCAL_ONLY = [
    "A3 tokensUsedToday == 0",  # 要活体网关；网关不在跑时判 SKIP（第七轮 Standards 轴抓到它漏声明）
    # 换代指针（round17）：本轮的票 39 硬闸门按所有者授权跑了三次全量 dev 评测（约 117 万 token），
    # A3 的零额度口径被有意打破；证据在 eval/results 的三套产物与 round17 spec 的收口记录里。
    # 网关当前已停（跑 Maven 需腾内存），A3 因此判 SKIP 而不是"本轮真的零额度"——别把这次 SKIP 当绿灯。
    "D3 verify_eval_judge 退出码 0 且 40/40 条断言全过",  # 依赖检出后的换行符，见 eol_drift()
    "F1c 文档里的 logs 类产物名都指向本机 logs/（本机限定：logs 不入库）",
    "G2b 落点那轮极性守卫真被触发（blocked 计数器增量 = 1）",
    "G3 落点日志记 commit=9eede6d 且开跑时工作树 clean",
    "G4 落点为 17 步全绿、总耗时 511s",
    "G5 落点矩阵逐步读数（stack 80 / demo 12 / polarity 26 / eval 123）",
    "G5b README 索引行与落点矩阵同读数",
    G6_CLAIM,
    "G7 冒烟日志首行 SCORER SELFCHECK ok=16、末行 EVAL DONE cases=24 errors=0",
    "G8 打字机断言落在 > 60 字这一真判据上（未放宽）",
    "H1 六轮门禁的 ok 步数与钉住的期望表逐轮相符",
    "H1b 每轮都是 17 步（不是步数变少造成的『更绿』）",
    "H1d README 里每一处 N/17 主张都等于该轮日志实算（真读 README，不靠脚本内抄本）",
    "H2 本机确有 69s/13s 成对的落盘矩阵（故 README 不得写『与任何一份都不符』）",
    "H12 入仓读数产物的末行与本次实跑逐字段相同",  # 产物记的是本机全绿那一跑，克隆上不同源
]
TAIL_CHECKS = 9  # N 定义点之后还会跑的 check 数：H7、H7b、H13、H13b、H14、H15、H12、H12b、H16。
# 第七轮把 H12 挪到末尾并加了 H12b，这张表跟着变；加一项就得改这里，末尾硬断言会当场炸。
# P13 落盘期补：H12 在这一段里求值，但它的 check() 登记走 defer，实际落在 H16 之后（见 _run_h12）。


def check(name, ok, detail="", skip=False):
    """skip=True 走第三态：前置不成立，既不算绿也不算防线失效，但计入项数与退出码 3。"""
    tag = "SKIP" if skip else ("PASS" if ok else "FAIL")
    line = f"{tag}  {name}"
    if detail:
        line += f"\n        {detail}"
    print(line, flush=True)
    ALL_NAMES.append(name)
    (SKIPS if skip else (PASSES if ok else FAILS)).append(name)


def defer(name, fn):
    """登记一个「要等最终计数」的 check：名字立刻进清单（H16 要认），判定推迟到 flush 阶段执行。"""
    DEFERRED_NAMES.append(name)
    DEFERRED.append((name, fn))


def sh(args):
    return subprocess.run(args, cwd=str(REPO), capture_output=True, text=True,
                          encoding="utf-8", errors="replace")


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest().upper()


def read(path):
    return Path(path).read_text(encoding="utf-8", errors="replace")


def read_opt(path):
    """本机限定用的读法：文件不在就返回 None，让调用方去判 SKIP，而不是抛异常打断整轮审计。
    缺 `logs/` 是干净克隆的正常形状（H5 钉着 logs 不入库），不是崩溃现场。"""
    p = Path(path)
    return p.read_text(encoding="utf-8", errors="replace") if p.exists() else None


def local_missing(paths):
    """纯函数：返回这组前置里缺哪些（相对路径）。check_local 与 J0b 共用同一个判断，
    免得对照组另抄一份谓词（那是第五轮刚治过的病）。"""
    # 仓库外的路径（J0 的临时探针）也要能报缺，不能因为 relative_to 抛异常把整轮审计打断。
    out = []
    for x in paths:
        p = Path(x)
        if p.exists():
            continue
        try:
            out.append(str(p.relative_to(REPO)).replace("\\", "/"))
        except ValueError:
            out.append(str(p).replace("\\", "/"))
    return out


def check_local(name, paths, fn):
    """依赖本机不入库文件（`logs/`）的断言统一走这里：
    前置齐 → 照常判；前置不齐 → 打 **SKIP 并点名缺哪个文件**，既不算绿也不抛异常。"""
    missing = local_missing(paths)
    if missing:
        check(name, False, f"本机限定·前置不成立，缺 {'、'.join(missing)}（logs/ 不入库，见文件头）", skip=True)
        return
    ok, detail = fn()
    check(name, ok, detail)


def gold_cases():
    out = []
    for name in ["cases-part1-policy.jsonl", "cases-part2-action.jsonl", "cases-part3-edge.jsonl"]:
        for line in (REPO / "eval" / name).read_text(encoding="utf-8").splitlines():
            if line.strip():
                out.append(json.loads(line))
    return out


print("=" * 78)
print("本机限定项（读 logs/ 或依赖本机检出环境；缺前置时判 SKIP，不算绿也不算防线失效）")
print("=" * 78)
for _n in LOCAL_ONLY:
    print(f"  LOCAL  {_n}")
print(f"  共 {len(LOCAL_ONLY)} 项 —— 由 H16 两个方向钉：实跑 SKIP 不得超出这张表，表里也不许有本轮没跑过的死条目")

print()
print("=" * 78)
print("A. 零额度 / git 状态")
print("=" * 78)

head = sh(["git", "rev-parse", "HEAD"]).stdout.strip()
origin = sh(["git", "rev-parse", "origin/main"]).stdout.strip()
check("A1 origin/main == HEAD", head == origin, f"HEAD={head[:9]} origin/main={origin[:9]}")

# 注意：`git status --porcelain` 每行是 `XY<space>path`，前两行通常是 " M "（前导空格是状态位的一部分）。
# 对整个输出做 .strip() 会把**第一行**的前导空格一起吃掉，于是第一条路径少一个点（`.scratch` 变 `scratch`），
# 后果是豁免表认不出它、A2 假红、D4 又把同一个路径看成"新增又消失"。这里按行解析，不动每行的列结构。
def dirty_paths():
    out = sh(["git", "status", "--porcelain"]).stdout
    return [l[3:].strip().strip('"') for l in out.splitlines() if l.strip()]


# 自排除名单：只允许审计**自己的读数产物**。Tee-Object 是边跑边写的，产物在跑的过程中必然处于未跟踪态，
# 不排除它就是永远自我判脏；能排掉自己，也就有了下面 A2b 这条防呆——这张表一旦长出文档或脚本路径就是免检通道。
OWN_TXT = ".scratch/shoppilot-mvp/round3-closeout-audit.txt"  # 本脚本自己的读数产物，H12 读的是 HEAD 里那一份
OWN_ARTIFACTS = {OWN_TXT}
_all = dirty_paths()
_kept = [l for l in _all if l not in OWN_ARTIFACTS]
_dropped = [l for l in _all if l in OWN_ARTIFACTS]
# 归因（2026-09-20，不改判据）：本机工作树里有 `CHANGELOG.md` 与 `DELIVERY.md` 两个未跟踪文件，
# 它们不是本轮产物——同一 checkout 上有第二个写入者（另一个 agent 会话）在并行落地交付契约页与
# 变更日志，本轮既不碰它们、也不替它提交（提交了会让 README 的 DELIVERY.md 导航行指向一个未入库
# 的文件）。A2 因此在本机判红：判据一字未动，干净克隆里这两个文件不存在，A2 在那里照常绿。
# 别把这条红读成"本轮改动没提交"——`git status --short` 里属于本轮的改动全是 staged。
BASELINE_DIRTY = set(_kept)  # A2 时刻"本轮正在改的东西"快照；D4 用它判取证复跑有没有添乱
check("A2 工作树 clean（审计自身读数产物除外，见 A2b）", not _kept,
      f"未提交且不可豁免 {len(_kept)} 项：{_kept}" if _kept else "无输出（当轮审计脚本已提交）")
check("A2b 自排除名单不得长出文档/脚本路径（防长成免检通道）",
      all(p.endswith("round3-closeout-audit.txt") for p in OWN_ARTIFACTS)
      and not any(p.endswith((".md", ".java", ".jsonl", ".csv")) for p in OWN_ARTIFACTS)
      and not any(p.startswith(("scripts/", "eval/", "src/", "docs/")) for p in OWN_ARTIFACTS),
      f"名单 {sorted(OWN_ARTIFACTS)}；本次实际被豁免 {_dropped}")

try:
    body = json.dumps({"tenantId": "T001", "customerId": "C001"}).encode()
    req = urllib.request.Request("http://127.0.0.1:8082/auth/mock-token", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    tok = json.loads(urllib.request.urlopen(req, timeout=10).read().decode())
    token = tok.get("token") or tok.get("accessToken")
    req = urllib.request.Request("http://127.0.0.1:8082/api/v1/support/ops/circuit",
                                 headers={"X-Ops-Token": "dev-ops-token",
                                          "Authorization": f"Bearer {token}"})
    circuit = json.loads(urllib.request.urlopen(req, timeout=10).read().decode())
    used = circuit.get("tokensUsedToday")
    check("A3 tokensUsedToday == 0（本轮零额度）", used == 0,
          f"tokensUsedToday={used} mode={circuit.get('llmMode')} budget={circuit.get('dailyTokenBudget')}")
except Exception as exc:  # noqa: BLE001
    # 第五轮双轴审查：网关不在跑时这一格原先判 FAIL，读起来像"零额度这条防线失效"，
    # 实际是前置不成立（这一项本来就要活体网关，干净克隆里没有）。改成具名 SKIP，与 polarity 的 exit 3 同族。
    check("A3 tokensUsedToday == 0（本轮零额度）", False,
          f"本机限定·前置不成立：读不到活体网关（{exc}）。零额度这条改由 A3b 离线钉。", skip=True)

# A3b：不依赖活体网关的零额度反证——本轮改动面里不得出现任何会花额度的评测命令入口。
#      （花钱那条路必须显式 `-Run`，见 ticket 16/20 与 ADR 0012 的熔断；这里钉的是"本轮没去碰它"。）
_dev_runs = sh(["git", "log", "--format=%H %s", f"{ROUND_FP}..HEAD"]).stdout.splitlines()
_spend = [l for l in _dev_runs if re.search(r"run-dev-guardcheck|--limit\s+180|dev 模式实测", l)]
# 提交数必须用 rev-list --count 取：原先拿 `len(log.split())//2` 凑，subject 里的空格会把它放大
# （第五轮双轴审查两轴同报：实跑打「共 17 笔」，`git rev-list --count` 是 7）。
_N_COMMITS = sh(["git", "rev-list", "--count", f"{ROUND_FP}..HEAD"]).stdout.strip()
check("A3b 本轮提交信息里没有花钱跑测的痕迹（零额度的离线反证）", not _spend,
       f"命中 {len(_spend)} 笔：{_spend[:3]}" if _spend else f"{ROUND_FP}..HEAD 共 {_N_COMMITS} 笔提交，无一含花钱跑测字样")

print()
print("=" * 78)
print("B. 判据与阈值未被改动（反刷分）")
print("=" * 78)

scorer = read(REPO / "scripts" / "run_tool_eval.py")
check("B1 ACCEPT_TOOL 仍是 0.95", "ACCEPT_TOOL = 0.95" in scorer)
check("B2 HARD_FAIL_INTENT 仍是 0.80", "HARD_FAIL_INTENT = 0.80" in scorer)

diff = sh(["git", "diff", "-U0", f"{FIXED_POINT}..HEAD", "--", "scripts/run_tool_eval.py"]).stdout
touched = [l for l in diff.splitlines()
           if l[:1] in "+-" and l[:3] not in ("+++", "---")
           and re.search(r"ACCEPT_TOOL|HARD_FAIL_INTENT|EVAL DONE", l)]
check("B3 第三轮未碰阈值/标记常量", not touched, f"命中 {len(touched)} 行")

touched = [l for l in diff.splitlines()
           if l[:1] in "+-" and l[:3] not in ("+++", "---")
           and re.search(r"^\+\s+tool_ok\s*=", l)]
check("B4 第三轮未新增第二处 tool_ok 赋值", not touched)

# tool_ok 的赋值点必须全部落在 judge() 内（judge 起于 def judge，止于下一个顶层 def）
judge_start = scorer.index("def judge(")
after = scorer.index("\ndef ", judge_start + 1)
# 用字符偏移定位，不用"整行文本再 index 一次"那种会撞到别处的写法（大小写与空白都敏感）
lines = scorer.splitlines(keepends=True)
cum = [0]
for ln in lines:
    cum.append(cum[-1] + len(ln))
assign_lines = [i for i, l in enumerate(lines, 1) if re.match(r"^\s+tool_ok = ", l)]
in_judge = [i for i in assign_lines if judge_start <= cum[i - 1] < after]
check("B5 唯一判据：tool_ok 赋值只在 judge() 内",
      len(assign_lines) == 2 and len(in_judge) == 2,
      f"赋值行 {assign_lines}，落在 judge()（偏移 {judge_start}-{after}）内的 {in_judge}")

# B6 的右端原先是 HEAD，于是「第三轮窗口」这个以某一轮命名的断言会跟着仓库一起长：
# 实现轮只改 CONTEXT.md 与新增 ADR 就让它当场判红（本轮实测），而那条改动根本不属于第三轮。
# 右端改钉到第四轮收口那一笔 PRE_FIX（`a6ccdcb`，复算：`git log -1 --format=%h a6ccdcb`），
# 白名单与判据面一字未动。这是 ADR 0023 那条「窗口逐轮重锚」的同病同治，不是放宽：
# 窗口闭起来之后，它回答的才正是它名字里那件事。
changed = sh(["git", "diff", "--name-only", f"{FIXED_POINT}..{PRE_FIX}"]).stdout.splitlines()
allow_prefix = ("README.md", ".scratch/shoppilot-mvp/", "docs/interview-qa.md", "docs/console.png", "scripts/up.ps1",
                "scripts/lib-launch.ps1", "eval/results/")
outside = [f for f in changed if not f.startswith(allow_prefix)]
check("B6 第三轮窗口改动面未越界（判据/gold/ADR/PLAN/CONTEXT/Java 零改动；注意白名单含 scripts 那两支 ps1）",
      not outside, f"越界文件：{outside}" if outside else f"{FIXED_POINT}..{PRE_FIX} 共 {len(changed)} 个文件，全在白名单内")
# B7：第五轮双轴审查抓到 P10 判据第 3 条那句「scripts/、eval/ 零改动由 B1-B6 与 F4 钉」是借来的保证——
#     B6 的白名单里就明列 scripts/up.ps1、scripts/lib-launch.ps1、eval/results/，它根本不放这条红线；
#     而且 B 组量的窗口是第三轮起点 11a12ac..HEAD，不是本轮。这里补一条真钉得住的：
#     窗口取本轮 fixed point `ROUND_FP`（第六轮抓到原先误用 PRE_FIX，多含第四轮三笔），
#     白名单是**具名文件**而不是 `.scratch/shoppilot-mvp/` 整目录，scripts/eval/src/docs 出现即红。
# 实现轮的产出就是 `src/` 里的 Java，路径白名单在这一轮不再承载任何含义，故按 ADR 0023 改成
# **内容级禁面 + 改动清单读数**。原口径（下面两行历史）与 `STRICT_ALLOW` 一并撤下：
# 第六轮 Standards 轴立的是「白名单必须是具体文件名，整目录前缀等于免检通道」，那条规矩在它唯一
# 有效的场景（纯文档轮）里仍然成立；纯文档轮若再需要这张表，五行代码加回，且那是有意加回。
# 留着它不删的另一条理由是本仓自己的家法 F2b：豁免表不许长出没人读的条目。
# 项数保持 95：这一条仍是一次 check 调用，两个子句合成一个谓词，改动清单降为 detail 里的读数。
# 已知缺口照登：`application.yml` 里的阈值（`perf-first-token-latency` 等）不在路径禁面内——
# 票 21/24 要往同一个文件加键，那条红线本轮只由「判据/阈值一字不动」这条铁律与本条的读数看着。
changed_now = sh(["git", "diff", "--name-only", f"{ROUND_FP}..HEAD"]).stdout.splitlines()
# 「既有 ADR」= 本轮起点那一刻就在库里的这些，新写的 ADR 不在禁面内（本窗口自己就在往里加）。
prior_adrs = {"docs/adr/" + n
              for n in sh(["git", "ls-tree", "--name-only", f"{ROUND_FP}:docs/adr"]).stdout.splitlines()}
# round17（ADR 0033）按设计往 eval/ 新增用例文件（part4-7）， blanket `eval/` 前缀会把本轮自己的
# 产物判成禁面。按 ADR 0023 的内容级本义收窄：红的是 gold 标注集（part1-3，判据所在）、
# 评测结果目录与三支量具脚本；新用例文件只增不改的原则由 C 组 gold 检查与读数看着。
GOLD_CASE_FILES = {"eval/cases-part1-policy.jsonl", "eval/cases-part2-action.jsonl",
                   "eval/cases-part3-edge.jsonl"}
# run_tool_eval.py 摘出禁面（票 34/35）：round17 的 judge() 扩 schema 与评测报告头字段是
# ADR 0033/0037 的设计内工作；judge 语义的机器防线由 CI 的 rescore 门禁承载（差异集合偏离即红）。
# verify_eval_judge.py（判据的断言载体）仍在禁面；gold 三文件原样钉着。
# eval/results/ 再收窄一次（票 39）：本义是"既有产物不许改/删"——新增一次评测的读数入仓
# 恰恰是 EVIDENCE 纪律要求的动作（指标与声明的原始产物层）。改用 --name-status 区分：
# 新增（A）放行，改动/删除（M/D/R）仍红。
changed_status = sh(["git", "diff", "--name-status", f"{ROUND_FP}..HEAD"]).stdout.splitlines()
changed_now = [line.split("\t", 1)[1] for line in changed_status if "\t" in line]
added_paths = {line.split("\t", 1)[1] for line in changed_status if line.startswith("A")}
forbidden = [f for f in changed_now
             if f in GOLD_CASE_FILES
             or (f.startswith("eval/results/") and f not in added_paths)
             or f.startswith(("knowledge/", "scripts/verify_eval_judge.py"))
             or f in prior_adrs]
check("B7 本轮窗口未碰内容级禁面（gold 与判据阈值所在文件、既有 ADR 出现即红；改动清单为读数）",
      not forbidden,
      (f"禁面命中 {forbidden}；" if forbidden else "禁面零命中；")
      + f"{ROUND_FP}..HEAD 改动 {len(changed_now)} 个文件：{'、'.join(changed_now) or '（无）'}")

print()
print("=" * 78)
print("C. gold 标注边界（放开 4 条，其余不动）")
print("=" * 78)

cases = gold_cases()
by_id = {c["id"]: c for c in cases}
LIST_IDS = {"ACT-ORD-09", "ACT-ORD-11", "ACT-ORD-16", "ACT-ORD-17"}
list_ids = {c["id"] for c in cases if isinstance(c["expect"].get("tool"), list)}
check("C1 列表形态 gold 恰 4 条", list_ids == LIST_IDS, f"实际 {sorted(list_ids)}")

ok = all(c["expect"]["tool"] == ["queryOrderDetail", "queryLogistics"] for c in cases if c["id"] in LIST_IDS)
note = all("对偶" in c["expect"].get("relabelNote", "") and "ticket 20" in c["expect"].get("relabelNote", "")
           for c in cases if c["id"] in LIST_IDS)
check("C2 4 条放开项合格答案集与 relabelNote 齐备", ok and note)

KEEP = ["ACT-ORD-13", "ACT-ADR-14", "ACT-RFD-14"]
not_opened = all(isinstance(by_id[i]["expect"].get("tool"), str) for i in KEEP)
check("C3 三条真错未放开（无对偶即不放开）", not_opened,
      "；".join(f"{i}={by_id[i]['expect'].get('tool')!r}" for i in KEEP))

dual = ["ACT-ORD-09", "ACT-LOG-09", "ACT-ORD-11", "ACT-LOG-11",
        "ACT-ORD-16", "ACT-LOG-15", "ACT-ORD-17", "ACT-LOG-17"]
check("C4 对偶 8 个 id 全在标注集里", all(i in by_id for i in dual),
      f"缺失 {[i for i in dual if i not in by_id]}")

TRIPLE = {"演示买家", "13800001234", "文三路 1 号"}
cross = [c for c in cases if c.get("kind") == "cross_tenant"]
full = [c for c in cross if set(c["expect"].get("mustNotLeak") or []) >= TRIPLE]
check("C5 8 条越权样本带满三件套", len(cross) == 8 and len(full) == 8,
      f"cross_tenant {len(cross)} 条，带满三件套 {len(full)} 条")

per_intent = {}
for c in cases:
    per_intent[c["intent"]] = per_intent.get(c["intent"], 0) + 1
adv = [c for c in cases if c.get("kind") != "normal"]
check("C6 180 条 / 十意图各 18 / 对抗 72",
      len(cases) == 180 and set(per_intent.values()) == {18} and len(per_intent) == 10
      and len(adv) == 72,
      f"总数 {len(cases)}，意图数 {len(per_intent)}，对抗 {len(adv)}")

tc_sha = sha256(REPO / "eval" / "tool-cases.jsonl")[:12]
check("C7 tool-cases.jsonl sha256 前 12 位 = a8c88525fa7c",
      tc_sha.lower() == "a8c88525fa7c", f"实测 {tc_sha.lower()}")

print()
print("=" * 78)
print("D. 取证命令复跑（全离线，0 token）")
print("=" * 78)


def run_py(args):
    # 子进程也钉 UTF-8：否则它按本机 GBK 码页写管道，这里按 utf-8 解码就得到一串 U+FFFD，
    # 于是"读数里必须含 72/180 = 40.0%"这类断言会因为**解码**而不是因为**事实**失败。
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    proc = subprocess.run([sys.executable, *args], cwd=str(REPO), capture_output=True,
                          text=True, encoding="utf-8", errors="replace", env=env)
    out = (proc.stdout or "") + (proc.stderr or "")
    return proc.returncode, out


rc, out = run_py(["scripts/run_tool_eval.py", "--selfcheck"])
check("D1 --selfcheck 退出码 0 且 ok=16", rc == 0 and "SCORER SELFCHECK ok=16" in out,
      f"rc={rc}；" + (out.strip().splitlines()[-1] if out.strip() else "无输出"))

rc, out = run_py(["scripts/build_eval_set.py"])
check("D2 build_eval_set 退出码 0、对抗 72/180=40.0%、无 FAIL",
      rc == 0 and "FAIL" not in out and "72/180 = 40.0%" in out,
      f"rc={rc}；" + (out.strip().splitlines()[-1] if out.strip() else "无输出"))

def eol_drift():
    """入库是 LF、检出被改写成 CRLF 的文件数。干净克隆 + `core.autocrlf=true` 的机器上必然 > 0，
    此时 `verify_eval_judge` 的换行符基线判红是**检出环境**造成的，不是防线失效（第五轮 Spec 轴记过这条）。"""
    rows = sh(["git", "ls-files", "--eol"]).stdout.splitlines()
    return sum(1 for r in rows if "i/lf" in r and "w/crlf" in r)


rc, out = run_py(["scripts/verify_eval_judge.py"])
totals = re.findall(r"合计 (\d+)/(\d+) 通过", out)
n_bad = len(re.findall(r"(?m)^FAIL  ", out))
_drift = eol_drift()
_d3_ok = rc == 0 and totals and totals[-1][0] == totals[-1][1] == "40" and n_bad == 0
if not _d3_ok and _drift:
    check("D3 verify_eval_judge 退出码 0 且 40/40 条断言全过", False,
          f"本机限定·检出环境不成立：{_drift} 个文件在索引里是 LF、检出后被改成 CRLF"
          f"（core.autocrlf=true 的克隆上必这样），跑器 rc={rc} 的换行符基线红是环境差异不是防线失效",
          skip=True)
else:
    check("D3 verify_eval_judge 退出码 0 且 40/40 条断言全过", _d3_ok,
          f"rc={rc}；合计行 {totals[-1] if totals else '未打印'}；FAIL 行 {n_bad}；换行符漂移 {_drift} 个文件")

# "未污染" = 取证复跑**没有新增**未跟踪/改动项；A2 那一刻已经在本轮改动里的文件不算它头上。
# 两条一起钉：零新增（真判据）、零消失（钉住上面那个按行解析的列结构，路径被截断时这里会红）。
_now = set(dirty_paths()) - OWN_ARTIFACTS
check("D4 取证复跑未污染工作树（相对 A2 快照零新增）", not (_now - BASELINE_DIRTY),
      f"新增 {sorted(_now - BASELINE_DIRTY)}")
check("D4b 工作树快照解析稳定（同一批脏文件不得改名/被截断）", _now == BASELINE_DIRTY,
      f"消失 {sorted(BASELINE_DIRTY - _now)}；新增 {sorted(_now - BASELINE_DIRTY)}")

RESCORE_STAMPED = RESULTS / "tool-eval-20260911-042142-rescore.csv"
inputs = ["tool-eval-20260910-080638-dev-budgetfix.csv", "tool-eval-20260910-080732-dev-budgetfix.csv",
          "tool-eval-20260910-080830-dev-budgetfix.csv", "tool-eval-20260910-080926-dev-budgetfix.csv",
          "tool-eval-20260910-080942-dev-budgetfix.csv", "tool-eval-20260910-075747-dev.csv"]
args = ["scripts/run_tool_eval.py", "--rescore", *[str(RESULTS / n) for n in inputs],
        "--rescore-expected-diff", "ACT-ORD-09,ACT-ORD-11,ACT-ORD-16,ACT-ORD-17"]
rc, out = run_py(args)
check("D5 --rescore 退出码 0（差异集合未越界）", rc == 0, f"rc={rc}")
check("D6 聚合两套读数 168/180=93.3% -> 172/180=95.6%",
      "168/180 = 93.3%" in out and "172/180 = 95.6%" in out)
check("D7 ORDER 行 72.2% -> 94.4%", "72.2%" in out and "94.4%" in out)
m = re.search(r"判据变动的样本：\[(.*?)\]", out)
got = sorted(x.strip().strip("'\"") for x in m.group(1).split(",")) if m else []
check("D8 差异集合恰 4 条", got == sorted(LIST_IDS), f"实测 {got}")

new_files = sorted(RESULTS.glob("tool-eval-*-rescore.csv"), key=lambda p: p.name)
produced = [p for p in new_files if p.name != RESCORE_STAMPED.name]
check("D9 本次复算产物与落盘产物字节级相同", bool(produced) and
      all(sha256(p) == sha256(RESCORE_STAMPED) for p in produced),
      f"新产物 {len(produced)} 份（名字带本次时间戳，故不写进读数产物，免得每跑一次就漂移一行）"
      f" sha={sha256(produced[0])[:16] if produced else '-'} vs 落盘 sha={sha256(RESCORE_STAMPED)[:16]}")
for p in produced:
    os.remove(p)
check("D10 复算临时产物已清理（不留未引用文件）",
      sorted(RESULTS.glob("tool-eval-*-rescore.csv")) == [RESCORE_STAMPED],
      f"现存 {[p.name for p in sorted(RESULTS.glob('tool-eval-*-rescore.csv'))]}")

print()
print("=" * 78)
print("E. 文档读数自洽（大小写敏感）")
print("=" * 78)

readme = read(REPO / "README.md")
both = [i for i, l in enumerate(readme.splitlines(), 1) if "93.3%" in l and "95.6%" in l]
check("E1 README 有 93.3%+95.6% 并列的行 >= 3", len(both) >= 3, f"行号 {both}")
missing = [i for i in dual if i not in readme]
check("E2 对偶 8 个 id 在 README 可 grep", not missing, f"缺 {missing}")
for banned in ["57/60", "95.0%", "168/180 = 95.6%", "172/180 = 93.3%"]:
    check(f"E3 README 无假读数 `{banned}`", banned not in readme)
# E4：第五轮双轴审查判这条**恒绿**——原先只问「合并」「否决」两个词在不在 43k 字的 README 里，
#     实测两词各出现 13 与 8 次，除非删光整份 README 否则永远不会红，而它的名字写的是"写明这条路被否决"。
#     现在钉的是那一行的实际措辞（三个特征同现），并配一支**必须能失败**的反证：把那一行删掉，判据必须变 False。
MERGE_DENY = ("合并", "否决", "queryOrderDetail")


def merge_denied(text):
    """README 里是否存在一行同时写明「合并 queryOrderDetail … 这条路被否决」（三个特征同现行）。"""
    return any(all(k in l for k in MERGE_DENY) for l in text.splitlines())


_deny_lines = [i for i, l in enumerate(readme.splitlines(), 1) if merge_denied(l)]
_stripped = "\n".join(l for l in readme.splitlines() if not all(k in l for k in MERGE_DENY))
check("E4 README 仍写明合并工具这条路被否决（钉同现行的三个特征，不是两个词各在不在）",
      bool(_deny_lines), f"命中行 {_deny_lines[:4]}")
check("E4b 对照组：把那一行删掉后 E4 的判据必须变 False（钉这条不是恒绿）",
      merge_denied(readme) and not merge_denied(_stripped),
      f"正本 True={merge_denied(readme)}；删掉同现行后 False={not merge_denied(_stripped)}（删了 {len(readme.splitlines()) - len(_stripped.splitlines())} 行）")

qa = read(REPO / "docs" / "interview-qa.md")
head_line = re.search(r"共 (\d+) 问，覆盖 (\d+) 个 ticket", qa)
body_q = qa.count("*Q：")
body_a = len(re.findall(r"(?m)^A：", qa))
# 换代指针（第十三轮票 21/22）：问答库从 91 涨到 93。
# 换代指针（v1.0 交接补录）：票 21-32 的 Handoff notes 全部进入生成源，问答库从 93 涨到 127，
# 覆盖从 20 个 ticket 扩到全部 32 个。与 G6 同一族：涨问答是文档产出，不跟着改会把新内容报成防线失效。
# 换代指针（round16 票 33）：票 33 的三个追问进入生成源，问答库从 127 涨到 130，覆盖从 32 扩到全部 33 个。
# 换代指针（round17）：票 34-41 与两张无编号票（style、readme 非目标成文）的追问进入生成源，
# 问答库从 130 涨到 157，覆盖从 33 扩到 42 个（含票 40 号位未开票、由 ADR 0040 承载的那一格）。
# 换代指针（2026-09-23 事实性修正）：round18 的票 42-44 收尾后本文件一直没重生成，头部停在
# 157/42；重生后 166/45。同期修掉票 14 追问里的一处错枚举（漏 INTENT_UNRESOLVED、又把主动
# 转人工计进「七种」），问答库与生成源重新逐字一致。
# 判据本身没放宽：头部计数、正文 Q 条目、A 条目三者仍必须逐字相等。
check("E5 问答库 166 问、头部计数 = 正文 Q 条目 = 答案条数",
      head_line is not None and int(head_line.group(1)) == 166 == body_q == body_a and body_a == 166,
      f"头部 {head_line.group(1) if head_line else '-'}，正文 Q {body_q}，A {body_a}")
check("E5b 问答库覆盖 45 个 ticket 且无缺收尾记录",
      head_line is not None and head_line.group(2) == "45" and "缺收尾记录" not in qa,
      f"头部行 {head_line.group(0) if head_line else '-'}；正文无缺收尾记录={'缺收尾记录' not in qa}")

ticket20 = read(REPO / ".scratch" / "shoppilot-mvp" / "issues" / "20-action-order-attribution.md")
# 185608 是 17/17 但让位的上一轮，只需在 ticket 20 留痕；200038/201808 是没拿全绿的两轮，README 也必须照登。
check("E6a 让位轮 185608 在 ticket 20 留痕", "185608" in ticket20,
      f"README {readme.count('185608')} 处，ticket20 {ticket20.count('185608')} 处")
for s in ["200038", "201808"]:
    check(f"E6b 未全绿那一轮 `{s}` 在 README 与 ticket 20 都可 grep 到",
          s in readme and s in ticket20,
          f"README {readme.count(s)} 处，ticket20 {ticket20.count(s)} 处")
check("E6c 落点轮 212011 在 README 与 ticket 20 都在",
      "212011" in readme and "212011" in ticket20)

print()
print("=" * 78)
print("F. 引用可解析 + 扫描器对照组")
print("=" * 78)

tracked = set(sh(["git", "ls-files"]).stdout.splitlines())
res_files = set(os.listdir(RESULTS))
log_files = set(os.listdir(LOGS)) if LOGS.exists() else set()

DOC_GLOBS = ["README.md", "PLAN.md", "CONTEXT.md",
             ".scratch/shoppilot-mvp/round3-plan.md",
             ".scratch/shoppilot-mvp/issues/16-tool-calling-eval.md",
             ".scratch/shoppilot-mvp/issues/20-action-order-attribution.md",
             "docs/adr/0021-action-order-gold-boundary-relabel-not-tool-merge.md",
             "docs/interview-qa.md"]
PATTERN = re.compile(r"(?:tool-eval|acceptance-run|clean-clone-check)-\d{8}-\d{6}[A-Za-z0-9\-]*")

refs, missing, missing_log, occurrences = set(), [], [], 0
# 两处「删除记录」引用：文档里写明是被删对象，不是依赖。豁免必须写死具体名字，不能放开整类。
DELETION_RECORDS = {"tool-eval-20260911-050015-local-smoke",
                    "tool-eval-20260911-181037-rescore"}


def resolvable(stem):
    """产物名能不能落到一个真实文件上——F1 / F1c / F2 共用的**唯一**谓词。
    第五轮双轴审查抓到原先 F2 把这段就地重写一遍（同一段复制三处），于是 F1 坏成"永远命中"时
    对照组仍然绿：对照组必须与被试组同源，否则它只是另一把没校过的尺子。"""
    if stem in DELETION_RECORDS:
        return True  # 删除记录不是依赖，具名豁免
    names = res_files if stem.startswith("tool-eval") else log_files
    return any(f == stem or f.startswith(stem + ".") or f.startswith(stem + "-") for f in names)


# 两类名字的可核面不同，必须分开钉：`tool-eval-*` 落在 eval/results/（入库），干净克隆里也核得动；
# `acceptance-run-*` / `clean-clone-check-*` 落在 logs/（不入库，H5 钉着），只有本机核得动。
# 混成一条会让"缺 logs 的克隆"整条判红（假红），整体豁免又会让漏名混过去（假绿）。
for doc in DOC_GLOBS:
    for m in PATTERN.finditer(read(REPO / doc)):
        stem = m.group(0)
        occurrences += 1
        refs.add(stem)
        if resolvable(stem):
            continue
        (missing if stem.startswith("tool-eval") else missing_log).append((doc, stem))

_eval_refs = sorted(r for r in refs if r.startswith("tool-eval"))
_log_refs = sorted(r for r in refs if not r.startswith("tool-eval"))
check("F1 文档里的 tool-eval 类产物名都指向在库文件（除 2 处写明是被删对象的删除记录）", not missing,
      f"MISSING：{missing}" if missing
      else f"{len(DOC_GLOBS)} 份文档共 {occurrences} 次 / 去重 {len(refs)} 名；tool-eval 类 {len(_eval_refs)} 名全可解析，"
           f"豁免命中 {sorted(r for r in refs if r in DELETION_RECORDS)}")
check_local("F1c 文档里的 logs 类产物名都指向本机 logs/（本机限定：logs 不入库）", [LOGS],
            lambda: (not missing_log,
                     f"logs 类 MISSING：{missing_log}" if missing_log
                     else f"logs 类 {len(_log_refs)} 名全部落在本机 logs/ 里"))

# F2/F2c：对照组必须**真调** F1 的那个谓词，而且两边都要能失败。
_probe_absent = "tool-eval-20260911-999999-local-smoke"
check("F2 对照组：不存在的产物名必须被 resolvable() 判不可解析", not resolvable(_probe_absent),
      f"探针 {_probe_absent} → resolvable={resolvable(_probe_absent)}")
_probe_present = "tool-eval-20260911-042142-rescore"
check("F2c 对照组：真实存在的产物名必须被同一个 resolvable() 判可解析（防 F2 单边恒绿）",
      resolvable(_probe_present), f"探针 {_probe_present} → resolvable={resolvable(_probe_present)}")
unused = [n for n in DELETION_RECORDS if n not in refs]
check("F2b 豁免表没有死条目（每条豁免都真的在文档里出现）", not unused, f"未命中的豁免 {unused}")

lower_probe = "plan.md"
exact = {p.rsplit("/", 1)[-1] for p in tracked} | res_files | log_files
check("F3 对照组：比对大小写敏感（plan.md 不得命中 PLAN.md）",
      lower_probe not in exact and "PLAN.md" in exact,
      f"PLAN.md 在库={('PLAN.md' in exact)}，plan.md 在库={(lower_probe in exact)}")

md_links = re.findall(r"\]\((?!http|codex:|#)([^)#\s]+)", readme)
broken = [t for t in md_links if not (REPO / t).exists()]
check("F4 README 相对链接全部可解析", not broken, f"断链 {broken}" if broken else f"共 {len(md_links)} 条")

print()
print("=" * 78)
print("G. 计划状态与门禁落点")
print("=" * 78)

plan = read(REPO / ".scratch" / "shoppilot-mvp" / "round3-plan.md")
# 计划里"状态"这一行有两种写法（`- 状态：**Pass**` 与 `- **状态**：**Pass（…**）`），
# 只认一种会把另一种漏掉（P9 就是被漏的那个），于是 G1 的"每段都有 Pass"永远差一格。这里两种都认。
STATUS_PAT = re.compile(r"(?mi)^- \*{0,2}状态\*{0,2}[：:]\*{0,2}(Pass|Pending)")
status_hits = STATUS_PAT.findall(plan)
pass_count = sum(1 for s in status_hits if s.lower() == "pass")
n_plan_steps = len(re.findall(r"(?m)^### P\d", plan))
pending = [s for s in status_hits if s.lower() == "pending"]
check("G1 计划里每个 `### Pn` 段都带 Pass 状态", pass_count == n_plan_steps,
      f"段数 {n_plan_steps}，Pass 计数 {pass_count}，pending 计数 {len(pending)}")
check("G2 收口后无 pending", len(pending) == 0, f"pending {len(pending)} 处")

# 下面这一整批读 logs/，而 logs/ 整目录不入库（H5 钉着）。第五轮双轴审查抓到：
# 原先它们直接 read()，干净克隆上抛 FileNotFoundError 把整轮审计打断，
# 于是「重跑=全绿」这句自述在别的机器上根本跑不到底。现在缺前置就逐条具名 SKIP，
# 形状与 verify-polarity.ps1 的 exit 3 同族——前置不成立既不算绿，也不算防线失效。
def _why(p):
    return (f"本机限定·前置不成立：缺 {Path(p).relative_to(REPO).as_posix()}"
            f"（logs/ 不入库，见文件头「本机当轮对账单」）")


G2B = "G2b 落点那轮极性守卫真被触发（blocked 计数器增量 = 1）"
pol = read_opt(LOGS / "acceptance" / "polarity.log")
if pol is None:
    check(G2B, False, _why(LOGS / "acceptance" / "polarity.log"), skip=True)
else:
    inc = re.findall(r"(?m)polarity_blocked_total 增量 = (\d+)", pol)
    check(G2B, inc == ["1"], f"polarity.log 读数 {inc}" if inc else "polarity.log 未打印 blocked 增量")

LANDING_LOG = LOGS / "acceptance-run-20260911-212011.log"
G3 = "G3 落点日志记 commit=9eede6d 且开跑时工作树 clean"
G4 = "G4 落点为 17 步全绿、总耗时 511s"
G5 = "G5 落点矩阵逐步读数（stack 80 / demo 12 / polarity 26 / eval 123）"
acc = read_opt(LANDING_LOG)
if acc is None:
    check(G3, False, _why(LANDING_LOG), skip=True)
    check(G4, False, _why(LANDING_LOG), skip=True)
    check(G5, False, _why(LANDING_LOG), skip=True)
    steps = {}
else:
    check(G3, any("commit=9eede6d" in l and "工作树=clean" in l for l in acc.splitlines()[:6]),
          acc.splitlines()[0] if acc.splitlines() else "空")
    check(G4, "全部步骤通过（17 步）" in acc and "总耗时 511s" in acc, acc.strip().splitlines()[-1])
    steps = {n: s for n, e, s in re.findall(r"(?m)^(\S+)\s+(\d+)\s+ok / (\d+)s", acc)}
    want_steps = {"stack": "80", "demo": "12", "polarity": "26", "eval": "123"}
    bad_steps = {k: steps.get(k) for k, v in want_steps.items() if steps.get(k) != v}
    check(G5, len(steps) == 17 and not bad_steps,
          f"步数 {len(steps)}，读数 {steps}" if bad_steps else f"17 步全 exit 0，关键四步 {want_steps}")
check_local("G5b README 索引行与落点矩阵同读数", [LANDING_LOG],
            lambda: (bool(steps) and "80s" in readme and "12s" in readme and "511s" in readme,
                     f"矩阵解析出 {len(steps)} 步；README 含 80s/12s/511s = "
                     f"{'80s' in readme and '12s' in readme and '511s' in readme}"))

G6 = G6_CLAIM
# 换代指针（第十三轮票 21 落点）：G3/G4/G5/G5b 钉的是第十二轮那份矩阵文件名，换轮不动它们；
# G6 读的却是 logs/acceptance/{build,unit}.log 这两份**每跑必覆盖**的最新日志，所以它天然跟着
# 最近一次门禁走。票 21 把网关单测从 89 加到 125（新增 DevDefaultsPolicy 等 36 条 + 静态页无凭证 2 条），
# 这一格不同步改数就会把「测试变多了」报成防线失效。见 ADR 0023 的逐轮重锚与 README 的票 21 落点段。
# 换代指针（第十三轮票 22 落点）：网关 125 涨到 134，新增的是 ConversationOwnershipTest 9 条
# （键含买家段、跨买家载不出/写不进/续办不了、旧键不迁移、无买家声明退化、买家标识不 trim、
# 正对照、静态页换身份清屏）。最后一条是收尾双轴审查抓出来的：键里那句 trim 会把 "C001" 与
# "C001 " 合成一把，而订单行侧的守卫把它们当两个买家——两道防线对"谁是同一个人"的判断就此分家。
# 同一条理由：这一格是当轮常数，不跟着改就把「测试变多了」报成防线失效。
# 换代指针（第十三轮票 23 落点）：网关 134 涨到 140，新增的是 DependencyHealthTest 6 条
# （readiness 成员一字未动、deps 恰好三格且与就绪门不相交、摘掉任一注册当场判错、
#  依赖全挂时三件事同时成立、引擎活着而语料 0 块时只有 knowledgeBase 红、静态页那颗灯改读 deps）。
# 本票没动任何一项判据形状，也没新增审计项：项数仍是 95。
unit_log, build_log = read_opt(LOGS / "acceptance" / "unit.log"), read_opt(LOGS / "acceptance" / "build.log")
if unit_log is None or build_log is None:
    check(G6, False, _why(LOGS / "acceptance" / "unit.log" if unit_log is None else LOGS / "acceptance" / "build.log"),
          skip=True)
else:
    SUM_RE = r"(?m)^\[INFO\] Tests run: (\d+), Failures: 0, Errors: 0, Skipped: 0$"
    unit_totals = [int(x) for x in re.findall(SUM_RE, unit_log)]
    build_totals = [int(x) for x in re.findall(SUM_RE, build_log)]
    check(G6, unit_totals == G6_EXPECT and build_totals == G6_EXPECT
          and sum(unit_totals) == sum(build_totals) == sum(G6_EXPECT),
          f"unit 模块小计 {unit_totals}，build 模块小计 {build_totals}")

G7 = "G7 冒烟日志首行 SCORER SELFCHECK ok=16、末行 EVAL DONE cases=24 errors=0"
eval_log = read_opt(LOGS / "acceptance" / "eval.log")
if eval_log is None:
    check(G7, False, _why(LOGS / "acceptance" / "eval.log"), skip=True)
else:
    check(G7, eval_log.splitlines()[0].strip() == "SCORER SELFCHECK ok=16"
          and "EVAL DONE cases=24 errors=0 mode=local limit=24" in eval_log.strip().splitlines()[-1],
          f"首行 {eval_log.splitlines()[0].strip()}；末行 {eval_log.strip().splitlines()[-1]}")

G8 = "G8 打字机断言落在 > 60 字这一真判据上（未放宽）"
console_log = read_opt(LOGS / "acceptance" / "console.log")
if console_log is None:
    check(G8, False, _why(LOGS / "acceptance" / "console.log"), skip=True)
else:
    cc = re.search(r"(\d+) chunks / (\d+) chars", console_log)
    check(G8, cc is not None and int(cc.group(2)) > 60,
          cc.group(0) if cc else "console.log 里找不到 chunks/chars 读数")

print()
print("=" * 78)
print("H. 文档自述与磁盘对拍（第四轮审查的教训固化成断言）")
print("=" * 78)


def gate_matrix(path):
    """按字段切分门禁日志的 step 表：返回 [(步名, 退出码, 备注)]。不用手写整行正则，防漏行。"""
    rows = []
    for line in read(path).splitlines():
        f = line.split()
        if len(f) >= 3 and re.fullmatch(r"\d+", f[1]) and " / " in line and f[0] != "step":
            rows.append((f[0], f[1], " ".join(f[2:])))
    return rows


def secs(step_rows, name):
    """从 step 表的备注 `ok / 69s` 里取秒数；取不到返回 None。"""
    for n, code, note in step_rows:
        if n == name:
            m = re.search(r"/ (\d+)s", note)
            return m.group(1) if m else None
    return None


ROUND_TS = ["121933", "123307", "185608", "200038", "201808", "212011"]


def build_rounds(logs_dir, stamps):
    """逐轮解析门禁矩阵。缺任何一份就返回 None（= 本机前置不成立）——
    不抛异常，也不返回空 dict 让下面的『零轮』看起来像全绿。"""
    out = {}
    for ts in stamps:
        p = Path(logs_dir) / f"acceptance-run-20260911-{ts}.log"
        if not p.exists():
            return None
        text = p.read_text(encoding="utf-8", errors="replace")
        rows = gate_matrix(p)
        head = text.splitlines()[0] if text.splitlines() else ""
        commit = re.search(r"commit=(\S+)", head)
        out[ts] = {
            "total": len(rows),
            "ok": sum(1 for r in rows if r[1] == "0"),
            "bad": [(r[0], r[1]) for r in rows if r[1] != "0"],
            "commit": commit.group(1) if commit else "?",
            "worktree": "dirty" if "dirty" in head else "clean",
        }
    return out


ROUNDS = build_rounds(LOGS, ROUND_TS)

if ROUNDS is None:
    _skip_detail = _why(LOGS / "acceptance-run-20260911-212011.log")
    check("H1 六轮门禁的 ok 步数与钉住的期望表逐轮相符", False, _skip_detail, skip=True)
    check("H1b 每轮都是 17 步（不是步数变少造成的『更绿』）", False, _skip_detail, skip=True)
    check("H1d README 里每一处 N/17 主张都等于该轮日志实算", False, _skip_detail, skip=True)
else:
    # claims 是**钉住的期望表**（当轮对账单的一部分），不是"文档主张"的抄本——文档那一侧由 H1d 真去读 README。
    claims = {"121933": 16, "123307": 17, "185608": 17, "200038": 16, "201808": 15, "212011": 17}
    wrong = {ts: (n, ROUNDS[ts]["ok"], ROUNDS[ts]["bad"]) for ts, n in claims.items() if ROUNDS[ts]["ok"] != n}
    check("H1 六轮门禁的 ok 步数与钉住的期望表逐轮相符（121933 曾被粗正则数成 15）", not wrong, f"不符 {wrong}")
    check("H1b 每轮都是 17 步（不是步数变少造成的『更绿』）",
          all(v["total"] == 17 for v in ROUNDS.values()),
          str({k: v["total"] for k, v in ROUNDS.items() if v["total"] != 17}))
    # H1d：第五轮双轴审查判 H1 是**第二把尺子**——注释写着「README 里每一句 N/17」，代码却从不读 README，
    #      只把脚本里手抄的字典与日志比；README 改数或字典改数各走各路都能绿。这里真去 README 抽主张：
    #      同一行里既有 `acceptance-run-<ts>` 又有 `N/17` 才算一处（README 别处的 `ACT-ORD-09/16/17` 不会误命中）。
    readme_claims = {}
    for l in readme.splitlines():
        ts_hit = re.findall(r"acceptance-run-20260911-(\d{6})", l)
        n_hit = re.findall(r"(\d+)/17", l)
        if len(ts_hit) == 1 and len(n_hit) == 1:
            readme_claims[ts_hit[0]] = int(n_hit[0])
    bad_claims = {ts: (readme_claims[ts], ROUNDS.get(ts, {}).get("ok")) for ts in readme_claims
                  if ROUNDS.get(ts, {}).get("ok") != readme_claims[ts]}
    check("H1d README 里每一处 N/17 主张都等于该轮日志实算（真读 README，不靠脚本内抄本）",
          len(readme_claims) >= 3 and not bad_claims,
          f"从 README 抽出 {len(readme_claims)} 处 {readme_claims}；与日志实算不符 {bad_claims}")


def find_pair(logs_dir, want_stack, want_demo):
    """在落盘矩阵里找 stack/demo 成对等于给定秒数的那些份。logs 不在 → 返回 None（不适用）。"""
    d = Path(logs_dir)
    if not d.is_dir():
        return None
    hits = []
    for p in sorted(d.glob("*.log")):
        if not p.name.startswith("acceptance-run"):
            continue
        rows = gate_matrix(p)
        if secs(rows, "stack") == want_stack and secs(rows, "demo") == want_demo:
            hits.append(p.name)
    return hits


# J0：上面那句「缺 logs 走 SKIP 而不是崩」本身必须有反证，否则它又是一句不可复核自述。
#     第六轮 Standards 轴抓到第一版 J0 只喂了 `build_rounds` 一个入口，而本机限定其实有**四个**入口
#     （build_rounds / find_pair / read_opt / check_local 的 local_missing）——只钉一个等于其余三个没校。
#     每个入口都按「干净克隆上的真实形状」喂：目录/文件根本不存在 → 必须返回不适用（None / 报缺），
#     不得抛异常。find_pair 多喂一种形状：目录在但空 → 返回空列表而不是崩，因为那种情况下「没有命中」
#     本身就是 H2 该判红的事实，不该被冒充成前置不成立。
_tmp_empty = tempfile.mkdtemp(prefix="shoppilot-audit-J0-")
_tmp_absent = str(Path(_tmp_empty) / "no-logs-dir")
_j0_hits, _j0_detail = [], []
try:
    _ghost = Path(_tmp_empty) / "acceptance-run-20260911-212011.log"

    def _j0_probe(_tag, _call, _is_not_applicable):
        try:
            _r = _call()
        except Exception as exc:  # noqa: BLE001
            _j0_detail.append(f"{_tag} 抛了 {type(exc).__name__}: {exc}（这就是原先干净克隆上的死法）")
            return
        if _is_not_applicable(_r):
            _j0_hits.append(_tag)
        else:
            _j0_detail.append(f"{_tag} 返回 {type(_r).__name__}（不是该形状应有的『不适用/无命中』）")

    _j0_probe("build_rounds", lambda: build_rounds(_tmp_empty, ROUND_TS), lambda r: r is None)
    _j0_probe("find_pair", lambda: find_pair(_tmp_absent, "69", "13"), lambda r: r is None)
    _j0_probe("find_pair(目录在但空)", lambda: find_pair(_tmp_empty, "69", "13"), lambda r: r == [])
    _j0_probe("read_opt", lambda: read_opt(_ghost), lambda r: r is None)
    _j0_probe("local_missing", lambda: local_missing([_ghost]), lambda r: len(r) == 1)
finally:
    os.rmdir(_tmp_empty)
_j0_ok = len(_j0_hits) == 5
check("J0 对照组：四个本机限定入口（find_pair 两种形状）喂『缺前置』都必须返回不适用/报缺，不得抛异常", _j0_ok,
      f"通过 {_j0_hits}；问题 {'；'.join(_j0_detail) if _j0_detail else '无'}")
# J0b：本机限定这条路径有两个入口（read_opt / check_local），只钉一个等于另一半没校。
_j0b_missing = local_missing([LOGS / "acceptance-run-19700101-000000.log"])
_j0b_present = local_missing([REPO / "README.md"])
check("J0b 对照组：check_local 用的前置谓词必须『缺的报缺、在的报在』（与 J0 不同源就又是一把尺子）",
      bool(_j0b_missing) and not _j0b_present, f"缺文件 → {_j0b_missing}；README → {_j0b_present}")


# H2：全称否定句必须被证伪过——69s/13s 确实成对存在于 09-09 的某份矩阵（本机限定：读 logs/）
pair_hits = find_pair(LOGS, "69", "13")
if pair_hits is None:
    check("H2 本机确有 69s/13s 成对的落盘矩阵（故 README 不得写『与任何一份都不符』）",
          False, _why(LOGS / "acceptance-run5.log"), skip=True)
else:
    check("H2 本机确有 69s/13s 成对的落盘矩阵（故 README 不得写『与任何一份都不符』）",
          pair_hits == ["acceptance-run5.log"], f"命中 {pair_hits}")
# 那句全称否定是假的，但订正后要把它作为"我曾经说过头"的反面教材留在原地，所以不能简单判子串不存在：
# 判的是"它只许出现在带撤回标记的行里"。对照组用订正前那份 README（HEAD）证明这条断言抓的是真东西。
DENIAL = "与本机任何一份落盘矩阵都不符"
RETRACT = "说过头"
den_offenders = [l for l in readme.splitlines() if DENIAL in l and RETRACT not in l]
old_readme = sh(["git", "show", f"{PRE_FIX}:README.md"]).stdout
check("H2b README 里那句全称否定只许以撤回形式出现（对照组：订正前必须判红）",
      not den_offenders and DENIAL in old_readme,
      f"未挂撤回标记的命中 {len(den_offenders)} 行；订正前 README 含该句={DENIAL in old_readme}")

# H3：文档里的 file.py:NNN / file.ps1:NNN 行号引用必须与磁盘一致。
#     第五轮双轴审查判这一格**名不副实**三处，逐条改掉：
#       (1) 原先给 `up.ps1:58` 钉的期望串是 `"# "`——任何一行注释都满足，等于没钉。现在换成该行真内容。
#       (2) 原先只扫 3 份文档，而 F1 扫 8 份；在 PLAN/ADR/ticket 16 里新增一处行号引用它看不见。现在与 F1 同集合。
#       (3) 原先 `rglob(fname)` 取 candidates[0]，同名文件多命中时取哪份不确定。现在多命中即判红。
#     另外引用可以带**版本**：文档里那句讲的是订正前那份文件，就按订正前那份核（rev 非 None 时走 git show），
#     但文档必须自己点名是哪个 commit，否则读者无从复核——这条口径写进本票第 11 条。
CITE_PAT = re.compile(r"([A-Za-z0-9_\-]+\.(?:py|ps1|java)):(\d+)")
EXPECT_CITE = {
    "run_tool_eval.py": {"156": ("tool_ok = ", None), "160": ("tool_ok = ", None),
                         "421": ("def rescore_details", None)},
    "up.ps1": {"58": ("throw 'Ollama 未监听 11434", "e7c19ad^"),  # 订正前那份；订正后同一句在第 68 行
               "66": ("Start-ShoppilotService", None), "68": ("throw 'Ollama 未监听 11434", None),
               "71": ("ollama list", None)},
}


def cite_ok(doc, fname, lineno):
    """核一处行号引用：返回 None = 落得住，返回字符串 = 失败原因。"""
    entry = EXPECT_CITE.get(fname, {}).get(lineno)
    if entry is None:
        return f"{doc}: {fname}:{lineno} 未在钉住的引用表里"
    want, rev = entry
    if rev:
        text = sh(["git", "show", f"{rev}:scripts/{fname}"]).stdout
        if not text:
            return f"{doc}: {fname}:{lineno} 取不到 {rev} 那份"
    else:
        candidates = list((REPO / "scripts").rglob(fname))
        if not candidates:
            return f"{doc}: 找不到 {fname}"
        if len(candidates) > 1:
            return f"{doc}: {fname} 多命中 {len(candidates)} 份，取哪份不确定"
        text = read(candidates[0])
    lines = text.splitlines()
    n = int(lineno)
    if n > len(lines) or want not in lines[n - 1]:
        got = lines[n - 1][:40] if n <= len(lines) else "<超行>"
        return f"{doc}: {fname}:{n} 期望含 `{want}`，实为 `{got}`"
    return None


bad_cites = []
n_cites = 0
for doc in DOC_GLOBS:  # 与 F1 同一组文档，不再只扫三份
    for fname, lineno in CITE_PAT.findall(read(REPO / doc)):
        n_cites += 1
        why = cite_ok(doc, fname, lineno)
        if why:
            bad_cites.append(why)
check("H3 文档里的每一处行号引用都能在对齐的磁盘行上落住", not bad_cites,
      f"核 {n_cites} 处引用、覆盖 {len(DOC_GLOBS)} 份文档" + ("；" + "；".join(bad_cites[:4]) if bad_cites else ""))
# H3b 对照组：一个物理上不可能落住的引用必须被同一个 cite_ok() 判红，否则 H3 又是一条恒绿假防线。
_bogus = cite_ok("对照组", "run_tool_eval.py", "999999")
check("H3b 对照组：超行引用必须被 cite_ok() 判失败", _bogus is not None, f"探针 run_tool_eval.py:999999 → {_bogus}")

# H4：可复现物计数与留库理由
block = ticket20.split("## 取证命令")[1].split("```")[1]
ncmd = sum(1 for l in block.splitlines() if l.strip().startswith("python "))
stated = re.findall(r"（\*\*(\d+) 条取证命令\*\*|「取证命令」那\s*(\d+)\s*条", ticket20)
nstated = max([int(a or b) for a, b in stated] or [0])
check("H4 ticket 20 取证命令条数自述与实数一致（4 条 python + 1 条零额度复读，自述 4）",
      ncmd == 4 and nstated == 4,
      f"python 命令实数 {ncmd}（钉为 4：`--rescore` 那条是续行，不计），自述最高档 {nstated}（钉为 4：第四轮审查后"
      f"文档自己说「那 4 条 python 命令 + 1 条零额度复读」）")
# 钉的是那个**主张句式**（「README 索引行 + 第 11 条」），不是两个词的邻接：订正后的计划里
# "README 索引行"是以「这个理由已经过期」的形式出现的，用邻接正则会把撤回句也判成假防线。
KEEP_CLAIM = "README 索引行 + 第 11 条"
old_plan = sh(["git", "show", f"{PRE_FIX}:.scratch/shoppilot-mvp/round3-plan.md"]).stdout
check("H4b 计划不得再以『README 索引行』为理由留 185410，且 README 不引用它",
      KEEP_CLAIM not in plan and "185410" not in readme and KEEP_CLAIM in old_plan,
      f"订正后计划含主张句式={KEEP_CLAIM in plan}；README 命中 185410 {readme.count('185410')} 处；"
      f"订正前计划含主张句式={KEEP_CLAIM in old_plan}（对照组，必须为 True，否则本条是恒绿假防线）")

# H5：日志与截图不入库这件事，README 必须自己说清楚
tracked_set = set(sh(["git", "ls-files"]).stdout.splitlines())
check("H5 取证面口径与磁盘一致：logs/ 整目录不入库，docs/console.png 入库",
      not any(p.startswith("logs/") for p in tracked_set) and "docs/console.png" in tracked_set,
      f"logs 在库 {sum(1 for p in tracked_set if p.startswith('logs/'))} 个（应为 0）；"
      f"console.png 在库={'docs/console.png' in tracked_set}")
check("H5b README 把『门禁日志干净克隆里没有』这条口径写出来了",
      "干净克隆里没有" in readme)

# H6：计划开头的计数口径与实际步数一致；P9 的标题不得停在已经作废的"待用户处置"
n_steps = len(re.findall(r"(?m)^### P\d", plan))
m_steps = re.search(r"共 \*\*(\d+) 步\*\*（(P0-P\d+)", plan)
check("H6 计划的步数自述与实际 `### P\\d` 段数一致",
      m_steps is not None and int(m_steps.group(1)) == n_steps and m_steps.group(2) == f"P0-P{n_steps - 1}",
      f"实际 {n_steps} 段，自述 {m_steps.group(0) if m_steps else '读不到'}")
check("H6b P9 标题里那句已经作废的『待用户处置』已改（状态已是自发解决）",
      "待用户处置" not in plan)
n_bad_claim = len(re.findall(r"另有 \d+ 条可逐条打勾", plan))
# 允许它在"我已经删掉这句话"的说明里被引用，但不许当成现存的自述
bad_claim_lines = [l for l in plan.splitlines() if "可逐条打勾" in l
                   and not re.search(r"删掉|原来自述|原来那句", l)]
check("H6c 计划里那句不可复核的『N 条可逐条打勾的动作项』自述已删（盘上无复选框形状）",
      not re.search(r"另有 \d+ 条可逐条打勾", plan) and not bad_claim_lines,
      f"现存自述命中 {n_bad_claim} 处；未挂在删除说明里的提及 {len(bad_claim_lines)} 行")


# H8：两支一次性跑器的幂等守卫真在工作 —— 重跑必须"全 skip 且三份文档逐字节不变"。
#     这是 09-12 自己踩的坑：没守卫时重跑了一遍，把三段话贴成了重复内容。
DOC3 = ["README.md", ".scratch/shoppilot-mvp/round3-plan.md",
        ".scratch/shoppilot-mvp/issues/20-action-order-attribution.md"]


def digest3():
    return {d: sha256(REPO / d) for d in DOC3}


before = digest3()
rc1, out1 = run_py([".scratch/shoppilot-mvp/round3-doc-fix-pass1.py"])
rc2, out2 = run_py([".scratch/shoppilot-mvp/round3-doc-fix-pass2.py"])
after = digest3()
check("H8 一次性跑器可安全重跑（退出码 0、新应用 0 处、三份文档 sha 逐字节不变）",
      rc1 == 0 and rc2 == 0
      and "新应用 0" in out1 and "新应用 0" in out2
      and before == after,
      f"rc={rc1}/{rc2}；末行 {out1.strip().splitlines()[-1][:38] if out1.strip() else '?'} / "
      f"{out2.strip().splitlines()[-1][:38] if out2.strip() else '?'}；sha 变化 "
      f"{[d for d in DOC3 if before[d] != after[d]]}")

def _rm_force(path):
    """删临时目录：`.git/objects` 里的文件是只读的，Windows 上要先摘掉只读位再删。"""
    def _onerr(func, p, exc):
        try:
            os.chmod(p, 0o777)
            func(p)
        except OSError:
            pass
    shutil.rmtree(path, onerror=_onerr)


# H8b：H8 只在这台机器的 LF 工作树上跑过（`git ls-files --eol` 可查三份文档 w/lf），
#      而 `core.autocrlf=true` 的干净克隆检出是 CRLF。第六轮干净克隆复测实抓到：
#      改前 pass1 在 CRLF 上误判 4 处「漂移」并**误应用 1 处**（跑器自己把 CRLF 悄悄改写成 LF）。
#      这里把三份文档按 CRLF 复刻进临时目录，连跑器一起在那儿跑，判「零新应用、零失败、字节不变」。
_crlf_dir = Path(tempfile.mkdtemp(prefix="shoppilot-audit-H8b-"))
_crlf_before, _crlf_notes = {}, []
try:
    for _d in DOC3:
        _src = (REPO / _d).read_bytes().replace(b"\r\n", b"\n")
        _dst = _crlf_dir / _d
        _dst.parent.mkdir(parents=True, exist_ok=True)
        _dst.write_bytes(_src.replace(b"\n", b"\r\n"))
        _crlf_before[_d] = sha256(_dst)
    for _r in ("round3-doc-fix-pass1.py", "round3-doc-fix-pass2.py"):
        shutil.copyfile(REPO / ".scratch/shoppilot-mvp" / _r, _crlf_dir / ".scratch/shoppilot-mvp" / _r)
    _env = dict(os.environ, PYTHONIOENCODING="utf-8")
    _rcs = []
    for _r in ("round3-doc-fix-pass1.py", "round3-doc-fix-pass2.py"):
        _p = subprocess.run([sys.executable, "-X", "utf8", f".scratch/shoppilot-mvp/{_r}"],
                            cwd=str(_crlf_dir), capture_output=True, text=True,
                            encoding="utf-8", errors="replace", env=_env)
        _o = (_p.stdout or "") + (_p.stderr or "")
        _rcs.append(_p.returncode)
        _last = _o.strip().splitlines()[-1] if _p.stdout.strip() else "无输出"
        if "新应用 0" not in _last or "失败 0" not in _last:
            _crlf_notes.append(f"{_r}: {_last}")
        if "CRLF 数由" in _o:
            _crlf_notes.append(f"{_r} 改写了换行形态：" +
                               "；".join(l.strip() for l in _o.splitlines() if "CRLF 数由" in l))
    _changed = [d for d in DOC3 if _crlf_before[d] != sha256(_crlf_dir / d)]
    if _changed:
        _crlf_notes.append(f"字节变了：{_changed}")
    check("H8b 一次性跑器在 CRLF 检出上同样可安全重跑（H8 只覆盖本机 LF，这是另一半形状）",
          _rcs == [0, 0] and not _crlf_notes,
          "；".join(_crlf_notes) if _crlf_notes
          else f"两份末行均「新应用 0、失败 0」，rc={_rcs}，三份 CRLF 文档 sha 逐字节不变")
finally:
    _rm_force(_crlf_dir)

# H8c：H8b 只覆盖「全部 skip」这一种形状，那条**写回**路径（真应用替换时把内容还原成该文件原有的
#      CRLF）一次都没被走到——ce7 的 CE-b 就是这么露出来的：把写回还原拆掉，H8b 照样绿。
#      这里拿订正前那份 README（PRE_FIX）复刻成 CRLF 喂 pass1，逼它真应用若干处，
#      判「至少应用 1 处」且「写回后每个换行仍是 CRLF」，把那条分支也钉住。
_crlf_dir2 = Path(tempfile.mkdtemp(prefix="shoppilot-audit-H8c-"))
try:
    _old_readme = sh(["git", "show", f"{PRE_FIX}:README.md"]).stdout.replace("\r\n", "\n")
    _crlf_dir2.joinpath(".scratch/shoppilot-mvp/issues").mkdir(parents=True)
    _crlf_dir2.joinpath("README.md").write_bytes(_old_readme.replace("\n", "\r\n").encode("utf-8"))
    for _d in DOC3[1:]:  # 另两份用当前版，保证只有 README 上有真活要干
        _crlf_dir2.joinpath(_d).parent.mkdir(parents=True, exist_ok=True)
        _crlf_dir2.joinpath(_d).write_bytes(
            (REPO / _d).read_bytes().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n"))
    shutil.copyfile(REPO / ".scratch/shoppilot-mvp/round3-doc-fix-pass1.py",
                    _crlf_dir2 / ".scratch/shoppilot-mvp/round3-doc-fix-pass1.py")
    _p = subprocess.run([sys.executable, "-X", "utf8", ".scratch/shoppilot-mvp/round3-doc-fix-pass1.py"],
                        cwd=str(_crlf_dir2), capture_output=True, text=True,
                        encoding="utf-8", errors="replace",
                        env=dict(os.environ, PYTHONIOENCODING="utf-8"))
    _o = (_p.stdout or "") + (_p.stderr or "")
    _last = _o.strip().splitlines()[-1] if _o.strip() else "无输出"
    _m = re.search(r"\u65b0\u5e94\u7528 (\d+)", _last)
    _applied = int(_m.group(1)) if _m else 0
    _raw = _crlf_dir2.joinpath("README.md").read_bytes()
    _n2 = []
    if _applied < 1:
        _n2.append(f"跑器一处都没应用（末行「{_last}」），写回分支仍未被覆盖")
    if "CRLF \u6570\u7531" in _o:
        _n2.append("跑器自报换行形态被改写：" +
                   "\uff1b".join(l.strip() for l in _o.splitlines() if "CRLF \u6570\u7531" in l))
    _nl, _crlf_n = _raw.count(b"\n"), _raw.count(b"\r\n")
    if _nl != _crlf_n:
        _n2.append(f"写回后混入裸 LF：{_nl} 个换行里只有 {_crlf_n} 个是 CRLF")
    check("H8c 跑器在 CRLF 上真应用替换时，写回必须保住该文件原有的换行形态（H8b 走不到的那条分支）",
          not _n2, "\uff1b".join(_n2) if _n2
          else f"末行「{_last}」；README 写回后 {_crlf_n}/{_nl} 换行仍为 CRLF，裸 LF 0 个")
finally:
    _rm_force(_crlf_dir2)

# H10：文档里"跑器替换 N 处"这个数，必须等于两支一次性跑器**自己末行打印的**处数之和。
#      第四轮原先写的是「17 处 = 16 跑器 + 1 手工」，而 pass2 那 3 处从没进过任何一格的账；
#      这里不重新解析脚本源码（那是第二把尺子），直接用跑器自己打的读数当唯一真值。
_pass_tot = [int(m.group(1)) for m in
             re.finditer(r"共 (\d+) 处：新应用 (\d+)、已应用跳过 (\d+)、失败 (\d+)", out1 + "\n" + out2)]
_pass_fail = [int(m.group(1)) for m in
              re.finditer(r"共 \d+ 处：新应用 \d+、已应用跳过 \d+、失败 (\d+)", out1 + "\n" + out2)]
_runner_total = sum(_pass_tot)
_doc_stated = sorted({int(m.group(1)) for m in re.finditer(r"(\d+) 处跑器替换", readme + plan + ticket20)})
check("H10 文档自述的跑器替换处数 == 两支跑器末行实打处数之和",
      len(_pass_tot) == 2 and _doc_stated == [_runner_total] and not any(_pass_fail),
      f"跑器末行 {len(_pass_tot)} 行、合计 {_runner_total} 处（失败 {sum(_pass_fail)}）；"
      f"文档自述集合 {_doc_stated}；缺的 1 处是手工改的计数口径行，不计入本数")

# H9：长段落自我复制检测。取本次真实事故里那段的长度做下限（README 那段被贴两遍、454 字），
#     这里用 200 字的窗口扫：任何 >=200 字的连续块在同一文档里出现两次即红（短短语重复是正常行文，不算）。
#     第四轮订正：上面这版实现是**恒绿假防线**，两处缺陷叠加——
#       (1) range 上界写成 len-win 而不是 len-win+1：恰好两份、总长 400 的探针，第二份起点 i=200 落在界外；
#       (2) 更要命的是 step=50：重复块起点只要不在 50 的整数倍上（本次事故就是这种），两份逐字相同的窗
#           在采样网格里错位半格，永远配不上对，检测器整段瞎掉。
#     现在改成逐字扫描（step=1）+ 闭区间上界。命中下标必须**按全局连续段**归并：
#     一处复制事故在 step=1 下会产生一连串逐字相邻的命中（1794 字段贴两遍 = 1595 个），
#     归并后每个事故只剩一个起点。第四轮第一版是按"首次出现下标"分组的，等于没归并——
#     它把一次事故报成 1595 次，而注释写的是"避免刷出一串计数"，又是一句自述跑不过机器，照登。
def long_dups(text, win=200):
    seen, hits = {}, []
    for i in range(0, max(0, len(text) - win + 1)):
        blk = text[i:i + win]
        if not blk.strip():
            continue
        if blk in seen:
            hits.append(i)
        else:
            seen[blk] = i
    starts, prev = [], None
    for i in hits:                      # hits 天然递增，逐字相邻的归成一事故
        if prev is None or i != prev + 1:
            starts.append(i)
        prev = i
    return starts


dup_hits = {d: long_dups(read(REPO / d)) for d in DOC3}
check("H9 三份文档里没有 >=200 字的块自我复制（防重跑贴两遍那类事故）",
      not any(v for v in dup_hits.values()),
      {d: len(v) for d, v in dup_hits.items() if v})
# 对照组 1：检测器必须能抓到"真贴两遍"，否则 H9 是恒绿假防线。
#           探针不重打原文（重打就会抄错字），直接从盘上切 README 最长的长行——本次事故被贴两遍的就是这种整段长行。
_blk = max((l for l in readme.splitlines() if len(l) >= 400), key=len, default="")
_hits_same = long_dups(_blk + _blk)
check("H9b 对照组：README 真实长段落原样贴两遍必须被 H9 抓到",
      len(_blk) >= 400 and bool(_hits_same),
      f"取段 {len(_blk)} 字，贴两遍后命中 {len(_hits_same)} 处")
# 对照组 2：钉本轮订正的那个根因。旧实现只在 50 的整数倍上采样，第二份起点落在网格外时两份窗永远配不上对；
#           本次事故那段在 README 里的起点偏移是 33880 % 50 = 30，正落在网格外，所以旧检测器整段瞎掉。
_sep_n = next((k for k in range(1, 51) if (_blk and (len(_blk) + k) % 50 != 0)), 0)
_start2 = len(_blk) + _sep_n                      # 第二份起点，刻意不在 50 的整数倍网格上
_hits_off = long_dups(_blk + "、" * _sep_n + _blk)
check("H9c 对照组：重复块起点不在 50 字网格上时仍必须被抓到（钉 step=50 采样那个缺陷）",
      bool(_blk) and _sep_n > 0 and _start2 % 50 != 0 and bool(_hits_off),
      f"第二份起点偏移 {_start2}（%50={_start2 % 50}），命中 {len(_hits_off)} 处")

print()
print("=" * 78)
print("I. 自我指涉：审计自己的项数（必须最后算，N 要覆盖前面所有 check）")
print("=" * 78)
#
# 第四轮订正：这一段原先夹在 H6 与 H8 中间，于是 N 把后面新增的 H8 / H9 / H9b / H9c 全漏计了——
#   实跑 74 项、汇总自述 70 项，正是本文件开头立的规矩（每句自述必须能重跑出来）在这一格上破了。
# H11：圆号子项（① ② …）在同一个顶层条目内不得重复，且正文里「第 X 项」的引用必须有落点。
#      第四轮自己踩的：往本票第 10 条尾部追加一块时顺手写成 ⑪，而 ⑪ 早被「本轮最该记的一条是自己」占了，
#      同一条款里出现两个 ⑪，交叉引用的指向变得不确定。只认『行首空格 + 圆号 + 空格 + **』这种起条目形状——
#      行首裸圆号（换行落到行头的续行、句中标号）不算条目；第一版没这个限定时曾把三处正常行文误判成重复。
_CIRC = "\u2460-\u2473"
_item_re = re.compile("(?m)^ +([%s]) \\*\\*" % _CIRC)
_ref_re = re.compile("\u7b2c\\s*([%s])" % _CIRC)


def _num(c):
    return ord(c) - 0x2460 + 1


def circ_dups(doc_text):
    bad = []
    for it in re.split(r"(?m)^(?=\d+\. )", doc_text):
        seen = set()
        for m in _item_re.finditer(it):
            n = _num(m.group(1))
            if n in seen:
                bad.append(chr(0x2460 + n - 1))
            seen.add(n)
    return bad


_dup_marks = {d: circ_dups(read(REPO / d)) for d in DOC3}
check("H11 同一个顶层条目里的圆号子项不得重复",
      not any(v for v in _dup_marks.values()),
      {d: v for d, v in _dup_marks.items() if v})
# 集合两边必须同型：_item_re 抓到的若是 _num() 后的整数，与引用侧的字符相减等于永不移除，
# 这会把每一个合法引用都报成无落点（第一版就这么全红过一次的形状，这里按字符存）。
_all_marks = set()
for d in DOC3:
    _all_marks |= {m.group(1) for m in _item_re.finditer(read(REPO / d))}
_dangling = sorted({m.group(1) for d in DOC3 for m in _ref_re.finditer(read(REPO / d))} - _all_marks)
check("H11b 正文里每处「第 ①-⑳ 项」引用都落到真存在的条目上",
      not _dangling, f"\u65e0\u843d\u70b9\u7684\u5706\u53f7\u5f15\u7528 {_dangling}")

# H7：审计自己的项数。这里还有个自我指涉的坑要交代清楚：
#   文档里出现的「N 项机器断言」既可能是**本轮**的数（必须等于本次实跑数），
#   也可能是**上一版跑器**的历史数（22:5x 那次的 54 项，加 H 组之前，那是当时的真话）。
# 早期版本一律要求相等，结果是历史那一格永远判红——那是拿今天的尺子量昨天。
# 现在的形状：本轮数必须至少被引用一次；其余每个数所在行必须带历史标记，否则红。
N = len(PASSES) + len(FAILS) + len(SKIPS) + TAIL_CHECKS  # 末尾还会跑 TAIL_CHECKS 项，见文件头常量
HIST = re.compile(r"上一版|让位|加 H 组前|历史")
stated_lines = []
for doc in [readme, plan, ticket20]:
    for l in doc.splitlines():
        for m in re.finditer(r"(\d+) 项机器断言", l):
            stated_lines.append((int(m.group(1)), l))
stale = [(s, l[:40]) for s, l in stated_lines if s != N and not HIST.search(l)]
check("H7 本轮实跑项数被文档至少引用一次",
      any(s == N for s, _ in stated_lines),
      f"本次实际 {N} 项，文档自述集合 {sorted({s for s, _ in stated_lines})}")
check("H7b 文档里每个不等于本轮实数的项数，都带历史标记（不许裸着当现状）",
      not stale, f"无历史标记的异数 {stale}")

# H13：P10 判据第 5 条要求「三条驳回项各留机器反证（不是留一句『审查读错了』）」，
#      第五轮双轴审查判这一条只有 1/3 真有产物。这里把缺的两条补成可重跑断言：
#      (a)「`verify_eval_judge` 的 17 项断言在盘上不存在」——钉它只许以带幻影标记的形式出现；
#      (b)「Kant F5 报的 `up.ps1` 第 115 行引用」——钉这个引用在四份文档里零命中（它本来就不存在）。
vej = read(REPO / "scripts" / "verify_eval_judge.py")
PHANTOM = "17 项断言"
MARK = re.compile(r"幻影|不存在|零命中")
_ph_lines = [(d, i, l) for d in (".scratch/shoppilot-mvp/round3-plan.md",
                                 ".scratch/shoppilot-mvp/issues/20-action-order-attribution.md")
             for i, l in enumerate(read(REPO / d).splitlines(), 1) if PHANTOM in l]
_unmarked = [(d, i, l[:40]) for d, i, l in _ph_lines if not MARK.search(l)]
check("H13 反证：『17 项断言』既不在 verify_eval_judge 里，在文档里也只许以幻影标记的形式出现",
      PHANTOM not in vej and not _unmarked,
      f"跑器源码命中={PHANTOM in vej}；文档命中 {len(_ph_lines)} 行、其中无幻影标记 {len(_unmarked)} 行 {_unmarked[:2]}")
# 与 H3 同源：用同一个引用正则去扫，而不是另抄一份 `.count()`（第六轮 Standards 轴抓到那是第二把尺子）。
_ghost_hits = {d: len([m for m in CITE_PAT.finditer(read(REPO / d)) if m.group(0) == "up.ps1:115"])
               for d in DOC_GLOBS}
check("H13b 反证：被驳回的那处 `up.ps1` 第 115 行引用在 8 份文档里零命中（它本就不存在，不是漏核）",
      sum(_ghost_hits.values()) == 0,
      f"逐份命中 {dict((k, v) for k, v in _ghost_hits.items() if v)}" if sum(_ghost_hits.values())
      else f"{len(DOC_GLOBS)} 份文档用同一个 CITE_PAT 扫，零命中")

# H14：本轮反复自述「P10 判据本体一字未动」。第六轮 Standards 轴抓到这句在字面上不成立——
#      判据 3 与判据 5 的**括号内**引用确实被改过。所以这条断言钉的是可核的那一半：
#      去掉括号内容之后的**主干要求**必须与本轮 fixed point（ROUND_FP=`9d444c9`）逐字相同。
#      谁哪天改了某条判据的要求本身，这里就红。
_PAT_PAREN = re.compile(r"（[^（）]*）")


def _criteria_block(text, head):
    """取某一节「通过判据」下的编号条目。**第七轮 Standards 轴抓到第一版只收首行**——
    判据 3/5 的续行整体不进比对，把续行改成「阈值从 95% 降到 90%」照样绿，
    而本轮真正的改动恰好落在续行上。现在续行（缩进 >=3 且不是新的编号项/列表项）并进同一条。"""
    i = text.index(head)
    j = text.index("\n### ", i + 1) if "\n### " in text[i + 1:] else len(text)
    seg = text[i:j]
    k = seg.index("**通过判据**")
    out, cur = [], None
    for line in seg[k:].splitlines()[1:]:
        if re.match(r"^\s+\d+\.\s", line):
            if cur is not None:
                out.append(cur)
            cur = line.strip()
        elif cur is not None and re.match(r"^\s{3,}\S", line) and not line.strip().startswith(("-", "1.", "2.", "3.")):
            cur += "\n" + line.strip()
        elif cur is not None:
            out.append(cur)
            cur = None
    if cur is not None:
        out.append(cur)
    return out


_old_plan = sh(["git", "show", f"{ROUND_FP}:.scratch/shoppilot-mvp/round3-plan.md"]).stdout
try:
    _raw_old, _raw_new = _criteria_block(_old_plan, "### P10"), _criteria_block(plan, "### P10")
    _c_old = [_PAT_PAREN.sub("", l) for l in _raw_old]
    _c_new = [_PAT_PAREN.sub("", l) for l in _raw_new]
    _drift14 = [(a, b) for a, b in zip(_c_old, _c_new) if a != b]
    # 括号内容改了而主干没改的条数——**算出来的**，不是抄一句文案（第七轮抓到原先那句「确有 2 处」代码从没核过）。
    _paren = [n for n, (a, b) in enumerate(zip(_raw_old, _raw_new), 1)
              if a != b and _PAT_PAREN.sub("", a) == _PAT_PAREN.sub("", b)]
    check("H14 P10 六条判据的主干要求（含续行、去括号后）与本轮 fixed point 逐字相同",
          len(_c_old) == len(_c_new) == 6 and not _drift14,
          f"条数 {len(_c_old)}/{len(_c_new)}；主干漂移 {len(_drift14)} 条 {_drift14[:1]}" if (len(_c_old) != 6 or _drift14)
          else f"6 条主干（含续行）逐字相同；括号内容有 {len(_paren)} 条判据不同（第 {_paren} 条），"
               "按 P11 ⑥⑨ 记，不当『一字未动』引用")
except ValueError as exc:
    check("H14 P10 六条判据的主干要求（含续行、去括号后）与本轮 fixed point 逐字相同", False, f"取不到判据块：{exc}")

def h12_verdict(artifact_stdout, n_pass, n_fail, n_skip, n_total):
    """H12 的判据本体（纯函数，第七轮反证 H12b 直接打这颗，不再另抄一把尺子）。
    返回 (是否判绿, 说明)。三条一起才算绿：
      ① 产物末行四个数 == 本次实数（把 H12 自己那一格从两侧都摘掉后）；
      ② 产物末行内部自洽（PASS+FAIL+SKIP == 共 N 项）；
      ③ 本次除 H12 外零红，且产物自记的红名单只许是 H12 那一格（收敛途中的唯一合法形状）。"""
    lines = artifact_stdout.splitlines()
    # 第八轮自查抓到：原先两侧都用宽匹配（"H12" in l / startswith("H12")），而跑器里真有一个
    # 名字以 `H12b` 开头的 check —— 伪造的红名单只要写 `H12b ...` 就能冒充「H12 自己那一格」，
    # 顺带还把 `was_red` 骗成「H12 当时是红」。
    # Mendel（第八轮 Standards 轴）补第二刀：收紧时别退回去写字面量前缀 `"H12 "`——那等于把「自己那一格」
    # 绑死在名字字符串上，哪天改了 `_H12_NAME`，产物里合法的红名单就不再被认作自己，`was_red` 恒假，
    # 「收敛途中」那个形状当场长成恒红假判据（和 f6234c4 刚治过的那条同型）。按 `_H12_NAME` 的全名比；
    # 它在调用期才求值，定义顺序不影响。
    # 第九轮 Standards 轴（Euclid）补第三刀：`startswith(全名)` 仍然放过「全名 + 任意后缀」，而产物里
    # `FAIL -> ` 那一行就是逐字登记名（跑器只打印名字），所以这里按**全名等值**比；H12b 补第九格钉住
    # 「全名后面还带字」必须判红。
    _is_h12 = lambda s: s == _H12_NAME
    last = [l for l in lines if l.startswith("汇总：")]
    if not last:
        return False, "产物里没有汇总行"
    m = re.match(r"汇总：PASS (\d+)  FAIL (\d+)  SKIP (\d+)  共 (\d+) 项", last[-1])
    if not m:
        return False, f"末行形状不对：{last[-1][:40]}"
    got = tuple(int(x) for x in m.groups())
    art_fails = [l[len("  FAIL -> "):].strip() for l in lines if l.startswith("  FAIL -> ")]
    was_red = any(_is_h12(f) for f in art_fails)
    exp = (n_pass + (0 if was_red else 1), n_fail + (1 if was_red else 0), n_skip, n_total)
    ok = (got == exp and got[0] + got[1] + got[2] == got[3]
          and n_fail == 0 and all(_is_h12(f) for f in art_fails))
    return ok, (f"末行 {got} vs 应为 {exp}；产物里 H12 当时={'红' if was_red else '绿'}；"
                f"本次除 H12 外的红 {n_fail} 项；产物自记红名单 {art_fails or '无'}")


# 产物只读一次，H12 与 H15 共用同一份（读的是 HEAD 里那一份，不是工作树——工作树这份正被本次 Tee 边跑边写）。
committed_txt = sh(["git", "show", f"HEAD:{OWN_TXT}"])


# H15：入仓产物里 A1 记的 HEAD 必须是当前 HEAD 的祖先或本身。
#      第五轮 Spec 轴记的「产物永远比 HEAD 慢一笔」是真形状（自我指涉），但「慢一笔」不等于「可以记一个无关 sha」。
_a1 = [l for l in committed_txt.stdout.splitlines() if l.strip().startswith("HEAD=")]
_m15 = re.search(r"HEAD=([0-9a-f]{7,40})", _a1[0]) if _a1 else None
if not _m15:
    check("H15 入仓产物里 A1 记的 HEAD 是当前 HEAD 的祖先或本身（允许慢一笔，不许跑偏）", False,
          "产物里找不到 `HEAD=` 那一行")
else:
    _rec = _m15.group(1)
    _anc = sh(["git", "merge-base", "--is-ancestor", _rec, "HEAD"]).returncode == 0
    check("H15 入仓产物里 A1 记的 HEAD 是当前 HEAD 的祖先或本身（允许慢一笔，不许跑偏）", _anc,
          f"产物记 {_rec[:9]}，当前 HEAD {head[:9]}，祖先关系={_anc}")

# H12：第五轮双轴审查抓到——三处文档把「项数以 round3-closeout-audit.txt 末行为准」钉成权威，
#      可这份 txt 在脚本里只出现在文档字符串与豁免名单里，**没有任何断言读它**。
#      后果：改了跑器忘了重落产物，一份陈旧末行照样全绿入仓，那句"为准"是空的。
#      现在真去读 HEAD 里那份（不是工作树这份——它正被本次 Tee 边跑边写，读它会自我循环）。
_H12_NAME = "H12 入仓读数产物的末行与本次实跑逐字段相同（钉住那句『以末行为准』）"


def _run_h12():
    # P13 落盘期抓到：这一格原先**就地** check()，而 H12b、H16 在它之后才登记，
    # h12_verdict 拿到的是「跑到自己为止」的计数，比最终少两格 ⇒ exp 永远对不上，
    # 「PASS 95 FAIL 0」这个状态在数学上不可达。第六轮注释里刚写过「拿中间快照比末行是恒红假判据」，
    # 第七轮只把 H12 挪到「自己之前那一段之后」，没挪到「所有 check 之后」，同一个病换了更隐蔽的形状。
    # 正解：判定走 defer，落到 flush 阶段——此刻除 H12 自己以外全部登记完毕。
    # Mendel（第八轮 Standards 轴）：原先写死 `N - 1`，等于把「本轮只推迟一项、且那一项就是 H12」
    # 藏进判据里——再加一个 `defer()` 就自己炸自己。改成按实际推迟项数算。
    # 第九轮 Standards 轴（Euclid）补第四刀：`N - len(DEFERRED_NAMES)` 还隐含「H12 是**第一个**落地的推迟项」——
    # 干净克隆里再登记一个 defer 并让它先落地，实测 `reg=95 N=96 deferred=2` 当场炸，那是又一次过度自述。
    # 正解：只扣「此刻尚未落地的推迟项数」（含 H12 自己这一格），与本项在队列里的位置无关。
    _reg = len(PASSES) + len(FAILS) + len(SKIPS)
    _pending = len(DEFERRED_NAMES) - _DEF_LANDED
    assert _reg == N - _pending, (
        f"H12 要求「除尚未落地的推迟项之外全部登记完」：此刻登记 {_reg} 项、N={N}、"
        f"推迟 {len(DEFERRED_NAMES)} 项（其中已落地 {_DEF_LANDED}、尚未落地 {_pending}）。"
        f"要么有人把 _run_h12 改回就地调用（那时 H12b/H16 还没登记），要么有 check 加在了 flush 之后")
    if committed_txt.returncode != 0:
        check(_H12_NAME, False, f"取不到 HEAD:{OWN_TXT}（{committed_txt.stderr.strip()[:60]}）")
    elif SKIPS:
        # 入仓那份产物记的是**本机全绿那一跑**的末行；干净克隆上本次必然带 SKIP，两边不同源，
        # 判红就是把「本机当轮对账单」当成克隆可复现的防线——那正是第五轮 ① 刚治过的病。走 SKIP 三态。
        check(_H12_NAME, False,
              f"本机限定·本次有 {len(SKIPS)} 项 SKIP（缺 `logs/` 的形状），与入仓产物那份全绿末行不同源",
              skip=True)
    else:
        # 第六轮抓到两件事：(a) 只比 `共 N 项` 的话，一份 `FAIL 3` 的陈旧产物照样绿；
        #   (b) 拿中间快照去比末行永远对不上，那是恒红假判据。自指涉的正解：把 H12 自己那一格从两侧都摘掉再比。
        _ok12, _why12 = h12_verdict(committed_txt.stdout, len(PASSES), len(FAILS), len(SKIPS), N)
        check(_H12_NAME, _ok12, f"{_why12}（改了跑器或本轮状态变了就得重跑并重新落盘产物）")


defer(_H12_NAME, _run_h12)

# H12b：H12 的三条判据必须都能失败——直接拿合成输入打那颗纯函数，不用再造克隆。
#       第七轮 Standards 轴抓到「末行写着 FAIL 1（那 1 就是 H12）的陈旧产物照样能满足 H12」，
#       这一格就是把那个形状钉成可重跑断言；同时钉住收敛途中的合法形状仍然判绿（防它长成恒红）。
# 夹具里的项数由 N 推（第九轮 Standards 轴抓到原先把 94/93 抄死，真 N 一变这些合成输入就和调用点不变量
# 脱钩，夹具悄悄失去它本要钉的那个形状）。取法：整份夹具按「H12 那一格尚未登记」建模 —— 传给纯函数的
# n_total 与产物末行那个「共 X 项」都用 _FX = N-1，两侧仍满足真调用点的不变量「PASS+FAIL+SKIP+1 == n_total」。
# 真跑时 H12 已在表内、末行写的是共 N 项，N-1 是**此刻已登记的 check 数**，不是产物末行那个数（第十轮
# Standards 轴抓到上一版注释把这两件事混成一句「产物侧的总数恒为 N-1」，按盘是错的）。
_FX = N - 1
_H12_TAIL = f"汇总：PASS {_FX}  FAIL 0  SKIP 0  共 {_FX} 项"
def _mk(passes, fails, skip_line, fail_names=()):
    body = "".join(f"  FAIL -> {n}\n" for n in fail_names)
    return f"...\n{body}{skip_line}汇总：PASS {passes}  FAIL {fails}  SKIP 0  共 {_FX} 项\n"
_H12_CASES = [
    ("全绿产物 + 本次全绿", _mk(_FX, 0, _H12_TAIL), _FX - 1, 0, True),
    ("陈旧产物（末行项数落后六笔）", _mk(_FX - 6, 0, f"汇总：PASS {_FX - 6}  FAIL 0  SKIP 0  共 {_FX - 6} 项"), _FX - 1, 0, False),
    ("伪造：末行 FAIL 1、红名单里是 G1", _mk(_FX - 1, 1, "", ("G1 计划里每个",)), _FX - 1, 0, False),
    ("伪造：末行 FAIL 1、红名单只有 H12（收敛途中）", _mk(_FX - 1, 1, "", (_H12_NAME,)), _FX - 1, 0, True),
    ("本次另有红（A2）", _mk(_FX, 0, _H12_TAIL), _FX - 2, 1, False),
    ("末行四个数自相矛盾", _mk(_FX - 4, 0, f"汇总：PASS {_FX - 4}  FAIL 0  SKIP 0  共 {_FX} 项"), _FX - 1, 0, False),
    # P13 落盘期真实踩到的形状：H12 就地判、后面还有两颗没登记 ⇒ 送进来的计数比最终少两笔。
    # 这一格把「np 必须 == N-1」这个调用点不变量钉成可重跑断言，防它换个名字再长回来。
    ("本次计数少两笔（未 flush 的旧调用点形状）", _mk(_FX, 0, _H12_TAIL), _FX - 3, 0, False),
    # 第八轮自查抓到的宽匹配：伪造的红名单写 `H12b ...`（同样以 H12 开头）在旧代码里会同时骗过
    # `was_red` 与「红名单只许是 H12 那一格」两道，判成绿。收紧成 `H12 ` 前缀后这一格必须判红。
    ("伪造：红名单写 H12b（宽前缀匹配下的漏网形状）", _mk(_FX - 1, 1, "", ("H12b 对照组",)), _FX - 1, 0, False),
    # 第九轮 Standards 轴抓到的漏网形状：`startswith(全名)` 下「全名 + 后缀」照样被认作自己那一格。
    ("伪造：红名单写「全名 + 后缀」", _mk(_FX - 1, 1, "", (_H12_NAME + " 的变体",)), _FX - 1, 0, False),
    # 第十轮 Standards 轴抓到：上面九格里**只有判据①在承重**——从正本抠出 h12_verdict 做三组变异（摘掉
    # ②末行自洽、摘掉③的「本次除 H12 外零红」、摘掉③的「红名单只许是 H12」），九格判定一字不变。那等于
    # 名字里那句「三条都真能失败」只有 1/3 兑现，正是本票第 10 条 ⑫(a) 立的「一条从未判过红的防线等于没有
    # 防线」。下面三格各钉住一条：摘掉对应那半句，该格当场翻绿。
    ("承重②：末行四数与应为值逐字段相同、但自身不自洽（送进来的计数少两笔）", _mk(_FX - 2, 0, "", ()), _FX - 3, 0, False),
    ("承重③：本次另有红、末行四数与应为值逐字段相同且自洽", _mk(_FX - 1, 1, "", ()), _FX - 2, 1, False),
    ("承重③：红名单写全名+别人（收敛途中混进一条别人的红）", _mk(_FX - 1, 1, "", (_H12_NAME, "G1 计划里每个")), _FX - 1, 0, False),
    # 第十一轮 Standards 轴（Euclid）：本轮刚立的规矩立刻打到自己——`h12_verdict` 里那两条早退
    # （产物没有汇总行、末行形状不对）与「取 `last[-1]` 而不是 `last[0]`」这条，12 格里无一格能到达，
    # 三条防线从未判过红 = 没有防线（本票第 10 条 ⑫(a)）。下面三格各钉一条，摘掉对应那条必有格子判错。
    ("早退·产物里根本没有汇总行（截断的产物）", "...\n  FAIL -> G1 计划里每个\n", _FX - 1, 0, False),
    ("早退·末行形状不对（缺『共 N 项』那一段）", f"...\n汇总：PASS {_FX}  FAIL 0  SKIP 0\n", _FX - 1, 0, False),
    # 这一格要求判**绿**：产物里先后印过两条汇总行，前一条是旧的、最后一条才是本次的——取错下标就当场红。
    ("取末行·第一条汇总行陈旧、最后一条与本次相符（必须判绿）",
     f"...\n汇总：PASS 60  FAIL 0  SKIP 0  共 60 项\n{_H12_TAIL}\n", _FX - 1, 0, True),
    # 残余边界照登：`_mk` 恒写 SKIP 0、调用点恒传 n_skip=0（真跑时 SKIPS 非空走的是上面那条三态分支，
    # 根本到不了这颗纯函数）⇒ 判据①的第三个字段在夹具里永久空转。那是调用点不变量，不是漏网形状。
]
_H12_BAD = [(nm, got, exp) for nm, art, np_, nf, exp in _H12_CASES
            for got, _ in [h12_verdict(art, np_, nf, 0, _FX)] if got != exp]
check(f"H12b 对照组：H12 那颗纯函数对 {len(_H12_CASES)} 种合成产物必须按预期判绿/判红"
      f"（钉住第七轮补的三条、P13 落盘期的调用点不变量、两条早退与取末行下标各自都有承重格：摘掉任一条，必有格子当场判错）",
      not _H12_BAD, f"{len(_H12_CASES)} 格全部符合预期" if not _H12_BAD
      else "；".join(f"{nm}：判 {got}，应为 {exp}" for nm, got, exp in _H12_BAD))

# H16：文件头那份「本机限定项」清单是在跑任何 check 之前打出来的自述，两个方向都要核：
#      正向——实跑的每一项 SKIP 都必须在清单里（不许偷偷 SKIP 没声明的项）；
#      反向——清单里每一项都必须是本轮真跑过的项（删了断言却留着声明，就是这张表腐烂）。
#      第七轮 Standards 轴抓到原先只有正向，且本机 SKIP=0 时正向无事可查，等于恒绿。
#      本条自己是清单里唯一不可能被「跑过的项」覆盖的名字（它就在末尾），显式摘掉。
_H16 = "H16 本机限定清单两个方向都要对得上（声明 ⊇ 实跑 SKIP，且声明的每一项本轮真跑过）"
_norm = lambda x: re.sub(r"\s+", " ", x.split("（")[0]).strip()
_declared = {_norm(n) for n in LOCAL_ONLY}
_actual_skip = {_norm(x) for x in SKIPS}
# 推迟登记的项（H12）此刻还没落地，这里先按「稍后会跑」算进「跑过的项」。
# 第八轮 Standards 轴抓到：光靠末尾「登记数 == N」兜不住——冒名换掉 check 的名字，数量照样对得上。
# 真正的闸在下面 flush 处：逐项核 `DEFERRED_NAMES` 是否以自己的名字落地，不落地就当场炸。
_ran = {_norm(x) for x in ALL_NAMES + DEFERRED_NAMES if _norm(x) != _norm(_H16)}
_undeclared = sorted(_actual_skip - _declared)
_dead = sorted(_declared - _ran)
check(_H16, not _undeclared and not _dead,
      f"未声明就 SKIP {len(_undeclared)} 项 {_undeclared[:3]}；清单里本轮没跑过的死条目 {len(_dead)} 项 {_dead[:3]}"
      if (_undeclared or _dead)
      else f"声明 {len(LOCAL_ONLY)} 项、本次实跑 SKIP {len(_actual_skip)} 项、本轮共跑过 {len(_ran)} 项，两个方向都对得上")

# flush：把「要等最终计数」的判据落地。必须在末尾硬断言之前，否则 N 对不上。
_before = len(ALL_NAMES)
for _nm, _fn in DEFERRED:
    _fn()
    _DEF_LANDED += 1
assert len(ALL_NAMES) - _before == len(DEFERRED_NAMES), (
    f"登记了 {len(DEFERRED_NAMES)} 个推迟项，flush 只落地 {len(ALL_NAMES) - _before} 个")
# Mendel（第八轮 Standards 轴）抓到上面那条只比「数量」：把 _run_h12 里的 check(_H12_NAME, ...) 换成
# check("H12Q 冒名格", ...) 之后数量照样相等，而 H16 又把 DEFERRED_NAMES 当成「跑过」，
# 于是清单里那一格从没以自己的名字落地过，两侧却全绿。按名字逐个核才咬得住。
_misnamed = [nm for nm in DEFERRED_NAMES if nm not in ALL_NAMES[_before:]]
assert not _misnamed, (
    f"推迟项登记了名字却没以自己的名字落地（冒名/改名）：{_misnamed}")
# 第九轮 Standards 轴（Euclid）：两张表必须同步清空。原先只清 `DEFERRED`，`DEFERRED_NAMES` 留着旧名字，
# 下一个 `defer()` 一登记就和旧账混在一起，`_misnamed` 会去核一批根本不属于本轮的名字。
DEFERRED.clear()
DEFERRED_NAMES.clear()

# 硬断言放在**所有** check 之后：N 必须等于此刻的实际计数。
# 第五轮订正：原先这句注释写「以后若有人在 H7b 之后再加 check，这里会当场炸」是**说过头**——
# 那个 assert 站在 H7b 与汇总行之间，落在汇总行之后新增的项它根本看不见（恒真）。
# 现在 assert 就是最后一道，任何位置的追加都会当场炸；TAIL_CHECKS 也要同步改，否则同样炸。
assert len(PASSES) + len(FAILS) + len(SKIPS) == N, (
    f"N={N} 与实际 PASS {len(PASSES)} + FAIL {len(FAILS)} + SKIP {len(SKIPS)} 不符："
    f"新增 check 请同步改文件头的 TAIL_CHECKS（当前 {TAIL_CHECKS}）")

print(f"汇总：PASS {len(PASSES)}  FAIL {len(FAILS)}  SKIP {len(SKIPS)}  共 {N} 项")
if FAILS:
    for f in FAILS:
        print(f"  FAIL -> {f}")
if SKIPS:
    for s in SKIPS:
        print(f"  SKIP -> {s}")
print("=" * 78)
if FAILS:
    sys.exit(1)
sys.exit(3 if SKIPS else 0)
