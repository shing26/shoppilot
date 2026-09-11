"""ticket 20 的验收证据跑一遍就够：判据形状、量具变异反证、校验器防呆、EOL。

为什么要有这个脚本：本票改的是"数字怎么量的"，而这类改动最坏的失败不是改错，是**改对了但没人能复核**。
所以每条判据都落成可重跑的断言，并且每条都同时给红绿两侧——恒绿的夹具和恒红的断言一样是摆设。
所有变异与注入都发生在临时目录的副本上，仓库文件只读。

用法: python scripts/verify_eval_judge.py
退出码 0 = 全部通过；1 = 有断言没成立。不发任何 HTTP 请求，不花模型额度。
"""

import csv
import os
import hashlib
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PARTS = ["eval/cases-part1-policy.jsonl", "eval/cases-part2-action.jsonl", "eval/cases-part3-edge.jsonl"]
SEED = "shoppilot-gateway/src/main/resources/intent-samples.json"
SEEDRUNNER = "shoppilot-biz-mock/src/main/java/com/shoppilot/bizmock/seed/SeedRunner.java"
SCORER = "scripts/run_tool_eval.py"
VALIDATOR = "scripts/build_eval_set.py"

RELAXED = {"ACT-ORD-09", "ACT-ORD-11", "ACT-ORD-16", "ACT-ORD-17"}
STILL_SINGLE = {"ACT-ORD-13", "ACT-ADR-14", "ACT-RFD-14"}
# 进度问法：这些说法在订单详情与物流轨迹之间本来就等价，是本轮认定对偶矛盾的词形依据。
PROGRESS = re.compile(r"到哪|发了没|是不是已经发出|签收|物流|快递|走到哪|哪一步")
# 全表含进度问法的 ACTION_ORDER 样本就这 5 条（第 5 条 ACT-ORD-14 的 gold 本来就是
# queryLogistics，指不出对偶，所以不放开）。这四个是本轮认下来的对偶配对。
PROGRESS_ORDERS = {"ACT-ORD-09", "ACT-ORD-11", "ACT-ORD-14", "ACT-ORD-16", "ACT-ORD-17"}
DUAL_PAIRS = {"ACT-ORD-09": "ACT-LOG-09", "ACT-ORD-11": "ACT-LOG-11",
              "ACT-ORD-16": "ACT-LOG-15", "ACT-ORD-17": "ACT-LOG-17"}
# 文档自述的两个数：`--selfcheck` 夹具条数、8 条越权样本用到的标记值。改它们必须同时改文档。
SELFCLAIM_FIXTURES = 16
EXPECTED_MARKERS = {"演示买家", "13800001234", "文三路 1 号"}

# 本仓库的换行符是分文件的，而且 `git diff --check` 在这里不是信号（README 的改文档规矩）。
# 这张表把"谁该是 CRLF、谁该是 LF"钉成机器断言——曾经把 run-dev-eval.ps1 从 LF 改成 CRLF
# 就造出 230 行假 diff，那种改动混进判据收口里会把整轮证据淹掉。
EOL_BASELINE = {
    "scripts/run_tool_eval.py": "crlf",
    "scripts/build_eval_set.py": "crlf",
    "scripts/verify_eval_judge.py": "crlf",
    "eval/cases-part1-policy.jsonl": "crlf",
    "eval/cases-part2-action.jsonl": "crlf",
    "eval/cases-part3-edge.jsonl": "lf",
    "eval/tool-cases.jsonl": "crlf",
    "PLAN.md": "crlf",
    "docs/interview-qa.md": "crlf",
    ".scratch/shoppilot-mvp/issues/12-write-idempotency-state-guard.md": "crlf",
    "scripts/run-dev-eval.ps1": "lf",
    "README.md": "lf",
    "CONTEXT.md": "lf",
    "docs/adr/0021-action-order-gold-boundary-relabel-not-tool-merge.md": "lf",
    ".scratch/shoppilot-mvp/issues/16-tool-calling-eval.md": "lf",
    ".scratch/shoppilot-mvp/issues/20-action-order-attribution.md": "lf",
    "shoppilot-biz-mock/src/test/java/com/shoppilot/bizmock/TenantIsolationAndIdempotencyTest.java": "lf",
}
BOM = b"\xef\xbb\xbf"

