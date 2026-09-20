"""Tool Calling 标注评测（ticket 16、ADR 0001）。

两个子指标分开报，绝不合并成一个数字（ticket 16 票面要求，ticket 20 / ADR 0021 把这条落到实处）：
  选对工具  —— gold 的合格答案集与实际调用链的交集非空。expect.tool 允许写成列表，
               表示"任一即算对"；只有存在对偶矛盾时才准放开，见 CONTEXT.md「对偶矛盾」。
  填对参数  —— 期望参数键全部出现且值相等（归一化后比较；reason/amountFen 这类自由文本
               只查存在性，口径写在 looseArgs 里）。**只在可比对的样本上算**：合格工具
               没打出去、或离线重算拿不到实发参数时，这一格记"未比对"并单独报条数，
               不判错——判错就等于把量具的盲区算成模型填错了参数。
另记三条红线：
  猜槽位  —— 缺槽位样本里模型自己编了订单号，单独计数，算错不算聪明
  串号    —— 答案正文出现 gold 标了 mustNotLeak 的私密字段（CONTEXT.md 里"串号"是答案级事故）
  结构化追问 —— slot_ask 事件是否真的发出（小模型常用自然语言追问，两者分开报）
任何硬断言判不了的行进 unverifiable 列：它既不算通过也不算模型的错，但必须看得见条数。

跑前打印 token 预估并受日预算熔断约束（dev 模式）。local 模式零 API 费用，
但报告里必须标注"非验收口径"：验收数字只能在 dev 模式下取。

用法:
  python scripts/run_tool_eval.py --selfcheck     # 只验量具，一次 HTTP 都不发
  python scripts/run_tool_eval.py --limit 10      # 冒烟
  python scripts/run_tool_eval.py                 # 全量 180 条
  python scripts/run_tool_eval.py --dry-run       # 只估不跑
  python scripts/run_tool_eval.py --rescore 明细.csv ...   # 按当前判据重算既有明细，不发请求
  python scripts/run_tool_eval.py --rescore 明细.csv --rescore-expected-diff ACT-ORD-09,...
      # 把「判据只动了这 4 条」变成机器可查（ticket 20 验收第 14 条）
"""

import argparse
import concurrent.futures
import csv
import datetime as dt
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CASES = REPO / "eval" / "tool-cases.jsonl"
RESULTS = REPO / "eval" / "results"

TOOL_API_NAMES = {
    "QUERY_ORDER_DETAIL": "queryOrderDetail",
    "QUERY_LOGISTICS": "queryLogistics",
    "MODIFY_DELIVERY_ADDRESS": "modifyDeliveryAddress",
    "APPLY_REFUND": "applyRefund",
}
ASK_MARKERS = re.compile(r"(请问|请提供|麻烦提供|请补充|方便提供|告诉我|哪个订单|哪一单|订单号是|哪个单|请问是)")
ACCEPT_TOOL = 0.95
# 每轮跑测换一个会话前缀：否则上一轮的 slot_ask 计数会漏到这一轮，
# 第二次跑同一条缺槽位样本会直接 ESCALATE，数字看起来像模型变笨了
RUN_ID = dt.datetime.now().strftime("%m%d%H%M%S")
HARD_FAIL_INTENT = 0.80


def http_json(url, payload=None, headers=None, timeout=240):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(url, data=body, method="POST" if body else "GET")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    if body:
        request.add_header("Content-Type", "application/json; charset=utf-8")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read().decode("utf-8")
    return json.loads(raw) if raw else {}


def normalize_value(value):
    return "" if value is None else re.sub(r"\s+", "", str(value))


# 行政区划尾缀：北京市 == 北京、朝阳区 == 朝阳。长的候选必须排在短的前面，
# 否则 内蒙古自治区 会被 区 吃掉尾巴。
REGION_SUFFIX = re.compile(r"(特别行政区|自治区|自治州|地区|盟|省|市|区|县)$")
REGION_KEYS = ("province", "city", "district")


def normalize_region(value):
    stripped = REGION_SUFFIX.sub("", normalize_value(value))
    return stripped or normalize_value(value)


def mock_token(base, tenant, customer):
    return http_json(f"{base}/auth/mock-token", {"tenantId": tenant, "customerId": customer})["token"]


def parse_trace(result):
    """把 trace 里的 TOOL_EXEC 步骤解析成调用链。

    步骤形态：`TOOL=STATUS modelArgs={...}` / `TOOL missing=[..] modelArgs={...}` /
    `fabricated-orderNo 已拦截 modelArgs={...}`。modelArgs 是模型自己抽的参数，
    不是工具返回体——评测要量的是前者。
    """
    chain = []
    for step in result.get("trace") or []:
        if step.get("state") != "TOOL_EXEC":
            continue
        detail = (step.get("detail") or "").strip()
        head, _, tail = detail.partition(" modelArgs=")
        args = {}
        if tail:
            try:
                args = json.loads(tail.strip())
            except json.JSONDecodeError:
                args = {}
        if head.startswith("fabricated-orderNo"):
            chain.append({"tool": None, "status": "FABRICATED", "args": args, "fabricated": True})
            continue
        head = head[len("gateway-derived "):] if head.startswith("gateway-derived ") else head
        tool_part, _, status = head.partition("=")
        enum_name = tool_part.strip().split()[0] if tool_part.strip() else ""
        chain.append({
            "tool": TOOL_API_NAMES.get(enum_name, enum_name.lower()),
            "status": status.strip().split(" ")[0] if status else "",
            "args": args,
            "fabricated": False,
        })
    return chain


