#!/usr/bin/env python3
"""答案级事实判分（round48 票 202 / ADR 0083）——确定性、无模型、无网络。

判据只有两条：

- `required`：每一条事实都必须命中；一条事实可以给多个同义备选（`["次日","第二天"]`），
  命中任一即算命中。
- `forbidden`：任一条命中即失败。

归一化做四件事：NFKC（全角转半角）、大小写折叠、去掉所有空白、去掉常见中英文标点。
不做同义词推断——没列进备选的改写就记 miss，读数只会偏严，不会偏松。

`--selfcheck` 是本判分器的红绿对照：全角数字、空白、标点、同义备选、forbidden 命中、
空答案。门禁每次先跑它，自检不过直接红，防止"判分器写宽了，全部通过"。
"""

from __future__ import annotations

import re
import unicodedata

PUNCTUATION = re.compile(
    r"[\s，。！？、；：\"'“”‘’（）()\[\]【】{}<>《》〈〉,.;:!?`~·…—–\-_/\\|]+")

# forbidden 的否定守卫：正确答案经常**引用**错误说法再否定它（"不是当天到账""不支持用券"）。
# 命中位置前 4 个归一化字符里出现否定词就不算违规。这只放宽 forbidden，required 仍按原样判。
NEGATIONS = ("不", "没", "无", "非", "未", "别", "勿")
NEGATION_WINDOW = 4


def normalize(text: str) -> str:
    folded = unicodedata.normalize("NFKC", text or "").lower()
    return PUNCTUATION.sub("", folded)


def alternatives(fact) -> list[str]:
    raw = [fact] if isinstance(fact, str) else list(fact)
    return [normalize(item) for item in raw if normalize(item)]


def fact_label(fact) -> str:
    return fact if isinstance(fact, str) else " / ".join(fact)


def negated(haystack: str, index: int) -> bool:
    window = haystack[max(0, index - NEGATION_WINDOW):index]
    return any(marker in window for marker in NEGATIONS)


def first_violation(haystack: str, term: str) -> bool:
    start = haystack.find(term)
    while start >= 0:
        if not negated(haystack, start):
            return True
        start = haystack.find(term, start + 1)
    return False


def judge(case: dict, answer: str) -> dict:
    haystack = normalize(answer)
    missing = [fact_label(fact) for fact in case.get("required", [])
               if not any(term in haystack for term in alternatives(fact))]
    hits = []
    for fact in case.get("forbidden", []):
        for term in alternatives(fact):
            if first_violation(haystack, term):
                hits.append(term)
    return {
        "ok": bool(haystack) and not missing and not hits,
        "answer_empty": not haystack,
        "missing": missing,
        "forbidden_hits": hits,
    }


SELFCHECK = [
    ("签收次日起算七天。", {"required": [["签收"], ["次日"]], "forbidden": ["发货日"]}, True),
    ("从签收商品的第二天开始算。", {"required": [["次日", "第二天"]], "forbidden": []}, True),
    ("窗口是７天。", {"required": [["7天"]], "forbidden": []}, True),
    ("七 天 无 理 由。", {"required": [["七天无理由"]], "forbidden": []}, True),
    ("签收，次日。", {"required": [["签收次日"]], "forbidden": []}, True),
    ("SHIPPED", {"required": [["shipped"]], "forbidden": []}, True),
    ("订单已退款成功。", {"required": [["退款"]], "forbidden": ["退款成功"]}, False),
    ("", {"required": [["退款"]], "forbidden": []}, False),
    ("已发货。", {"required": [["已发货"], ["工作日"]], "forbidden": []}, False),
    ("商家承担运费。", {"required": [["商家", "平台"]], "forbidden": ["买家承担", "买家垫付"]}, True),
    ("需要买家垫付。", {"required": [["商家"]], "forbidden": ["买家承担", "买家垫付"]}, False),
    ("还没有签收。", {"required": [["签收"]], "forbidden": ["已签收"]}, True),
    # 否定守卫：引用错误说法再否定它，不算违规；肯定句仍要红。
    ("不是当天到账。", {"required": [["当天"]], "forbidden": ["当天到账"]}, True),
    ("不支持使用优惠券。", {"required": [["不支持"]], "forbidden": ["使用优惠券"]}, True),
    ("承诺当天到账。", {"required": [["当天"]], "forbidden": ["当天到账"]}, False),
]


def selfcheck() -> list[str]:
    failures = []
    for index, (answer, case, want) in enumerate(SELFCHECK, start=1):
        got = judge(case, answer)["ok"]
        if got != want:
            failures.append(f"自检 {index}: want={want} got={got} answer={answer!r}")
    return failures


if __name__ == "__main__":
    problems = selfcheck()
    if problems:
        print("FAIL 答案事实判分自检：")
        for problem in problems:
            print("  - " + problem)
        raise SystemExit(1)
    print(f"PASS 答案事实判分自检：{len(SELFCHECK)} 条红绿对照")