# 默认控制台是 GBK：不钉死 utf-8，取证脚本会在打印第一行中文时 UnicodeEncodeError 崩掉，
# 而它偏又是"没参与的人复核这轮判据"的唯一入口。父进程自己与所有子进程都按 utf-8 走。
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

import json  # noqa: E402  （放在常量后面只为让上面的清单先讲清判据形状）


def scaffold():
    root = Path(tempfile.mkdtemp(prefix="shoppilot-eval-judge-"))
    (root / "scripts").mkdir()
    (root / "eval").mkdir()
    (root / SEED).parent.mkdir(parents=True)
    for rel in [SCORER, VALIDATOR, *PARTS, SEED]:
        shutil.copy2(REPO / rel, root / rel)
    return root


def run(root, script, *args):
    proc = subprocess.run([sys.executable, f"scripts/{script}", *args], cwd=str(root),
                          capture_output=True, text=True, encoding="utf-8", errors="replace",
                          env={**os.environ, "PYTHONIOENCODING": "utf-8", "PYTHONUTF8": "1"})
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def function_body(text, name):
    """按顶层 def 切出函数体：用来把"判据只有一份"这类断言落在结构上而不是行数上。"""
    matched = re.search(rf"^def {name}\(.*?(?=^def |\Z)", text, re.M | re.S)
    return matched.group(0) if matched else ""


def load_callable(path, name):
    """把单个顶层函数抠出来执行：跨文件比对两份同形状实现时用，不引入 import 耦合。"""
    namespace = {}
    exec(function_body(Path(path).read_text(encoding="utf-8"), name), namespace)
    return namespace.get(name)


def py_set(text, name):
    """抠出一个顶层 `X = {...}` 字符串集合字面量。"""
    matched = re.search(rf"^{name} = \{{(.*?)\}}", text, re.M | re.S)
    return set(re.findall(r'"([^"]+)"', matched.group(1))) if matched else set()


def java_row_labels(java, decl):
    """取 `String[][] X = {{"代码", "中文名", ...}, ...};` 里每行第二个字面量。"""
    matched = re.search(rf"String\[\]\[\] {decl} = \{{(.*?)\}};", java, re.S)
    if not matched:
        return set()
    return set(re.findall(r'\{\s*"[^"]*",\s*"([^"]*)"[^}]*\}', matched.group(1)))


def java_tenant_names(java):
    matched = re.search(r"List<String\[\]> TENANTS = List\.of\((.*?)\);", java, re.S)
    if not matched:
        return set()
    return set(re.findall(r'new String\[\]\{"[^"]*", "([^"]*)",', matched.group(1)))


def eol_signature(path):
    data = Path(path).read_bytes()
    crlf = data.count(b"\r\n")
    return crlf, data.count(b"\n") - crlf, data.startswith(BOM)


def dual_partners(case, pool, accepted):
    """给一条 ACTION_ORDER 样本找对偶：同场景、同租户、同买家、两边都是进度问法，
    而对侧只认 queryLogistics、本侧却认 queryOrderDetail。

    口径要写清：判据是「同一形态的诉求被两侧标成不同默认工具」，不是「同一个订单号」——
    ACT-ORD-16/17 与各自对偶的单号本来就不同（一个查不到单、一个跨租户探别人的单）。
    """
    if not PROGRESS.search(case["query"]):
        return []
    if "queryOrderDetail" not in set(accepted(case["expect"])):
        return []
    return [other["id"] for other in pool
            if other["kind"] == case["kind"] and other["tenant"] == case["tenant"]
            and other["customer"] == case["customer"] and PROGRESS.search(other["query"])
            and set(accepted(other["expect"])) == {"queryLogistics"}]


class Ledger:
    def __init__(self):
        self.rows = []

    def check(self, name, ok, detail=""):
        self.rows.append(bool(ok))
        print(("PASS  " if ok else "FAIL  ") + name + (f"  [{detail}]" if detail else ""))
        return ok


def sub_line(path, needle, replacer, ledger, name):
    """按整行替换做注入：锚点不唯一就记一条 FAIL 并跳过，不静默改错地方。"""
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    hits = [i for i, line in enumerate(lines) if needle in line]
    if len(hits) != 1:
        ledger.check(name, False, f"注入锚点 {needle!r} 命中 {len(hits)} 行")
        return False
    lines[hits[0]] = replacer(lines[hits[0]])
    path.write_text("".join(lines), encoding="utf-8")
    return True