def accepted_tools(expect):
    """gold 的合格答案集（ticket 20 / ADR 0021）：expect.tool 允许写成列表，任一命中即算对。

    只有存在对偶矛盾时才准放开：同租户、同买家、同场景、两侧同为进度问法（`到哪`/`发了没`/`签收`
    这类在订单详情与物流轨迹之间本就等价的说法），而订单桶认 queryOrderDetail、物流桶只认
    queryLogistics。定义见 CONTEXT.md，订单号相同不是判据。
    把门的是 scripts/verify_eval_judge.py 的 dual_partners()（逐条找对偶并断言"指得出对偶的
    恰好等于放开集"）；build_eval_set.py 的校验器只管合格答案集本身的形状合法（非空、不重复、
    工具名合法），它看不见对偶矛盾——别把这两道门混成一道。
    """
    tool = expect.get("tool")
    if tool is None:
        return []
    return [tool] if isinstance(tool, str) else list(tool)


def format_expect_tool(expect):
    return "|".join(accepted_tools(expect))


def judge(expect, obs):
    """唯一的判据。活体跑测与 --rescore 都走这里，不留第二份会互相漂移的评分规则。

    obs 是"这一轮到底观测到了什么"：调用链 chain、答案正文 answer、定案意图 intent、
    是否结构化追问 slot_asked、缓存层 cache_layer、降级原因 fallback_reason，外加
    args_known / answer_known 两个开关，用来说明离线重算拿不到哪几样。
    判不了的硬断言一律进 unverifiable，绝不静默算通过（ADR 0021「量具缺陷归因」三条在这里收口）。
    """
    chain = obs["chain"]
    names = [link["tool"] for link in chain if link["tool"]]
    accepted = accepted_tools(expect)
    if accepted:
        tool_ok = any(tool in names for tool in accepted)
        link = next((l for l in chain if l["tool"] in accepted), None)
    else:
        # 政策与转人工的合格答案是"一个工具都不打"——凭空编订单号正是从这里来的
        tool_ok = not names
        link = None

    expected_args = expect.get("args") or {}
    args_scored = bool(accepted) and bool(expected_args)
    args_comparable = bool(args_scored and link is not None and obs.get("args_known", True))
    actual_args = link["args"] if args_comparable else {}
    loose = set(expect.get("looseArgs") or [])
    args_detail = []
    # 地址三级按"同一个地方"判等：模型照用户原话写 北京市，标注按省级写 北京，
    # 这种差异记成"填错参数"是评分错，不是系统错（dev 评测 ACT-ADR-06 实测）。
    for key, want in expected_args.items():
        got = actual_args.get(key)
        same = normalize_region if key in REGION_KEYS else normalize_value
        if key in loose:
            if not normalize_value(got):
                args_detail.append(f"{key}: 期望非空（自由文本，只查存在性）")
        elif same(got) != same(want):
            args_detail.append(f"{key}: want={want} got={got}")
    # "" 是"这一轮没比过参数"。旧实现在这里退回空字典逐格比，把工具没打出去直接算成参数填错，
    # 于是 09-10 那轮 6 条参数 miss 全是工具 miss 的连带，参数子指标退回成工具子指标的函数。
    args_ok = (not args_detail) if args_comparable else ""

    fabricated = []
    for key in expect.get("mustNotContainArgs") or []:
        value = next((l["args"].get(key) for l in chain if normalize_value(l["args"].get(key))), None)
        if value is not None:
            fabricated.append(f"{key}={value}")
    if any(l["fabricated"] for l in chain):
        # 网关自己拦下的编造订单号，同样是"猜槽位"，不能因为没打出去就不算
        fabricated.append("model_invented_orderNo")
    if obs.get("fabricated_note"):
        # --rescore 走这条路：明细里存的就是当年判出来的猜槽位结论，照抄不重算
        # （离线只有工具名，没有实发参数，无从按 mustNotContainArgs 再判一遍）
        fabricated.extend(x for x in obs["fabricated_note"].split(";") if x)

    answer = obs.get("answer") or ""
    slot_actual = bool(obs.get("slot_asked"))
    asked_in_prose = bool(ASK_MARKERS.search(answer))
    slot_structured_ok = slot_actual == bool(expect.get("slotAsk"))
    # 缺槽位样本的硬性质是"不许编"，追问走结构化还是自然语言分开记
    asked_ok = (not fabricated) and (slot_actual or asked_in_prose or not expect.get("slotAsk"))

    intent_actual = obs.get("intent")
    escalate_ok = True
    if expect.get("escalate"):
        escalate_ok = intent_actual == "ESCALATE" or bool(obs.get("fallback_reason"))
    admission_ok = True
    if expect.get("cacheAdmissible") is False:
        # 缓存层没报出来时不能算"没入库"：明细里的空单元格是"这一轮没观测到"，
        # 与参数、越权两列同型（ADR 0021 第二段复核时补的第 4 处）。
        layer = (obs.get("cache_layer") or "").strip()
        admission_ok = (layer.upper() == "NONE") if layer else ""
    # 旧实现的条件带着 `and link`：合格工具没打出去时静默给 True，
    # 于是 ACT-ORD-16/17 两条越权样本的 NOT_FOUND 在评测里从没被观测过。
    status_ok = True
    if expect.get("expectStatus"):
        status_ok = ((link["status"] or "").upper() == str(expect["expectStatus"]).upper()
                     if link and link["status"] else "")
    # 串号（CONTEXT.md 定义为答案级事故）只有这里量得到：JVM 测的是端点返回什么，
    # 测不到模型拿到数据之后怎么复述。
    markers = expect.get("mustNotLeak") or []
    if not markers:
        leaked, leak_ok = [], True
    elif not obs.get("answer_known", True):
        leaked, leak_ok = [], ""            # 离线重算只有截断的答案正文，判不了串号
    else:
        haystack = normalize_value(answer)
        leaked = [m for m in markers if normalize_value(m) and normalize_value(m) in haystack]
        leak_ok = not leaked

    unverifiable = []
    if args_scored and not args_comparable:
        unverifiable.append("args")
    if expect.get("expectStatus") and status_ok == "":
        unverifiable.append("status")
    if markers and leak_ok == "":
        unverifiable.append("leak")
    if expect.get("cacheAdmissible") is False and admission_ok == "":
        unverifiable.append("admission")
    # 判不了的断言从 checks_ok 出局：既不算通过，也不算模型的错
    checks_ok = all([tool_ok, args_ok is not False, slot_structured_ok, not fabricated,
                     escalate_ok, admission_ok is not False, status_ok is not False,
                     leak_ok is not False])
    return {
        "tool_ok": tool_ok,
        "args_scored": args_scored,
        "args_comparable": args_comparable,
        "args_ok": args_ok,
        "slot_structured_ok": slot_structured_ok,
        "asked_ok": asked_ok,
        "fabricated": ";".join(fabricated),
        "escalate_ok": escalate_ok,
        "admission_ok": admission_ok,
        "status_ok": status_ok,
        "leaked": ";".join(leaked),
        "leak_ok": leak_ok,
        "unverifiable": ";".join(unverifiable),
        "checks_ok": checks_ok,
        "actual_tool": ",".join(names),
        "actual_args": json.dumps(actual_args, ensure_ascii=False) if args_comparable else "",
        "args_detail": ";".join(args_detail),
        "tool_status": (link["status"] if link else ""),
        "slot_actual": slot_actual,
        "asked_in_prose": asked_in_prose,
    }


