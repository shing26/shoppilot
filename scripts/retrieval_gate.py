"""round22 票 64 / ADR 0049：检索融合夹具的**只校验、不复算**那一层。

为什么不在这里复算 RRF：两份实现就是第二份判据，两边迟早分叉。重算在 JVM
（`RetrievalFusionReplayTest`），那里调的是**生产那一份** `rrf()`。本脚本只做它独有的四件事：

  ① 夹具 schema：字段齐、每条 case 的 id/query/dense/lexical/fused 都在且非空；
  ② `fused ⊆ dense ∪ lexical`：融合的候选集就是两路的并集，越界的 id 说明夹具坏了；
  ③ `corpus_sha256` 现算 == 夹具里那个（语料一改即红）；
  ④ 四个常数与生产 `application.yml` 一致（改 yml 即红）。

CI 里还多一道：`RETRIEVAL_FIXTURE_SHA256` 有值时，夹具文件本身的 sha256 必须等于它（哈希钉，
与 `check_coverage.py` 的门槛写法同模式）——防的是"夹具被悄悄换掉而没人发现"。

用法：
  python scripts/retrieval_gate.py                 # 自检 + 校验最新那份夹具
  python scripts/retrieval_gate.py --fixture <路径>
  RETRIEVAL_FIXTURE_SHA256=<sha> python scripts/retrieval_gate.py
"""

import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
KIND_DIR = REPO / "eval"
APP_YML = REPO / "shoppilot-gateway" / "src" / "main" / "resources" / "application.yml"
CONSTANT_KEYS = ("rrfK", "denseTopK", "lexicalTopK", "fusedTopK")


def constants_from_yml(path=APP_YML):
    text = path.read_text(encoding="utf-8")
    block = re.search(r"^  retrieval:\n(.*?)(?=^  \S)", text, re.M | re.S)
    if not block:
        raise SystemExit("application.yml 里找不到 retrieval 块")
    body = block.group(1)

    def value(key):
        found = re.search(rf"^\s+{key}:\s*(\d+)", body, re.M)
        if not found:
            raise SystemExit(f"application.yml 的 retrieval 块里找不到 {key}")
        return int(found.group(1))

    return {"rrfK": value("rrf-k"), "denseTopK": value("dense-top-k"),
            "lexicalTopK": value("lexical-top-k"), "fusedTopK": value("fused-top-k")}


def corpus_sha256():
    digest = hashlib.sha256()
    for path in sorted((REPO / "knowledge").glob("*.md")):
        digest.update(path.name.encode("utf-8"))
        digest.update(path.read_bytes())
    return digest.hexdigest()


def validate(fixture, constants, corpus, kind_dir=KIND_DIR):
    """纯函数：返回问题清单（空 = 过）。夹具自检直接喂合成输入驱动它。"""
    problems = []
    if not isinstance(fixture, dict):
        return ["夹具不是对象"]
    for key in ("corpus_sha256", "constants", "record_limit", "cases"):
        if key not in fixture:
            problems.append(f"缺字段 {key}")
    if problems:
        return problems

    if fixture["corpus_sha256"] != corpus:
        problems.append("corpus_sha256 与当前语料不符：夹具是按当时那份 knowledge/ 录的，语料一改要重录")
    for key in CONSTANT_KEYS:
        pinned = (fixture["constants"] or {}).get(key)
        if pinned != constants.get(key):
            problems.append(f"常数 {key} 与生产 application.yml 不符：夹具 {pinned} vs yml {constants.get(key)}")

    cases = fixture["cases"]
    if not isinstance(cases, list) or not cases:
        return problems + ["cases 为空：一份没有 case 的夹具守不住任何东西"]

    seen = set()
    for index, case in enumerate(cases, start=1):
        cid = case.get("id") if isinstance(case, dict) else None
        if not cid:
            problems.append(f"第 {index} 条 case 缺 id")
            continue
        if cid in seen:
            problems.append(f"{cid}：id 重复")
        seen.add(cid)
        for field in ("query", "dense", "lexical", "fused"):
            value = case.get(field)
            if not value:
                problems.append(f"{cid}：{field} 缺失或为空")
        if problems and problems[-1].startswith(cid):
            continue
        union = set(case["dense"]) | set(case["lexical"])
        outside = [rule_id for rule_id in case["fused"] if rule_id not in union]
        if outside:
            problems.append(f"{cid}：fused 里有 {len(outside)} 个 id 不来自两路并集（例如 {outside[0][:8]}…）")
        if len(case["dense"]) < constants.get("fusedTopK", 0) or len(case["lexical"]) < 1:
            problems.append(f"{cid}：两路序太短，重算出来的融合序与录制值不可比")
    return problems