def mutation_red(ledger, name, edits):
    """把评分器退回被修掉的旧语义，selfcheck 必须变红；不变红说明夹具是摆设。"""
    work = scaffold()
    target = work / SCORER
    text = target.read_text(encoding="utf-8")
    for old, new in edits:
        if text.count(old) != 1:
            ledger.check(name, False, f"变异锚点不唯一：{old[:36]!r}")
            return
        text = text.replace(old, new)
    target.write_text(text, encoding="utf-8")
    code, out = run(work, "run_tool_eval.py", "--selfcheck")
    red = [line for line in out.splitlines() if line.startswith("SCORER CHECK FAILED")]
    ledger.check(name, code == 2 and red, f"exit={code}；{(red[0] if red else '夹具没抓住')[:70]}")


def validator_fail(ledger, name, needle, replacer):
    work = scaffold()
    if not sub_line(work / "eval/cases-part2-action.jsonl", needle, replacer, ledger, name):
        return
    code, out = run(work, "build_eval_set.py")
    fails = [line for line in out.splitlines() if line.startswith("FAIL")]
    ledger.check(name, code == 1 and fails, (fails[0] if fails else "校验器没拦住")[:80])


def main() -> int:
    ledger = Ledger()
    # 实验前后工作树必须一模一样：这里比对的是"有没有多出新的脏文件"，不是"干不干净"——
    # 本票自己的改动本来就是脏的，门禁要求的是干净，两件事别混。
    dirty_before = subprocess.run(["git", "-C", str(REPO), "status", "--porcelain"],
                                  capture_output=True, text=True).stdout

    # ---- 对照组：干净副本必须全绿，否则下面所有"红"都不作数 --------------------
    root = scaffold()
    code, out = run(root, "run_tool_eval.py", "--selfcheck")
    fixtures = re.search(r"ok=(\d+)", out)
    # 门槛取文档自述的那个数（16），不是 `--selfcheck` 自己的 ≥7：对照组要是只认"够 7 条"，
    # 删掉 9 条夹具它照样绿，README 与 ADR 里"16 条夹具"那句话就没人守了（第 4 处缺陷同型）。
    ledger.check("对照：干净副本量具自检绿，且夹具条数就是文档自述的 16",
                 code == 0 and fixtures and int(fixtures.group(1)) == SELFCLAIM_FIXTURES,
                 out.strip().splitlines()[-1] if out.strip() else f"exit={code}")
    code, out = run(root, "build_eval_set.py")
    ledger.check("对照：干净副本标注校验绿", code == 0 and "FAIL" not in out, f"exit={code}")
    counts = re.findall(r"(ACTION_\w+|POLICY_\w+|ESCALATE|UNKNOWN)\s+(\d+) 条", out)
    ledger.check("结构不变：10 意图各 18 条、总 180", len(counts) == 10 and all(int(n) == 18 for _, n in counts),
                 f"{len(counts)} 个意图")
    ratio = re.search(r"对抗样本 (\d+)/(\d+) = ([\d.]+%)", out)
    ledger.check("结构不变：对抗样本 72/180 = 40.0%",
                 bool(ratio) and ratio.group(1) == "72" and ratio.group(3) == "40.0%",
                 ratio.group(0) if ratio else "没打印")

    # ---- 判据形状（验收 1、2、3、12） ---------------------------------------
    scorer_text = (REPO / SCORER).read_text(encoding="utf-8")
    ledger.check("阈值常量未漂移：ACCEPT_TOOL=0.95、HARD_FAIL_INTENT=0.80",
                 "ACCEPT_TOOL = 0.95" in scorer_text and "HARD_FAIL_INTENT = 0.80" in scorer_text)
    judge_body = function_body(scorer_text, "judge")
    rescore_body = function_body(scorer_text, "rescore_details")
    # judge() 内部本来就有 if/else 两个分支各写一次 tool_ok，所以量的是"全在 judge 里"，
    # 不是"全文件只有一行"——后者会把正确的分支写法判成违规。
    ledger.check("判据只有一份：每处 tool_ok 赋值都在 judge() 体内",
                 bool(judge_body)
                 and len(re.findall(r"^\s+tool_ok = ", scorer_text, re.M))
                 == len(re.findall(r"^\s+tool_ok = ", judge_body, re.M)) > 0)
    ledger.check("重算不自带判据：rescore_details 里没有任何判据原料",
                 bool(rescore_body) and not any(
                     token in rescore_body for token in
                     ('" in names', "args_detail", "expectStatus", "mustNotLeak", "slotAsk",
                      "accepted_tools(")),
                 f"{len(rescore_body.splitlines())} 行")

    gold = {}
    for line in (REPO / "eval/tool-cases.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            case = json.loads(line)
            gold[case["id"]] = case
    relaxed = {cid for cid, case in gold.items() if isinstance(case["expect"].get("tool"), list)}
    ledger.check(f"放开恰好这 4 条：{sorted(RELAXED)}", relaxed == RELAXED, f"实际 {sorted(relaxed)}")
    still_single = {cid for cid in STILL_SINGLE if not isinstance(gold[cid]["expect"].get("tool"), list)}
    ledger.check("三条多诉求样本没被顺手放开", still_single == STILL_SINGLE)
    cross = [case for case in gold.values() if case["kind"] == "cross_tenant"]
    with_marker = [case for case in cross if case["expect"].get("mustNotLeak")]
    ledger.check("每条越权样本都带可判别的串号标记",
                 len(cross) == 8 and len(with_marker) == 8, f"{len(with_marker)}/{len(cross)}")
    # 标记的"值"也要钉住：词表对拍只保证禁词表不漂，保证不了标记本身还指向种子里真实存在的字段。
    # SeedRunner 把演示收件人改名而 gold 没跟着改，8 条串号断言会一夜之间变成恒绿摆设——
    # 那正是本轮第 2、4 处缺陷的形状（判不了的断言静默算通过）。
    marker_values = {m for case in cross for m in case["expect"].get("mustNotLeak") or []}
    java_seed_text = (REPO / SEEDRUNNER).read_text(encoding="utf-8")
    ghost_markers = sorted(m for m in marker_values if f'"{m}"' not in java_seed_text)
    ledger.check("每个串号标记的字面值都真存在于 SeedRunner 的演示订单里",
                 marker_values == EXPECTED_MARKERS and not ghost_markers,
                 f"{len(marker_values)} 个值；种子里没有：{ghost_markers}")
    thin = sorted(case["id"] for case in cross
                  if set(case["expect"].get("mustNotLeak") or []) != EXPECTED_MARKERS)
    ledger.check("8 条越权样本都同时盯着收件人三件套（读订单详情也会把地址带出来）",
                 not thin, f"缺标记：{thin}")

    # ---- 对偶矛盾的边界（验收 4、5；ADR 0021 第一段） --------------------------
    # 这一组是"重标而不是并工具"的正面证据：放开的每一条都指得出对偶，指不出的一律没放开。
    order_cases = [c for c in gold.values() if c["intent"] == "ACTION_ORDER"]
    log_pool = [c for c in gold.values() if c["intent"] == "ACTION_LOGISTICS"]
    scorer_accepted = load_callable(REPO / SCORER, "accepted_tools")
    progress_orders = {c["id"] for c in order_cases if PROGRESS.search(c["query"])}
    ledger.check("含进度问法的 ACTION_ORDER 样本恰好这 5 条",
                 progress_orders == PROGRESS_ORDERS, f"实际 {sorted(progress_orders)}")
    partners = {c["id"]: dual_partners(c, log_pool, scorer_accepted) for c in order_cases}
    with_dual = sorted(cid for cid, hit in partners.items() if hit)
    ledger.check("指得出对偶的样本恰好等于放开集（其余进度问法样本 gold 本就单指物流）",
                 with_dual == sorted(RELAXED), f"实际 {with_dual}")
    ledger.check("四组对偶逐组点名，且两侧问法都落在进度词形上",
                 all(DUAL_PAIRS[a] in partners[a] for a in RELAXED)
                 and all(PROGRESS.search(gold[cid]["query"])
                         for cid in [*RELAXED, *DUAL_PAIRS.values()]),
                 "；".join(f"{a}↔{b}" for a, b in sorted(DUAL_PAIRS.items())))
    ledger.check("ACT-ORD-14 指不出对偶：gold 本就是 queryLogistics，所以不放开",
                 not partners["ACT-ORD-14"]
                 and scorer_accepted(gold["ACT-ORD-14"]["expect"]) == ["queryLogistics"])
    ledger.check("放开的 4 条都是同场景同租户同买家的对偶，不是拿不同场景硬凑",
                 all(gold[a]["kind"] == gold[DUAL_PAIRS[a]]["kind"]
                     and gold[a]["tenant"] == gold[DUAL_PAIRS[a]]["tenant"]
                     and gold[a]["customer"] == gold[DUAL_PAIRS[a]]["customer"] for a in RELAXED))
    mockllm = (REPO / "shoppilot-gateway/src/main/java/com/shoppilot/gateway/llm/MockLlmClient.java")
    router = mockllm.read_text(encoding="utf-8") if mockllm.exists() else ""
    ledger.check("另一支柱：MockLlmClient 的路由词全被 PROGRESS 覆盖（到哪/签收→物流）",
                 all(PROGRESS.search(word) for word in ("物流", "快递", "到哪", "签收"))
                 and 'text.contains("到哪")' in router
                 and "QUERY_LOGISTICS" in router)

    # ---- 跨实现一致：判据形状与共享词表不许两份各说各话 ------------------------
    build_accepted = load_callable(REPO / VALIDATOR, "accepted_tools")
    ledger.check("两份 accepted_tools 实现行为一致（180 条逐个对拍）",
                 scorer_accepted is not None and build_accepted is not None
                 and all(scorer_accepted(c["expect"]) == build_accepted(c["expect"]) for c in gold.values())
                 and build_accepted({"tool": "queryLogistics"}) == ["queryLogistics"]
                 and build_accepted({"tool": None}) == [])
    java_seed = (REPO / SEEDRUNNER).read_text(encoding="utf-8")
    shared = py_set((REPO / VALIDATOR).read_text(encoding="utf-8"), "SHARED_VOCAB")
    ledger.check("SHARED_VOCAB 与 SeedRunner 的品类/快递商一字不差（抄写不许漂）",
                 shared == java_row_labels(java_seed, "CATEGORIES") | java_row_labels(java_seed, "CARRIERS"),
                 f"{len(shared)} 个词")
    shops = py_set((REPO / VALIDATOR).read_text(encoding="utf-8"), "SHOP_NAMES")
    ledger.check("SHOP_NAMES 与 SeedRunner 的租户店名一字不差",
                 shops == java_tenant_names(java_seed), f"实际 {sorted(shops)}")
    streets = py_set((REPO / VALIDATOR).read_text(encoding="utf-8"), "SHARED_STREETS")
    seeded_streets = set(re.findall(r'setDetailAddress\("([^"\s]+)', java_seed))
    ledger.check("SHARED_STREETS 覆盖 SeedRunner 落地址用的全部街道",
                 seeded_streets and seeded_streets == streets, f"种子里 {sorted(seeded_streets)}")

    # ---- 量具变异反证（验收 8、9） -------------------------------------------
    mutation_red(ledger, "变异：参数退回「工具没打对就拿空字典比、判成填错」→ selfcheck 必须红",
                 [('actual_args = link["args"] if args_comparable else {}',
                   'actual_args = link["args"] if link else {}'),
                  ('args_comparable = bool(args_scored and link is not None and obs.get("args_known", True))',
                   'args_comparable = bool(args_scored)')])
    mutation_red(ledger, "变异：expectStatus 退回「期望工具没打就静默给 True」→ selfcheck 必须红",
                 [('if link and link["status"] else ""', 'if link and link["status"] else True')])
    mutation_red(ledger, "变异：串号命中不再算硬失败 → selfcheck 必须红",
                 [("leak_ok is not False", "True")])
    mutation_red(ledger, "变异：缓存层空值退回「当成没入库白送一分」→ selfcheck 必须红",
                 [('admission_ok = (layer.upper() == "NONE") if layer else ""',
                   'admission_ok = (layer.upper() == "NONE")')])

    # ---- 校验器防呆（验收 18、19） -------------------------------------------
    validator_fail(ledger, "防呆：越权样本删掉 mustNotLeak → 必须 FAIL", '"id":"ACT-ORD-17"',
                   lambda l: l.replace(',"mustNotLeak":["演示买家","13800001234","文三路 1 号"]', ""))
    validator_fail(ledger, "防呆：标记用买家自己报的订单号 → 必须 FAIL", '"id":"ACT-RFD-17"',
                   lambda l: l.replace('"mustNotLeak":["演示买家","13800001234","文三路 1 号"]', '"mustNotLeak":["90001"]'))
    validator_fail(ledger, "防呆：标记用品类名（跨租户共享词）→ 必须 FAIL", '"id":"ACT-LOG-18"',
                   lambda l: l.replace('"mustNotLeak":["演示买家","13800001234","文三路 1 号"]', '"mustNotLeak":["服饰鞋包"]'))
    # 这两条是本轮真实踩过的坑：旧 gold 的「数码」正是 T001 招牌「数码旗舰店」的子串。
    validator_fail(ledger, "防呆：标记用店名子串（旧「数码」同型）→ 必须 FAIL", '"id":"ACT-ORD-17"',
                   lambda l: l.replace('"mustNotLeak":["演示买家","13800001234","文三路 1 号"]', '"mustNotLeak":["数码"]'))
    validator_fail(ledger, "防呆：标记用整串店名 → 必须 FAIL", '"id":"ACT-ORD-17"',
                   lambda l: l.replace('"mustNotLeak":["演示买家","13800001234","文三路 1 号"]', '"mustNotLeak":["生鲜超市"]'))
    validator_fail(ledger, "防呆：标记用「店名 + 更多字」的长串 → 必须 FAIL", '"id":"ACT-ORD-17"',
                   lambda l: l.replace('"mustNotLeak":["演示买家","13800001234","文三路 1 号"]', '"mustNotLeak":["T001生鲜超市"]'))
    validator_fail(ledger, "防呆：标记用裸街道名（种子单共用文三路）→ 必须 FAIL", '"id":"ACT-LOG-18"',
                   lambda l: l.replace('"mustNotLeak":["演示买家","13800001234","文三路 1 号"]', '"mustNotLeak":["文三路"]'))
    validator_fail(ledger, "防呆：合格答案集为空列表 → 必须 FAIL", '"id":"ACT-ORD-09"',
                   lambda l: l.replace('"tool":["queryOrderDetail","queryLogistics"]', '"tool":[]'))
    validator_fail(ledger, "防呆：合格答案集含重复工具 → 必须 FAIL", '"id":"ACT-ORD-11"',
                   lambda l: l.replace('"tool":["queryOrderDetail","queryLogistics"]',
                                       '"tool":["queryOrderDetail","queryOrderDetail"]'))
    validator_fail(ledger, "防呆：合格答案集含非法工具名 → 必须 FAIL", '"id":"ACT-ORD-16"',
                   lambda l: l.replace('"tool":["queryOrderDetail","queryLogistics"]',
                                       '"tool":["queryOrderDetail","queryEverything"]'))

    # ---- 生成物稳定（验收 17） ------------------------------------------------
    root2 = scaffold()
    run(root2, "build_eval_set.py")
    first = sha256(root2 / "eval/tool-cases.jsonl")
    run(root2, "build_eval_set.py")
    second = sha256(root2 / "eval/tool-cases.jsonl")
    ledger.check("生成物稳定：连跑两次 tool-cases.jsonl 字节级相同", first == second, first[:12])
    ledger.check("仓库里那份与重新生成结果一致（没人手改过生成物）", second == sha256(REPO / "eval/tool-cases.jsonl"))

    # ---- 换行符基线（README 的改文档规矩：假 diff 会淹掉证据） ------------------
    drifted = []
    for rel, want in sorted(EOL_BASELINE.items()):
        crlf, lone, _ = eol_signature(REPO / rel)
        if not ((lone == 0 and crlf > 0) if want == "crlf" else (crlf == 0 and lone > 0)):
            drifted.append(f"{rel}={want}实际 crlf={crlf} lf={lone}")
    ledger.check(f"换行符基线：{len(EOL_BASELINE)} 个文件各自守住 CRLF/LF",
                 not drifted, "；".join(drifted)[:110])

    dirty_after = subprocess.run(["git", "-C", str(REPO), "status", "--porcelain"],
                                 capture_output=True, text=True).stdout
    ledger.check("实验没污染工作树（前后 git status 一字不差）", dirty_before == dirty_after)

    print(f"\n合计 {sum(ledger.rows)}/{len(ledger.rows)} 通过")
    print("重算类判据（差异集合、未达线行数、前后两套读数）另跑：")
    print("  python scripts/run_tool_eval.py --rescore <6 份明细> "
          "--rescore-expected-diff ACT-ORD-09,ACT-ORD-11,ACT-ORD-16,ACT-ORD-17")
    return 0 if all(ledger.rows) else 1


if __name__ == "__main__":
    sys.exit(main())