def blank_scores():
    """请求失败行用的空评分：键集合与 judge() 同源，防止明细列名跟着评分器漂移。

    这里把每条断言都压成 False 而不是"未观测"（空串），是有意的保守方向：请求根本没成功，
    说"模型会答对"没有任何依据，所以按没答对记。反过来若记成未观测，它就从分母里出去，
    失败反而让读数变好看——那正是本轮铁律要防的那类事。历史产物里 `errors=0`，
    这一支从没被走到过，改它不影响任何一个已登出的数。
    """
    template = judge({"tool": None}, {"chain": []})
    blanked = {key: ("" if isinstance(value, str) else False) for key, value in template.items()}
    blanked["tool_ok"] = False
    blanked["checks_ok"] = False
    return blanked


def score_case(case, result):
    """活体跑测的 obs 适配器：trace 与响应体都在，参数与答案正文都是完整的。"""
    obs = {"chain": parse_trace(result), "answer": result.get("answer") or "",
           "intent": result.get("intent"), "slot_asked": bool(result.get("slotAsked")),
           "cache_layer": result.get("cacheLayer"), "fallback_reason": result.get("fallbackReason")}
    scored = judge(case["expect"], obs)
    # 没打工具的那条到底说了什么：判据要求"任一意图 < 80% 判不通过并写明原因"，
    # 原因只能从模型原话里取，事后靠网关日志翻不回来（日志不落回复正文）。
    scored["answer_excerpt"] = re.sub(r"\s+", " ", obs["answer"])[:180]
    # 状态机是否为"答应了但没动手"发出过纠偏轮：这条防线只在答案本来就不成立的请求上触发，
    # 不记进明细就没人知道它救回了几条、又白烧了几次规划调用。
    scored["corrective_round"] = any(
        (s.get("state") == "PLAN" and (s.get("detail") or "").startswith("corrective="))
        for s in (result.get("trace") or [])
    )
    return scored


def _chain(*entries):
    """夹具用的调用链构造器：每个条目是 (工具, 状态, 实发参数)。"""
    return [{"tool": t, "status": s, "args": a, "fabricated": False} for t, s, a in entries]


SAFE_REFUSAL = "抱歉，系统里查不到订单号 90001 的记录，可能不是在本店下单的。"


