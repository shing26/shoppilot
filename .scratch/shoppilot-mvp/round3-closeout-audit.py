# -*- coding: utf-8 -*-
"""ticket 20 第三轮收口审计（0 token，离线）。

重跑：`python .scratch/shoppilot-mvp/round3-closeout-audit.py`（退出码 0 = 全绿）。
读数产物：同目录 `round3-closeout-audit.txt`（用 `... | Tee-Object -FilePath` 落盘，随本轮一起入仓）。

规矩：所有文本比较一律大小写敏感（用 os.listdir / git ls-files 的精确集合，不用 Select-String 那种默认不敏感的比对）。
自带对照组：故意塞几个"必须被判成不存在"的探针，防止扫描器自己假绿。

钉的是当轮常数（落点轮、耗时、sha 前缀、104 用例数）。换落点就得同步改这些常数——
这是有意的：它是一份**当轮对账单**，不是长期防线；长期防线在 scripts/verify_eval_judge.py。
"""
import csv
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
RESULTS = REPO / "eval" / "results"
LOGS = REPO / "logs"
FIXED_POINT = "11a12ac"  # 第三轮起点

FAILS = []
PASSES = []


def check(name, ok, detail=""):
    line = f"{'PASS' if ok else 'FAIL'}  {name}"
    if detail:
        line += f"\n        {detail}"
    print(line, flush=True)
    (PASSES if ok else FAILS).append(name)


def sh(args):
    return subprocess.run(args, cwd=str(REPO), capture_output=True, text=True,
                          encoding="utf-8", errors="replace")


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest().upper()


def read(path):
    return Path(path).read_text(encoding="utf-8", errors="replace")


def gold_cases():
    out = []
    for name in ["cases-part1-policy.jsonl", "cases-part2-action.jsonl", "cases-part3-edge.jsonl"]:
        for line in (REPO / "eval" / name).read_text(encoding="utf-8").splitlines():
            if line.strip():
                out.append(json.loads(line))
    return out


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


porcelain_lines = sh(["git", "status", "--porcelain"]).stdout
porcelain = porcelain_lines.strip()
# 自排除名单：只允许审计**自己的读数产物**。Tee-Object 是边跑边写的，产物在跑的过程中必然处于未跟踪态，
# 不排除它就是永远自我判脏；能排掉自己，也就有了下面 A2b 这条防呆——这张表一旦长出文档或脚本路径就是免检通道。
OWN_ARTIFACTS = {".scratch/shoppilot-mvp/round3-closeout-audit.txt"}
_all = dirty_paths()
_kept = [l for l in _all if l not in OWN_ARTIFACTS]
_dropped = [l for l in _all if l in OWN_ARTIFACTS]
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
    check("A3 tokensUsedToday == 0（本轮零额度）", False, f"网关读取失败：{exc}")

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

changed = sh(["git", "diff", "--name-only", f"{FIXED_POINT}..HEAD"]).stdout.split()
allow_prefix = ("README.md", ".scratch/shoppilot-mvp/", "docs/interview-qa.md", "docs/console.png", "scripts/up.ps1",
                "scripts/lib-launch.ps1", "eval/results/")
outside = [f for f in changed if not f.startswith(allow_prefix)]
check("B6 第三轮改动面未越界（判据/gold/ADR/PLAN/CONTEXT/Java 零改动）",
      not outside, f"越界文件：{outside}" if outside else f"共 {len(changed)} 个文件，全在白名单内")

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


def run_py(args, want_exit=0):
    proc = sh([sys.executable, *args])
    out = (proc.stdout or "") + (proc.stderr or "")
    return proc.returncode, out


rc, out = run_py(["scripts/run_tool_eval.py", "--selfcheck"])
check("D1 --selfcheck 退出码 0 且 ok=16", rc == 0 and "SCORER SELFCHECK ok=16" in out,
      f"rc={rc}；" + (out.strip().splitlines()[-1] if out.strip() else "无输出"))

rc, out = run_py(["scripts/build_eval_set.py"])
check("D2 build_eval_set 退出码 0、对抗 72/180=40.0%、无 FAIL",
      rc == 0 and "FAIL" not in out and "72/180 = 40.0%" in out,
      f"rc={rc}；" + (out.strip().splitlines()[-1] if out.strip() else "无输出"))

