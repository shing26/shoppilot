"""ticket 20 的验收证据跑一遍就够：判据形状、量具变异反证、校验器防呆、EOL。

为什么要有这个脚本：本票改的是"数字怎么量的"，而这类改动最坏的失败不是改错，是**改对了但没人能复核**。
所以每条判据都落成可重跑的断言，并且每条都同时给红绿两侧——恒绿的夹具和恒红的断言一样是摆设。
所有变异与注入都发生在临时目录的副本上，仓库文件只读。

用法: python scripts/verify_eval_judge.py
退出码 0 = 全部通过；1 = 有断言没成立。不发任何 HTTP 请求，不花模型额度。
"""

import csv
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
SCORER = "scripts/run_tool_eval.py"
VALIDATOR = "scripts/build_eval_set.py"

RELAXED = {"ACT-ORD-09", "ACT-ORD-11", "ACT-ORD-16", "ACT-ORD-17"}
STILL_SINGLE = {"ACT-ORD-13", "ACT-ADR-14", "ACT-RFD-14"}

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
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def function_body(text, name):
    """按顶层 def 切出函数体：用来把"判据只有一份"这类断言落在结构上而不是行数上。"""
    matched = re.search(rf"^def {name}\(.*?(?=^def |\Z)", text, re.M | re.S)
    return matched.group(0) if matched else ""


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
    ledger.check("对照：干净副本量具自检绿", code == 0 and fixtures and int(fixtures.group(1)) >= 7,
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

    # ---- 量具变异反证（验收 8、9） -------------------------------------------
    mutation_red(ledger, "变异：参数退回「工具没打对就拿空字典比、判成填错」→ selfcheck 必须红",
                 [('actual_args = link["args"] if args_comparable else {}',
                   'actual_args = link["args"] if link else {}'),
                  ('args_comparable = bool(args_scored and link is not None and obs.get("args_known", True))',
                   'args_comparable = bool(args_scored)')])
    mutation_red(ledger, "变异：expectStatus 退回「期望工具没打就静默给 True」→ selfcheck 必须红",
                 [('if link and link["status"] else ""', 'if link and link["status"] else True')])
    mutation_red(ledger, "变异：串号命中不再算硬失败 → selfcheck 必须红",
                 [("status_ok is not False, leak_ok is not False", "status_ok is not False")])

    # ---- 校验器防呆（验收 18、19） -------------------------------------------
    validator_fail(ledger, "防呆：越权样本删掉 mustNotLeak → 必须 FAIL", '"id":"ACT-ORD-17"',
                   lambda l: l.replace(',"mustNotLeak":["演示买家","13800001234"]', ""))
    validator_fail(ledger, "防呆：标记用买家自己报的订单号 → 必须 FAIL", '"id":"ACT-RFD-17"',
                   lambda l: l.replace('"mustNotLeak":["演示买家","13800001234"]', '"mustNotLeak":["90001"]'))
    validator_fail(ledger, "防呆：标记用品类名（跨租户共享词）→ 必须 FAIL", '"id":"ACT-LOG-18"',
                   lambda l: l.replace('"mustNotLeak":["演示买家","13800001234"]', '"mustNotLeak":["服饰鞋包"]'))
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
