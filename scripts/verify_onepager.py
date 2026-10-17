# -*- coding: utf-8 -*-
"""round32 业务一页纸的防腐化门禁（0 token、纯 stdlib、无网关）。

它守两件事，**都不是语义理解**——别把它当成真人 cold-read 的替身：
  1) **结构**：一页纸仍逐字回答那五个问题（一进 / 一出 / 哪一步转人 / 什么情况办不了 / 现在哪段没通）；
  2) **事实一致性**：一页纸点名的具体事实（酒店是 T004、门面单 91001/91002、六小时窗口、
     `POLICY_HOTEL` 四处同步）在代码与语料里**真的存在**——文档改了代码没改（或反过来）就红。

**边界写清楚**：真人 cold-read 是本轮的主判（业务侧的人只看一页纸 3 分钟、回答五个问题）；
本脚本只防「文档日后腐化」，是辅证，**不互相冒充**：脚本绿不代表有人读懂了，脚本红也不代表
业务侧读不懂——它只代表文档与事实对不上了。

反证（脚本不是摆设）：把一页纸复制成临时文本、删掉五个问题里的任意一个锚点，
`check_doc()` 必须报红；删一个事实锚点同样报红。见 `selfcheck()`。

用法：`python scripts/verify_onepager.py`（退出码 0 = 全过，1 = 有断言没成立）。
"""

import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ONEPAGER = REPO / "docs" / "round32-two-runnable-scenarios.md"

# 五个问题就是业务侧 3 分钟内要复述的五件事（ADR 0066 / round32 spec）。
QUESTIONS = ["一进", "一出", "哪一步转人", "什么情况办不了", "现在哪段没通"]

# 一页纸点名的、必须与代码 / 语料对齐的具体事实。
DOC_FACTS = [
    "鲜果驿站", "云栖酒店", "死蟹", "六小时", "按只折算", "转人工",
    "T002", "T004", "POLICY_HOTEL", "91001", "91002",
]


def check_doc(text):
    """纯函数：文本 → 失败清单。临时副本的变异测试直接喂它。"""
    fails = []
    for question in QUESTIONS:
        if question not in text:
            fails.append(f"一页纸少了五个问题之一：{question!r}")
    for fact in DOC_FACTS:
        if fact not in text:
            fails.append(f"一页纸少了事实锚点：{fact!r}")
    return fails


def _read(rel):
    path = REPO / rel
    return path.read_text(encoding="utf-8") if path.exists() else ""


def check_code():
    """一页纸说的具体事实在代码 / 语料里真的存在吗。"""
    checks = []

    intent_java = _read("shoppilot-tool-api/src/main/java/com/shoppilot/tool/Intent.java")
    checks.append(("意图枚举有 POLICY_HOTEL", "POLICY_HOTEL" in intent_java))

    t0_java = _read("shoppilot-gateway/src/main/java/com/shoppilot/gateway/triage/T0RuleLayer.java")
    checks.append(("T0 规则层有酒店词表并返回 POLICY_HOTEL",
                   "HOTEL_WORDS" in t0_java and "Intent.POLICY_HOTEL" in t0_java))

    chunker = _read("shoppilot-gateway/src/main/java/com/shoppilot/gateway/ingest/MarkdownChunker.java")
    checks.append(("入库映射把 hotel- 前缀映到 POLICY_HOTEL",
                   'startsWith("hotel-")' in chunker and '"POLICY_HOTEL"' in chunker))

    seed = _read("shoppilot-biz-mock/src/main/java/com/shoppilot/bizmock/seed/SeedRunner.java")
    checks.append(("种子有 T004 云栖酒店租户行", '"T004"' in seed and "云栖酒店" in seed))
    checks.append(("种子店铺名与语料一致（鲜果驿站 / 星选生活旗舰店 / 云裳服饰旗舰店）",
                   all(name in seed for name in ("鲜果驿站", "星选生活旗舰店", "云裳服饰旗舰店"))))
    checks.append(("种子有门面演示单 91001 / 91002", '"91001"' in seed and '"91002"' in seed))

    hotel_docs = sorted((REPO / "knowledge").glob("hotel-*.md"))
    hotel_texts = [p.read_text(encoding="utf-8") for p in hotel_docs]
    checks.append((f"酒店语料 {len(hotel_docs)} 份、均挂 tenant_id: T004",
                   len(hotel_docs) >= 1 and all("tenant_id: T004" in t for t in hotel_texts)))
    checks.append(("酒店语料刻意不挂 PLATFORM（否则污染所有租户检索）",
                   all("PLATFORM" not in t for t in hotel_texts)))

    hotel_cases = _read("eval/cases-part9-hotel.jsonl")
    checks.append(("酒店评测单独成列（kind=hotel、tenant=T004）",
                   '"kind": "hotel"' in hotel_cases and '"tenant": "T004"' in hotel_cases))

    return checks


def selfcheck():
    """变异反证：临时副本删一个锚点必须报红；不删必须全绿。返回 (失败清单, 断言数)。"""
    fails = []
    count = 1
    real = ONEPAGER.read_text(encoding="utf-8")
    if check_doc(real):
        fails.append("对照：真一页纸本身没通过 check_doc：" + "; ".join(check_doc(real)[:3]))

    for anchor in ("哪一步转人", "现在哪段没通", "91001"):
        count += 1
        # 替换全部出现：锚点在正文里本就多处出现，只删第一处仍能通过，反证会假绿。
        mutated = real.replace(anchor, "（已删除）")
        if anchor in mutated or not any(anchor in f for f in check_doc(mutated)):
            fails.append(f"反证失败：删掉锚点 {anchor!r} 后 check_doc 没抓到")
    return fails, count


def main() -> int:
    if not ONEPAGER.exists():
        print(f"FAIL  一页纸不存在：{ONEPAGER.name}")
        return 1

    failures = list(check_doc(ONEPAGER.read_text(encoding="utf-8")))
    structural = len(QUESTIONS) + len(DOC_FACTS)

    code_checks = check_code()
    for name, ok in code_checks:
        print(("PASS  " if ok else "FAIL  ") + name)
        if not ok:
            failures.append(name)

    mutation_fails, mutation_count = selfcheck()
    for line in mutation_fails:
        print("FAIL  " + line)
    failures.extend(mutation_fails)
    print(f"  结构断言 {structural}（问题 {len(QUESTIONS)} + 事实 {len(DOC_FACTS)}）；"
          f"代码 / 语料 {len(code_checks)}；变异反证 {mutation_count - len(mutation_fails)}/{mutation_count}")

    for line in dict.fromkeys(failures):
        print("FAIL  " + line)
    print(f"ONEPAGER GATE {'OK' if not failures else 'FAIL'} failures={len(failures)}")
    print("说明：本脚本只做结构与事实一致性；真人 cold-read 是主判，另有其人、不在此脚本。")
    return 1 if failures else 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