def newest_fixture():
    fixtures = sorted((p for p in KIND_DIR.glob("retrieval-fixture-*.json")), key=lambda p: p.name)
    if not fixtures:
        raise SystemExit("eval/ 下没有 retrieval-fixture-*.json：先跑 python scripts/record_retrieval_fixture.py")
    return fixtures[-1]


# ---- 自检：每条断言都要有一支**必须能失败**的反证（与 eval_suites 同一条纪律）--------------
def _fx(name, fixture, want_problem_containing=None, **overrides):
    return {"name": name, "fixture": fixture, "want": want_problem_containing, "overrides": overrides}


def _good_case():
    # 两路都要长到 ≥ fusedTopK（这里是 5）：太短的夹具重算出来的融合序与录制值不可比。
    return {"id": "X", "query": "q",
            "dense": ["a", "b", "c", "d", "e", "f"],
            "lexical": ["c", "d", "g", "h", "i"],
            "fused": ["c", "a", "b", "d", "e"]}


def _good_fixture(constants, corpus):
    return {"corpus_sha256": corpus, "record_limit": 20, "constants": dict(constants), "cases": [_good_case()]}


SELFCHECK = [
    _fx("合格夹具：必须零问题", "good"),
    _fx("反证：fused 里出现两路都没有的 id → 必须报错", "good_with_outside"),
    _fx("反证：corpus_sha256 与当前语料不符 → 必须报错", "good_bad_corpus"),
    _fx("反证：常数与 yml 不符 → 必须报错", "good_bad_constant"),
    _fx("反证：cases 为空 → 必须报错", "empty_cases"),
    _fx("反证：某条 case 缺 fused → 必须报错", "missing_fused"),
]


def selfcheck(constants, corpus):
    failures = []
    variants = {
        "good": lambda: _good_fixture(constants, corpus),
        "good_with_outside": lambda: {**_good_fixture(constants, corpus),
                                      "cases": [{**_good_case(), "fused": ["c", "zzz"]}]},
        "good_bad_corpus": lambda: {**_good_fixture(constants, corpus), "corpus_sha256": "0" * 64},
        "good_bad_constant": lambda: {**_good_fixture(constants, corpus),
                                      "constants": {**constants, "rrfK": (constants["rrfK"] or 0) + 1}},
        "empty_cases": lambda: {**_good_fixture(constants, corpus), "cases": []},
        "missing_fused": lambda: {**_good_fixture(constants, corpus),
                                  "cases": [{k: v for k, v in _good_case().items() if k != "fused"}]},
    }
    for case in SELFCHECK:
        problems = validate(variants[case["fixture"]](), constants, corpus)
        should_fail = case["fixture"] != "good"
        if should_fail and not problems:
            failures.append(f"{case['name']}：应当报错，实际零问题")
        if not should_fail and problems:
            failures.append(f"{case['name']}：不该报错，实际 {problems[0]}")
    return failures, len(SELFCHECK)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture", default="")
    args = parser.parse_args()

    constants = constants_from_yml()
    corpus = corpus_sha256()

    failures, count = selfcheck(constants, corpus)
    for line in failures:
        print("GATE SELFCHECK FAILED  " + line)
    if failures:
        print(f"RETRIEVAL GATE SELFCHECK ok=0（{len(failures)} 条红，共 {count} 条）")
        return 1
    print(f"RETRIEVAL GATE SELFCHECK ok={count}")

    fixture_path = Path(args.fixture) if args.fixture else newest_fixture()
    raw = fixture_path.read_bytes()
    problems = validate(json.loads(raw.decode("utf-8")), constants, corpus)
    for problem in problems:
        print("RETRIEVAL GATE FAILED  " + problem)

    pinned = os.environ.get("RETRIEVAL_FIXTURE_SHA256", "").strip()
    digest = hashlib.sha256(raw).hexdigest()
    if pinned:
        if pinned != digest:
            problems.append(f"夹具 sha256 与 CI pin 不符：盘上 {digest[:16]}… vs pin {pinned[:16]}…")
            print("RETRIEVAL GATE FAILED  " + problems[-1])
    else:
        print(f"（未设 RETRIEVAL_FIXTURE_SHA256，跳过哈希钉；本夹具 sha256={digest}）")

    if problems:
        print(f"RETRIEVAL GATE ok=0（{len(problems)} 个问题）")
        return 1
    print(f"RETRIEVAL GATE ok cases={len(json.loads(raw.decode('utf-8'))['cases'])} fixture={fixture_path.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