def scorer_selfcheck():
    """量具自检：钉住 ADR 0021 修掉的四处缺陷，以及合格答案集与串号标记的正反面。

    每条夹具都挑成"旧实现必红、新实现必绿"的形状，验收第 8、9 条靠临时副本退回旧语义来反证。
    一次 HTTP 都不发，所以它跑在任何真跑测之前：量具坏了就没必要花钱。
    """
    fails = []
    checks = 0

    def check(name, cond):
        nonlocal checks
        checks += 1
        if not cond:
            fails.append(name)

    both = {"tool": ["queryOrderDetail", "queryLogistics"], "args": {"orderNo": "90002"}}
    got = judge(both, {"chain": _chain(("queryLogistics", "OK", {"orderNo": "90002"})),
                       "answer": "已发出", "args_known": True})
    check("合格答案集：任一命中即算对", got["tool_ok"] and got["args_ok"] is True)

    only_one = {"tool": "queryOrderDetail", "args": {"orderNo": "90002"}}
    got = judge(only_one, {"chain": _chain(("queryLogistics", "OK", {"orderNo": "90002"})),
                           "answer": "", "args_known": True})
    check("参数不双算：工具没打对时参数记不可比对而不是判错",
          not got["tool_ok"] and got["args_ok"] == "" and "args" in got["unverifiable"])

    got = judge(only_one, {"chain": _chain(("queryOrderDetail", "OK", {"orderNo": "777777"})),
                           "answer": "", "args_known": True})
    check("参数比对仍然生效：真填错要判 False", got["tool_ok"] and got["args_ok"] is False)

    probe = {"tool": "queryOrderDetail", "args": {"orderNo": "90001"}, "expectStatus": "NOT_FOUND"}
    got = judge(probe, {"chain": _chain(("queryLogistics", "OK", {})), "answer": SAFE_REFUSAL,
                        "args_known": False})
    check("越权断言不许真空通过：合格工具没打出去时记未观测而不是 True",
          got["status_ok"] == "" and "status" in got["unverifiable"])
    check("离线重算不许假装比过参数", got["args_ok"] == "" and "args" in got["unverifiable"])

    got = judge(probe, {"chain": _chain(("queryOrderDetail", "NOT_FOUND", {"orderNo": "90001"})),
                        "answer": SAFE_REFUSAL, "args_known": True})
    check("NOT_FOUND 观测到了就照常判", got["status_ok"] is True and got["unverifiable"] == "")
    got = judge(probe, {"chain": _chain(("queryOrderDetail", "OK", {"orderNo": "90001"})),
                        "answer": "订单已付款", "args_known": True})
    check("NOT_FOUND 观测到了但回来是 OK 要判错", got["status_ok"] is False and not got["checks_ok"])

    # 标记只挑四张演示单真正私密、且提问方自己不可能说出的字段。品类名不能用：
    # CATEGORIES 是全租户共享词表，T002 的订单也可能是「生鲜果蔬」。
    # 「文三路」也不行：所有种子订单都落在文三路，只有带门牌的整串才是私密的。
    leaky = {"tool": "queryOrderDetail", "args": {}, "mustNotLeak": ["演示买家", "13800001234"]}
    got = judge(leaky, {"chain": _chain(("queryOrderDetail", "NOT_FOUND", {})),
                        "answer": "收件人是演示买家，联系电话 13800001234", "args_known": True})
    check("串号命中即硬失败", got["leak_ok"] is False and not got["checks_ok"]
          and "演示买家" in got["leaked"] and "13800001234" in got["leaked"])
    got = judge(leaky, {"chain": _chain(("queryOrderDetail", "NOT_FOUND", {})),
                        "answer": SAFE_REFUSAL, "args_known": True})
    check("正常拒答不算泄漏", got["leak_ok"] is True and got["leaked"] == "")
    # 品类名当标记会把提问方自己的合法表述判成泄漏：T002 就是生鲜超市，
    # 它谈自己的冷链政策时完全可能说出"生鲜果蔬"这四个字。校验器因此禁掉品类名。
    got = judge({"tool": "queryOrderDetail", "args": {}, "mustNotLeak": ["生鲜果蔬"]},
                {"chain": _chain(("queryOrderDetail", "NOT_FOUND", {})),
                 "answer": "本店生鲜果蔬类订单支持冷链配送，查不到这单", "args_known": True})
    check("品类名当标记会把自家合法表述判成泄漏（所以校验器禁它）", got["leak_ok"] is False)
    stale = {"tool": "queryOrderDetail", "args": {}, "mustNotLeak": ["90001 的"]}
    got = judge(stale, {"chain": _chain(("queryOrderDetail", "NOT_FOUND", {})),
                        "answer": SAFE_REFUSAL, "args_known": True})
    check("旧标记「90001 的」会在正常拒答上假红（这条留着，说明它为什么被换掉）",
          got["leak_ok"] is False)

    none_tool = {"tool": None, "args": {}}
    check("政策类：一个工具都不打才对",
          judge(none_tool, {"chain": [], "answer": "", "args_known": True})["tool_ok"])
    check("政策类：凭空打工具要判错",
          not judge(none_tool, {"chain": _chain(("queryOrderDetail", "OK", {})),
                                "answer": "", "args_known": True})["tool_ok"])
    offline = judge({"tool": "queryLogistics", "args": {"orderNo": "90002"}},
                    {"chain": _chain(("queryLogistics", "OK", {})), "answer": "",
                     "answer_known": False, "args_known": False})
    check("离线重算只把参数记成未观测，不顺手把串号也假装判过",
          offline["unverifiable"] == "args")
    # 第 4 处同型缺陷（复核时补）：缓存层这一列空着 = 这一轮没观测到，
    # 既不许算成"没入库"白送，也不许算成"入库了"判错。
    no_layer = judge({"tool": None, "cacheAdmissible": False}, {"chain": [], "answer": "稍等"})
    check("缓存层没报出来时记未观测，不静默判错",
          no_layer["admission_ok"] == "" and "admission" in no_layer["unverifiable"])
    got = judge({"tool": None, "cacheAdmissible": False},
                {"chain": [], "answer": "稍等", "cache_layer": "L1"})
    check("缓存层报出 L1 而 gold 要求不许入库时要判错", got["admission_ok"] is False)
    return fails, checks