rc, out = run_py(["scripts/verify_eval_judge.py"])
assert_line = [l for l in out.splitlines() if "40" in l and ("断言" in l or "PASS" in l)]
totals = re.findall(r"合计 (\d+)/(\d+) 通过", out)
n_bad = len(re.findall(r"(?m)^FAIL  ", out))
check("D3 verify_eval_judge 退出码 0 且 40/40 条断言全过",
      rc == 0 and totals and totals[-1][0] == totals[-1][1] == "40" and n_bad == 0,
      f"rc={rc}；合计行 {totals[-1] if totals else '未打印'}；FAIL 行 {n_bad}")

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
      f"新产物 {[p.name for p in produced]} sha={sha256(produced[0])[:16] if produced else '-'}"
      f" vs 落盘 sha={sha256(RESCORE_STAMPED)[:16]}")
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
check("E4 README 仍写明合并工具这条路被否决", "合并" in readme and "否决" in readme)

qa = read(REPO / "docs" / "interview-qa.md")
head_line = re.search(r"共 (\d+) 问，覆盖 (\d+) 个 ticket", qa)
body_q = qa.count("*Q：")
body_a = len(re.findall(r"(?m)^A：", qa))
check("E5 问答库 91 问、头部计数 = 正文 Q 条目 = 答案条数",
      head_line is not None and int(head_line.group(1)) == 91 == body_q == body_a and body_a == 91,
      f"头部 {head_line.group(1) if head_line else '-'}，正文 Q {body_q}，A {body_a}")
check("E5b 问答库覆盖 20 个 ticket 且无缺收尾记录",
      head_line is not None and head_line.group(2) == "20" and "缺收尾记录" not in qa,
      f"头部行 {head_line.group(0) if head_line else '-'}；正文无缺收尾记录={'缺收尾记录' not in qa}")

tickets = sorted((REPO / ".scratch" / "shoppilot-mvp" / "issues").glob("*.md"))
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

refs, missing, occurrences = set(), [], 0
# 两处「删除记录」引用：文档里写明是被删对象，不是依赖。豁免必须写死具体名字，不能放开整类。
DELETION_RECORDS = {"tool-eval-20260911-050015-local-smoke",
                    "tool-eval-20260911-181037-rescore"}
for doc in DOC_GLOBS:
    for m in PATTERN.finditer(read(REPO / doc)):
        stem = m.group(0)
        occurrences += 1
        refs.add(stem)
        if stem in DELETION_RECORDS:
            continue
        if stem.startswith("tool-eval"):
            hit = any(f == stem or f.startswith(stem + ".") or f.startswith(stem + "-") for f in res_files)
        else:
            hit = any(f == stem or f.startswith(stem + ".") or f.startswith(stem + "-") for f in log_files)
        if not hit:
            missing.append((doc, stem))
check("F1 文档里每个具体产物名都指向在库文件（除 2 处写明是被删对象的删除记录）", not missing,
      f"{len(DOC_GLOBS)} 份文档里出现 {occurrences} 次 / 去重 {len(refs)} 个不同名，豁免 {len(DELETION_RECORDS)} 条，"
      f"MISSING：{missing}" if missing
      else f"{len(DOC_GLOBS)} 份文档里出现 {occurrences} 次 / 去重 {len(refs)} 个不同名，MISSING 0；"
           f"豁免命中 {sorted(r for r in refs if r in DELETION_RECORDS)}")

probe = "tool-eval-20260911-999999-local-smoke"
hit = any(f == probe or f.startswith(probe + ".") or f.startswith(probe + "-") for f in res_files)
check("F2 对照组：不存在的产物名必须被判 MISSING", not hit)
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
statuses = dict(re.findall(r"### (P\d)[^\n]*\n(?:.*?\*\*状态\*\*：\*\*(\w+))?", plan, re.S) or [])
rows = re.findall(r"### (P\d)|状态：\*\*(Pass|pending)", plan)
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
pol = read(LOGS / "acceptance" / "polarity.log")
inc = re.findall(r"(?m)polarity_blocked_total 增量 = (\d+)", pol)
check("G2b 落点那轮极性守卫真被触发（blocked 计数器增量 = 1）",
      inc == ["1"],
      f"polarity.log 读数 {inc}" if inc else "polarity.log 未打印 blocked 增量")

acc = read(LOGS / "acceptance-run-20260911-212011.log")
check("G3 落点日志记 commit=9eede6d 且开跑时工作树 clean",
      any("commit=9eede6d" in l and "工作树=clean" in l for l in acc.splitlines()[:6]),
      acc.splitlines()[0] if acc.splitlines() else "空")
check("G4 落点为 17 步全绿、总耗时 511s",
      "全部步骤通过（17 步）" in acc and "总耗时 511s" in acc,
      acc.strip().splitlines()[-1])

steps = {n: s for n, e, s in re.findall(r"(?m)^(\S+)\s+(\d+)\s+ok / (\d+)s", acc)}
want_steps = {"stack": "80", "demo": "12", "polarity": "26", "eval": "123"}
bad_steps = {k: steps.get(k) for k, v in want_steps.items() if steps.get(k) != v}
check("G5 落点矩阵逐步读数（stack 80 / demo 12 / polarity 26 / eval 123）",
      len(steps) == 17 and not bad_steps,
      f"步数 {len(steps)}，读数 {steps}" if bad_steps else f"17 步全 exit 0，关键四步 {want_steps}")
check("G5b README 索引行与落点矩阵同读数",
      "80s" in readme and "12s" in readme and "511s" in readme)

unit_log = read(LOGS / "acceptance" / "unit.log")
build_log = read(LOGS / "acceptance" / "build.log")
SUM_RE = r"(?m)^\[INFO\] Tests run: (\d+), Failures: 0, Errors: 0, Skipped: 0$"
unit_totals = [int(x) for x in re.findall(SUM_RE, unit_log)]
build_totals = [int(x) for x in re.findall(SUM_RE, build_log)]
check("G6 surefire 3 + 12 + 89 = 104（build 与 unit 两份日志的 Results 段各自核过）",
      unit_totals == [3, 12, 89] and build_totals == [3, 12, 89]
      and sum(unit_totals) == sum(build_totals) == 104,
      f"unit 模块小计 {unit_totals}，build 模块小计 {build_totals}")

eval_log = read(LOGS / "acceptance" / "eval.log")
check("G7 冒烟日志首行 SCORER SELFCHECK ok=16、末行 EVAL DONE cases=24 errors=0",
      eval_log.splitlines()[0].strip() == "SCORER SELFCHECK ok=16"
      and "EVAL DONE cases=24 errors=0 mode=local limit=24" in eval_log.strip().splitlines()[-1],
      f"首行 {eval_log.splitlines()[0].strip()}；末行 {eval_log.strip().splitlines()[-1]}")