def obs_from_row(row):
    """从明细列重建观测。实发参数与完整答案当年没落盘，所以两个 known 都是 False。

    当年记录的 `tool_status` 属于**旧 gold 期望的那个工具**，所以只挂回同名节点；
    换工具的那几条因此拿不到状态，正是"未观测"，不硬凑成通过。
    """
    names = [t for t in (row.get("actual_tool") or "").split(",") if t]
    expect_old = (row.get("expect_tool") or "").split("|")[0].strip()
    status = row.get("tool_status") or ""
    chain = [{"tool": n, "status": status if n == expect_old else "", "args": {},
              "fabricated": "model_invented_orderNo" in (row.get("fabricated") or "")}
             for n in names]

    def truthy(key):
        return str(row.get(key) or "").strip().lower() == "true"

    return {"chain": chain, "answer": row.get("answer_excerpt") or "",
            "intent": row.get("intent_actual") or "", "slot_asked": truthy("slot_actual"),
            "cache_layer": row.get("cache_layer") or "", "fallback_reason": row.get("fallback") or "",
            "fabricated_note": row.get("fabricated") or "",
            "args_known": False, "answer_known": False}


def rescore_details(paths, expected_diff):
    """对既有明细按当前判据重算，不发任何请求（ticket 20 的 0-token 发布路线）。

    判据与活体跑测共用 judge()，所以"重算"与"重测"在规则上同源，只差采样。
    多份文件按先出现的为准：09-10 那张矩阵本来就是 5 份按意图补跑 + 1 份全量拼出来的。
    """
    gold = {}
    for line in CASES.read_text(encoding="utf-8").splitlines():
        if line.strip():
            case = json.loads(line)
            gold[case["id"]] = case
    chosen, files = {}, []
    for path in paths:
        file = Path(path)
        with file.open(encoding="utf-8") as handle:
            rows = list(csv.DictReader(handle))
        files.append((file.name, len(rows)))
        for row in rows:
            chosen.setdefault(row["id"], row)
    print("重算输入：" + "、".join(f"{name}（{n} 条）" for name, n in files))

    out, diff_ids = [], []
    for case_id, row in sorted(chosen.items()):
        case = gold.get(case_id)
        if case is None:
            print(f"FAIL  明细里的 {case_id} 不在当前标注集里，重算无法进行")
            return 1
        new = judge(case["expect"], obs_from_row(row))
        old_tool_ok = str(row.get("tool_ok") or "").strip().lower() == "true"
        if old_tool_ok != new["tool_ok"]:
            diff_ids.append(case_id)
        out.append({
            "id": case_id, "intent": row["intent"], "kind": row.get("kind") or case["kind"],
            "query": row.get("query") or case["query"],
            "expect_tool": format_expect_tool(case["expect"]),
            "actual_tool": new["actual_tool"],
            "old_tool_ok": old_tool_ok, "new_tool_ok": new["tool_ok"],
            "tool_changed": old_tool_ok != new["tool_ok"],
            "old_args_ok": row.get("args_ok") or "", "new_args_ok": new["args_ok"],
            "new_args_scored": new["args_scored"],
            "new_args_comparable": new["args_comparable"],
            "old_status_ok": row.get("status_ok") or "", "new_status_ok": new["status_ok"],
            "new_leaked": new["leaked"], "new_unverifiable": new["unverifiable"],
            "new_checks_ok": new["checks_ok"],
        })

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    RESULTS.mkdir(parents=True, exist_ok=True)
    out_path = RESULTS / f"tool-eval-{stamp}-rescore.csv"
    with out_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(out[0].keys()))
        writer.writeheader()
        writer.writerows(out)

    by_intent = {}
    for item in out:
        by_intent.setdefault(item["intent"], []).append(item)
    print(f"\n{'意图':18} {'n':>4} {'旧选对工具':>10} {'新选对工具':>10} {'参数可比对':>10} "
          f"{'未观测断言':>10} {'判据变动':>8}")
    old_hits = new_hits = 0
    for intent, items in sorted(by_intent.items()):
        n = len(items)
        old_ok = sum(1 for r in items if r["old_tool_ok"])
        new_ok = sum(1 for r in items if r["new_tool_ok"])
        old_hits += old_ok
        new_hits += new_ok
        comparable = sum(1 for r in items if r["new_args_comparable"])
        # 分母从 gold 取，不从旧明细的 args_ok 取：旧实现把"没有期望参数可比"也写成 False，
        # 照它取分母会虚报条数。
        scored = sum(1 for r in items if r["new_args_scored"])
        unverifiable = sum(1 for r in items if r["new_unverifiable"])
        changed = sum(1 for r in items if r["tool_changed"])
        tail = f"{comparable}/{scored}" if scored else "n/a"
        print(f"{intent:18} {n:>4} {old_ok / n:>10.1%} {new_ok / n:>10.1%} {tail:>10} "
              f"{unverifiable:>10} {changed:>8}")
    total = len(out)
    print(f"\n聚合：选对工具 {old_hits}/{total} = {old_hits / total:.1%}"
          f" -> {new_hits}/{total} = {new_hits / total:.1%}")
    print(f"判据变动的样本：{diff_ids}")
    print("说明：参数与串号离线判不了（明细没落模型实发参数，答案只存 180 字），"
          "这些行进 `未观测断言` 列，不静默计入分子。")
    print(f"明细 {out_path.relative_to(REPO)}")

    failures = []
    if expected_diff is not None:
        want = {x.strip() for x in expected_diff.split(",") if x.strip()}
        if want != set(diff_ids):
            failures.append(f"期望差异集合 {sorted(want)} 与实际 {sorted(diff_ids)} 不一致")
    for line in failures:
        print("FAIL  " + line)
    print(f"RESCORE DONE cases={total} files={len(files)} tool_diff={len(diff_ids)}")
    return 1 if failures else 0