console_log = read(LOGS / "acceptance" / "console.log")
cc = re.search(r"(\d+) chunks / (\d+) chars", console_log)
check("G8 打字机断言落在 > 60 字这一真判据上（未放宽）",
      cc is not None and int(cc.group(2)) > 60,
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


ROUNDS = {}
for ts in ["121933", "123307", "185608", "200038", "201808", "212011"]:
    p = LOGS / f"acceptance-run-20260911-{ts}.log"
    rows = gate_matrix(p)
    head = read(p).splitlines()[0]
    commit = re.search(r"commit=(\S+)", head)
    ROUNDS[ts] = {
        "total": len(rows),
        "ok": sum(1 for r in rows if r[1] == "0"),
        "bad": [(r[0], r[1]) for r in rows if r[1] != "0"],
        "commit": commit.group(1) if commit else "?",
        "worktree": "dirty" if "dirty" in head else "clean",
    }

# H1：README 里每一句 "N/17" 都必须等于该轮日志实算的 ok 数（121933 曾被本审计的粗正则数成 15）
claims = {"121933": 16, "123307": 17, "185608": 17, "200038": 16, "201808": 15, "212011": 17}
wrong = {ts: (n, ROUNDS[ts]["ok"], ROUNDS[ts]["bad"]) for ts, n in claims.items() if ROUNDS[ts]["ok"] != n}
check("H1 六轮门禁的 ok 步数与文档主张逐轮相符", not wrong, f"不符 {wrong}")
check("H1b 每轮都是 17 步（不是步数变少造成的『更绿』）",
      all(v["total"] == 17 for v in ROUNDS.values()),
      str({k: v["total"] for k, v in ROUNDS.items() if v["total"] != 17}))

# H2：全称否定句必须被证伪过——69s/13s 确实成对存在于 09-09 的某份矩阵
pair_hits = []
for p in sorted(LOGS.glob("*.log")):
    if not p.name.startswith("acceptance-run"):
        continue
    rows = gate_matrix(p)
    if secs(rows, "stack") == "69" and secs(rows, "demo") == "13":
        pair_hits.append(p.name)
check("H2 本机确有 69s/13s 成对的落盘矩阵（故 README 不得写『与任何一份都不符』）",
      pair_hits == ["acceptance-run5.log"], f"命中 {pair_hits}")
# 那句全称否定是假的，但订正后要把它作为"我曾经说过头"的反面教材留在原地，所以不能简单判子串不存在：
# 判的是"它只许出现在带撤回标记的行里"。对照组用订正前那份 README（HEAD）证明这条断言抓的是真东西。
DENIAL = "与本机任何一份落盘矩阵都不符"
RETRACT = "说过头"
den_offenders = [l for l in readme.splitlines() if DENIAL in l and RETRACT not in l]
old_readme = sh(["git", "show", "HEAD:README.md"]).stdout
check("H2b README 里那句全称否定只许以撤回形式出现（对照组：订正前必须判红）",
      not den_offenders and DENIAL in old_readme,
      f"未挂撤回标记的命中 {len(den_offenders)} 行；订正前 README 含该句={DENIAL in old_readme}")

# H3：文档里的 file.py:NNN / file.ps1:NNN 行号引用必须与磁盘一致
CITE_PAT = re.compile(r"([A-Za-z0-9_\-]+\.(?:py|ps1|java)):(\d+)")
EXPECT_CITE = {
    "run_tool_eval.py": {"156": "tool_ok = ", "160": "tool_ok = ", "421": "def rescore_details"},
    "up.ps1": {"58": "# ", "66": "Start-ShoppilotService", "71": "ollama list"},
}
bad_cites = []
for doc in ["README.md", ".scratch/shoppilot-mvp/round3-plan.md",
            ".scratch/shoppilot-mvp/issues/20-action-order-attribution.md"]:
    for fname, lineno in CITE_PAT.findall(read(REPO / doc)):
        want = EXPECT_CITE.get(fname, {}).get(lineno)
        if want is None:
            bad_cites.append(f"{doc}: {fname}:{lineno} 未在钉住的引用表里")
            continue
        candidates = list((REPO / "scripts").rglob(fname))
        if not candidates:
            bad_cites.append(f"{doc}: 找不到 {fname}")
            continue
        lines = read(candidates[0]).splitlines()
        n = int(lineno)
        if n > len(lines) or want not in lines[n - 1]:
            got = lines[n - 1][:40] if n <= len(lines) else "<超行>"
            bad_cites.append(f"{doc}: {fname}:{n} 期望含 `{want}`，实为 `{got}`")
check("H3 文档里的每一处行号引用都能在对齐的磁盘行上落住", not bad_cites, "；".join(bad_cites[:4]))

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
old_plan = sh(["git", "show", "HEAD:.scratch/shoppilot-mvp/round3-plan.md"]).stdout
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
_n_long = sum(1 for l in readme.splitlines() if l.strip())
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
N = len(PASSES) + len(FAILS) + 2  # +2：H7 与 H7b 自己
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

# 硬断言：N 必须等于此刻的实际计数。以后若有人在 H7b 之后再加 check，这里会当场炸，
# 而不是像第四轮那样静默把新增项漏出自述。
assert len(PASSES) + len(FAILS) == N, (
    f"N={N} 与实际 {len(PASSES) + len(FAILS)} 不符：新增 check 不能放在 H7b 之后")

print(f"汇总：PASS {len(PASSES)}  FAIL {len(FAILS)}  共 {N} 项")
if FAILS:
    for f in FAILS:
        print(f"  FAIL -> {f}")
print("=" * 78)
sys.exit(1 if FAILS else 0)