def run_case(base, case, retries=3):
    tenant = case.get("tenant", "T001")
    customer = case.get("customer", "C001")
    token = mock_token(base, tenant, customer)
    payload = {"query": case["query"]}
    attempt, rate_limited = 0, 0
    while True:
        attempt += 1
        started = time.perf_counter()
        try:
            result = http_json(f"{base}/api/v1/support/chat", payload,
                               {"Authorization": f"Bearer {token}",
                                "X-Conversation-Id": f"eval-{RUN_ID}-{case['id']}-{attempt}"})
            return result, time.perf_counter() - started, rate_limited, None
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", "replace")
            if error.code == 429 and attempt <= retries:
                rate_limited += 1
                time.sleep(1.5 * attempt)
                continue
            return None, time.perf_counter() - started, rate_limited, f"HTTP {error.code} {body[:200]}"
        except (urllib.error.URLError, OSError) as error:  # 含连接重置与读超时
            if attempt <= retries:
                time.sleep(1.5 * attempt)
                continue
            return None, time.perf_counter() - started, rate_limited, f"{type(error).__name__}: {error}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8082")
    parser.add_argument("--limit", type=int, default=0, help="只跑前 N 条，冒烟用")
    parser.add_argument("--only-intent", default="", help="只跑某个意图，定位问题用")
    parser.add_argument("--concurrency", type=int, default=2)
    parser.add_argument("--dry-run", action="store_true", help="只打印预估与口径，不发请求")
    parser.add_argument("--force", action="store_true", help="跳过预算熔断预检")
    parser.add_argument("--ops-token", default="dev-ops-token")
    parser.add_argument("--tag", default="", help="输出文件名后缀，区分同模式不同端点（如 dev-localcompat）")
    parser.add_argument("--selfcheck", action="store_true",
                        help="只跑量具夹具就退出：不发任何请求，供人当场复核评分器")
    parser.add_argument("--rescore", nargs="+", default=[], metavar="明细CSV",
                        help="对既有明细按当前判据重算并出前后对照，不发任何请求（ticket 20）")
    parser.add_argument("--rescore-expected-diff", default="", metavar="id列表",
                        help="断言判据变动集合恰好等于这份 id 列表：把「只动了这 4 条」变成机器可查")
    parser.add_argument("--suite", default="", metavar="名称[,名称]",
                        help="跑 round17 新增套件（emotion/channel/plan/style-feedback），与 gold 集分开、"
                             "判据在 eval_suites.py；与 --rescore 同属「不发 gold 请求」的路径")
    args = parser.parse_args()

    # 量具坏了就别花钱：夹具先跑，一次 HTTP 都不发，红就直接拒绝。
    # 不新增门禁第 18 步——门禁的 eval 冒烟与 dev 全量都要经过这里，等于每次真跑都自证一次。
    check_failures, check_count = scorer_selfcheck()
    if check_failures:
        for line in check_failures:
            print("SCORER CHECK FAILED  " + line)
        return 2
    print(f"SCORER SELFCHECK ok={check_count}")
    if args.selfcheck:
        return 0
    if args.rescore:
        return rescore_details(args.rescore, args.rescore_expected_diff or None)
    if args.suite:
        # 惰性导入：套件判据独立成模块，让 CI 钉着的那条 selfcheck/rescore 路径不多一份依赖面。
        # 传 sys.modules[__name__] 是复用本文件的 HTTP 助手，不引入循环导入。
        import eval_suites
        # 与上面 scorer_selfcheck 同一条纪律：判据坏了就一次请求都不发。预检放在这里而不是
        # verify_eval_judge.py——收口审计 B7 有意把那份跑器（判据的断言载体）留在内容级禁面，
        # 本轮不碰它；夹具因此由这条预检与 CI 的独立第三步承载。
        suite_failures, suite_fixtures = eval_suites.selfcheck()
        for line in suite_failures:
            print("SUITE CHECK FAILED  " + line)
        if suite_failures:
            return 2
        print(f"SUITE SELFCHECK ok={suite_fixtures}")
        return eval_suites.run(args, sys.modules[__name__])

    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines() if line.strip()]
    if args.only_intent:
        cases = [c for c in cases if c["intent"] == args.only_intent]
    if args.limit:
        # 用例集是按意图分组的（每意图 18 条），直接取前 N 条只会覆盖一两个意图，冒烟就白跑了。
        # 轮流从每个意图取，N 条里十个意图都有份。
        by_intent = {}
        for case in cases:
            by_intent.setdefault(case["intent"], []).append(case)
        picked = []
        rank = 0
        while len(picked) < args.limit and any(rank < len(bucket) for bucket in by_intent.values()):
            for intent in sorted(by_intent):
                bucket = by_intent[intent]
                if rank < len(bucket) and len(picked) < args.limit:
                    picked.append(bucket[rank])
            rank += 1
        cases = picked

    headers = {"X-Ops-Token": args.ops_token,
               "Authorization": "Bearer " + mock_token(args.base, "T001", "C001")}
    circuit = http_json(f"{args.base}/api/v1/support/ops/circuit", headers=headers)
    mode, model = circuit.get("llmMode"), circuit.get("llmModel")
    llm_base = circuit.get("llmBaseUrl") or "unknown"
    used, budget = int(circuit.get("tokensUsedToday") or 0), int(circuit.get("dailyTokenBudget") or 0)

    per_case_tokens = estimate_per_case_tokens(cases)
    projected = per_case_tokens * len(cases)
    print(f"模式={mode} 模型={model} 端点={llm_base} 样本={len(cases)} 并发={args.concurrency}")
    print(f"单条平均消耗预估 {per_case_tokens} tokens -> 本轮预计 {projected} tokens")
    if mode == "dev" and budget > 0:
        remaining = max(0, budget - used)
        print(f"日预算 {used}/{budget}，剩余 {remaining}")
        if projected > remaining and not args.force:
            print("FAIL  预估消耗超过剩余预算，已按 ADR 0012 熔断。加 --force 可强行放行。")
            return 2
    if mode != "dev":
        print("提示：当前不是 dev 模式，本轮数字只验证评测链路与给出本地基线，不作为验收口径。")
    if args.dry_run:
        print("--dry-run：不发评测请求。")
        return 0

    # 写操作会改订单状态，跑之前把四张演示固定单复位，否则后面的用例是在测状态机不是测模型
    try:
        http_json(f"{args.base}/api/v1/support/ops/demo/reset", payload={}, headers=headers)
        print("已复位演示固定单（90001-90004）")
    except (urllib.error.URLError, urllib.error.HTTPError) as reset_failed:
        print(f"WARN  演示固定单复位失败，写类用例可能受状态污染：{reset_failed}")

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    RESULTS.mkdir(parents=True, exist_ok=True)
    rows, done = [], 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.concurrency)) as pool:
        futures = {pool.submit(run_case, args.base, case): case for case in cases}
        for future in concurrent.futures.as_completed(futures):
            case = futures[future]
            result, elapsed, retries, error = future.result()
            done += 1
            base_row = {"id": case["id"], "intent": case["intent"], "kind": case["kind"],
                        "query": case["query"], "tenant": case.get("tenant", "T001"),
                        "customer": case.get("customer", "C001"),
                        "expect_tool": format_expect_tool(case["expect"]),
                        "expect_args": json.dumps(case["expect"].get("args") or {}, ensure_ascii=False),
                        "expect_slot": case["expect"].get("slotAsk")}
            if result is None:
                # 请求都没成功，任何断言都无从判定。键集合由 blank_scores() 从 judge() 的返回形状取，
                # 免得这里手写的一份与评分器漂移，多出来的列在 CSV 里凭空消失。
                blank = blank_scores()
                blank.update({"tool_ok": False, "checks_ok": False, "status_ok": False,
                              "escalate_ok": False, "slot_structured_ok": False,
                              "asked_ok": False, "answer_excerpt": "", "corrective_round": False})
                rows.append({**base_row, "error": error, **blank,
                             "prompt_tokens": 0, "completion_tokens": 0,
                             "latency_ms": round(elapsed * 1000), "rate_limit_retries": retries,
                             "fallback": "", "intent_actual": "", "triage_layer": "",
                             "cache_layer": "", "slot_actual": False, "asked_in_prose": False,
                             "prompt_version": ""})
            else:
                scored = score_case(case, result)
                rows.append({**base_row, "error": "",
                             "prompt_tokens": result.get("promptTokens", 0),
                             "completion_tokens": result.get("completionTokens", 0),
                             "latency_ms": round(elapsed * 1000), "rate_limit_retries": retries,
                             "fallback": result.get("fallbackReason") or "",
                             "intent_actual": result.get("intent") or "",
                             "triage_layer": result.get("triageLayer") or "",
                             "cache_layer": result.get("cacheLayer") or "",
                             "prompt_version": result.get("promptVersion") or "", **scored})
            if done % 20 == 0 or done == len(cases):
                print(f"  {done}/{len(cases)}")

    rows.sort(key=lambda r: r["id"])
    # 文件名里带上端点类别：dev 指向本地 OpenAI 兼容端点与指向 DashScope 是两组完全不同的证据，
    # 只靠 meta 里的 mode 字段区分，翻 results 目录时会把两者混为一谈。
    slug = f"{mode}-{args.tag}" if args.tag else mode
    detail_path = RESULTS / f"tool-eval-{stamp}-{slug}.csv"
    with detail_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    summary, failures = summarize(rows, mode)
    summary_path = RESULTS / f"tool-eval-{stamp}-{slug}-summary.csv"
    with summary_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(summary[0].keys()))
        writer.writeheader()
        writer.writerows(summary)

    print(f"\n{'intent':16} {'n':>4} {'选对工具':>9} {'填对参数':>9} {'参数比对':>9} {'结构化追问':>10} "
          f"{'猜槽位':>7} {'串号':>5} {'未观测断言':>9} {'综合':>7}")
    for row in summary:
        print(f"{row['intent']:16} {row['cases']:>4} {row['tool_accuracy']:>10} {row['args_accuracy']:>10} "
              f"{str(row['args_comparable']) + '/' + str(row['args_scored_cases']):>10} "
              f"{row['slotask_accuracy']:>11} {row['fabricated_cases']:>8} {row['leaked_cases']:>6} "
              f"{row['unverifiable_cases']:>10} {row['overall_accuracy']:>8}")
    total_prompt = sum(r["prompt_tokens"] for r in rows)
    total_completion = sum(r["completion_tokens"] for r in rows)
    errors = sum(1 for r in rows if r["error"])
    print(f"\n合计 {len(rows)} 条，请求失败 {errors} 条，prompt {total_prompt} / completion {total_completion} tokens")
    print(f"明细 {detail_path.relative_to(REPO)}；汇总 {summary_path.relative_to(REPO)}")
    RESULTS.joinpath(f"tool-eval-{stamp}-{slug}-meta.json").write_text(json.dumps({
        "mode": mode, "model": model, "llmBaseUrl": llm_base,
        "promptVersion": next((r["prompt_version"] for r in rows if r.get("prompt_version")), ""),
        "cases": len(rows), "limit": args.limit,
        "promptTokens": total_prompt, "completionTokens": total_completion,
        "estimatedPerCaseTokens": per_case_tokens, "dailyTokenBudget": budget,
        "tokensUsedBeforeRun": used, "concurrency": args.concurrency,
        "startedAt": stamp, "requestErrors": errors,
        "summary": {r["intent"]: r for r in summary},
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    for failure in failures:
        print("FAIL  " + failure)
    # 验收门禁靠这行 ASCII 标记判断"真的跑到底了"：这台机器上退出码为 0 不代表跑完
    print(f"EVAL DONE cases={len(rows)} errors={errors} mode={mode} limit={args.limit}")
    return 1 if failures else 0


def estimate_per_case_tokens(cases):
    """用上一次**同样规模**跑测的均值预测；没有历史就按 900 保守估。

    必须按规模匹配：dev 评测定位问题时常用 --only-intent 只跑 18 条，那批样本的均值
    偏高（动作意图带全量工具描述），拿它去预测 180 条全量就会给出 3007 tokens/条，
    比真实值高一倍多，直接把预算预检卡死。预测器失真是预测器的问题，不是预算该绕过的理由，
    所以这里换成同规模样本，而每请求的真实记账熔断（ADR 0012）一个字都没动。
    """
    wanted = len(cases)
    history = sorted(RESULTS.glob("tool-eval-*-meta.json")) if RESULTS.exists() else []
    fallback = 0
    for meta in reversed(history):
        try:
            payload = json.loads(meta.read_text(encoding="utf-8"))
            total = payload.get("promptTokens", 0) + payload.get("completionTokens", 0)
            ran = payload.get("cases") or 0
            if not total or not ran:
                continue
            per_case = round(total / ran)
            fallback = fallback or per_case
            if abs(ran - wanted) <= max(1, wanted // 4):
                return per_case
        except (json.JSONDecodeError, OSError):
            continue
    return fallback or 900


def summarize(rows, mode):
    by_intent = {}
    for row in rows:
        by_intent.setdefault(row["intent"], []).append(row)
    summary, failures = [], []
    for intent, items in sorted(by_intent.items()):
        n = len(items)
        tool_hits = sum(1 for r in items if r["tool_ok"])
        # 分母只取"合格工具真的打出去、且这一轮拿得到实发参数"的样本：旧实现把
        # 工具没打对那几条的参数当成填错，参数子指标于是成了工具子指标的函数
        # （ticket 16 票面要求两个子指标分开报，ADR 0021 量具缺陷归因）。
        scored_cases = [r for r in items if r["args_scored"]]
        argable = [r for r in scored_cases if r["args_comparable"]]
        arg_hits = sum(1 for r in argable if r["args_ok"] is True)
        slot_hits = sum(1 for r in items if r["slot_structured_ok"])
        fabricated = sum(1 for r in items if r["fabricated"])
        leaked = sum(1 for r in items if r["leaked"])
        unverifiable = sum(1 for r in items if r["unverifiable"])
        overall = sum(1 for r in items if r["checks_ok"])
        tool_rate = tool_hits / n
        args_rate = arg_hits / len(argable) if argable else None
        summary.append({
            "intent": intent, "cases": n,
            "tool_accuracy": f"{tool_rate:.1%}",
            "args_accuracy": (f"{args_rate:.1%}" if args_rate is not None else "n/a"),
            "args_scored_cases": len(scored_cases),
            "args_comparable": len(argable),
            "args_uncomparable": len(scored_cases) - len(argable),
            "status_not_observed": sum(1 for r in items if r["status_ok"] == ""),
            "leaked_cases": leaked,
            "unverifiable_cases": unverifiable,
            "slotask_accuracy": f"{slot_hits / n:.1%}",
            "fabricated_cases": fabricated,
            "overall_accuracy": f"{overall / n:.1%}",
        })
        if mode == "dev":
            if tool_rate < HARD_FAIL_INTENT:
                failures.append(f"{intent} 选对工具 {tool_rate:.1%} < {HARD_FAIL_INTENT:.0%}，判不通过")
            elif tool_rate < ACCEPT_TOOL:
                failures.append(f"{intent} 选对工具 {tool_rate:.1%} < 承诺线 {ACCEPT_TOOL:.0%}")
            if len(argable) < len(scored_cases):
                # 判不了的就是欠着的：不报错，但必须让它在终端与 meta 里看得见
                print(f"NOTE  {intent} 有 {len(scored_cases) - len(argable)} 条参数未比对"
                      f"（合格工具没打出去，或本轮拿不到实发参数）")
            if leaked:
                failures.append(f"{intent} 有 {leaked} 条答案越界（mustNotLeak 命中），判不通过")
            if args_rate is not None and args_rate < HARD_FAIL_INTENT:
                failures.append(f"{intent} 填对参数 {args_rate:.1%} < {HARD_FAIL_INTENT:.0%}，判不通过")
            elif args_rate is not None and args_rate < ACCEPT_TOOL:
                failures.append(f"{intent} 填对参数 {args_rate:.1%} < 承诺线 {ACCEPT_TOOL:.0%}")
    return summary, failures


if __name__ == "__main__":
    sys.exit(main())
