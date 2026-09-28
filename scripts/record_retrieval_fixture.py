"""round22 票 64 / ADR 0049：录检索融合夹具（append-only 家族）。

用**现成**的 `/ops/retrieval` 探针（`OpsController`）：它一次调用同时返回 `denseTop` /
`lexicalTop` / `fusedTop` —— 输入与输出在同一次召回里录到，所以夹具里的三份序天然可比。

三个前置不成立就拒绝录制（录一份降级的夹具等于给融合门发一张假绿灯）：
  ① 任一路为空或 `degraded` → 拒绝；
  ② `intentFilterRelaxed` 为真 → 拒绝（那说明录的是"兜底重试"那一路，不是首次召回）；
  ③ 同一查询录两遍结果不一致 → 拒绝（融合本该是确定性的）。

用法：
  python scripts/record_retrieval_fixture.py                       # 写到 eval/retrieval-fixture-<今日>.json
  python scripts/record_retrieval_fixture.py --out <路径>
录完会打印夹具的 sha256 —— 那份值要填进 `ci-subset.yml` 的 `RETRIEVAL_FIXTURE_SHA256`（哈希钉）。
"""

import argparse
import datetime as dt
import hashlib
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
APP_YML = REPO / "shoppilot-gateway" / "src" / "main" / "resources" / "application.yml"

# 查询集必须**能造分歧**：原 16 条按文件名前缀比时 dense 与 hybrid 名次全同，
# 拿它做夹具对融合是 no-op。这里挑的是同一主题下多个近重复条款（平台级 + 店铺级 + 相邻主题），
# 两路召回本来就该在这些查询上给出不同序。
QUERIES = [
    ("RET-7DAY", "七天无理由怎么算", "POLICY_RETURN"),
    ("RET-SHIPCOST", "退回去的邮费谁出", "POLICY_RETURN"),
    ("RET-REFUND-TL", "退款到账要几个工作日", "POLICY_RETURN"),
    ("RET-REMOTE", "偏远地区包邮吗", "POLICY_SHIPPING"),
    ("RET-CARRIER", "可以指定发顺丰吗", "POLICY_SHIPPING"),
    ("RET-ADDR", "收货地址填错了还能改吗", "POLICY_SHIPPING"),
    ("RET-FRESH-WINDOW", "生鲜签收后多久之内可以申请理赔", "POLICY_FRESH"),
    ("RET-FRESH-DEAD", "螃蟹到货是死的能赔吗", "POLICY_FRESH"),
    ("RET-PROMO-STACK", "店铺券和满减可以叠加不", "POLICY_PROMO"),
    ("RET-PROMO-CROSS", "跨店满减是怎么凑的", "POLICY_PROMO"),
]

# 四个常数在这里**从生产 application.yml 现读**，不抄一份到夹具里 —— 抄一份就会和 yml 分家，
# 而本票要抓的恰恰是常数漂移。retrieval 块是扁平的键值，逐行取即可。
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
    """与 scripts/provenance.py 同一定义：knowledge/*.md 按文件名排序，逐个拼进摘要。"""
    digest = hashlib.sha256()
    for path in sorted((REPO / "knowledge").glob("*.md")):
        digest.update(path.name.encode("utf-8"))
        digest.update(path.read_bytes())
    return digest.hexdigest()


def _get_json(url, headers):
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=120) as response:
        return json.load(response)


def mock_token(base, tenant, customer):
    body = json.dumps({"tenantId": tenant, "customerId": customer}).encode("utf-8")
    request = urllib.request.Request(base + "/auth/mock-token", data=body,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)["token"]


def probe(base, token, query, limit, ops_token, intent=None):
    params = {"query": query, "limit": str(limit)}
    if intent:
        params["intent"] = intent
    url = base + "/api/v1/support/ops/retrieval?" + urllib.parse.urlencode(params)
    return _get_json(url, {"Authorization": "Bearer " + token, "X-Ops-Token": ops_token})


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8082")
    parser.add_argument("--ops-token", default="dev-ops-token")
    parser.add_argument("--tenant", default="T001")
    parser.add_argument("--customer", default="C001")
    parser.add_argument("--limit", type=int, default=20, help="探针返回的两路条数（≥ fusedTopK 才有意义）")
    parser.add_argument("--out", default="")
    args = parser.parse_args()
    if args.limit < 20:
        print("limit 必须 ≥ 20：两路序太短会让重算出来的融合序与录下来的不可比")
        return 2

    constants = constants_from_yml()
    token = mock_token(args.base, args.tenant, args.customer)
    circuit = _get_json(args.base + "/api/v1/support/ops/circuit",
                        {"Authorization": "Bearer " + token, "X-Ops-Token": args.ops_token})

    cases, epoch = [], None
    for case_id, query, intent in QUERIES:
        # 本机 bge-m3 会被别的项目容器挤掉显存，偶发一次 `degraded`（向量化超时）。
        # 那属于环境抖动：**重试**，但绝不录一份降级的夹具 —— 前置仍然是不成立就拒绝。
        accepted = None
        for attempt in range(1, 4):
            first = probe(args.base, token, query, args.limit, args.ops_token, intent)
            second = probe(args.base, token, query, args.limit, args.ops_token, intent)
            bad = [s for s in (first, second)
                   if s.get("degraded") or not s.get("denseTop") or not s.get("lexicalTop")]
            if bad:
                print(f"  第 {attempt} 次尝试不干净（dense={first.get('denseHits')} "
                      f"lexical={first.get('lexicalHits')} degraded={first.get('degraded')}），重试")
                continue
            if first.get("intentFilterRelaxed") or second.get("intentFilterRelaxed"):
                print(f"  第 {attempt} 次尝试走了意图兜底重试，重试")
                continue
            if (first["denseTop"], first["lexicalTop"], first["fusedTop"]) != \
               (second["denseTop"], second["lexicalTop"], second["fusedTop"]):
                print(f"  第 {attempt} 次尝试两次召回不一致，重试")
                continue
            accepted = first
            break
        if accepted is None:
            print(f"拒绝录制 {case_id}：三次尝试都拿不到一份干净且确定的召回")
            return 1
        epoch = accepted["kbEpoch"]
        cases.append({"id": case_id, "query": query, "intent": intent,
                      "dense": accepted["denseTop"], "lexical": accepted["lexicalTop"],
                      "fused": accepted["fusedTop"]})
        print(f"录 {case_id}: dense={len(accepted['denseTop'])} lexical={len(accepted['lexicalTop'])} "
              f"fused={len(accepted['fusedTop'])}")

    fixture = {
        "recorded_at": dt.datetime.now().isoformat(timespec="seconds"),
        "kb_epoch": epoch,
        "llm_mode": circuit.get("llmMode"),
        "corpus_sha256": corpus_sha256(),
        "record_limit": args.limit,
        "constants": constants,
        "cases": cases,
    }
    out = Path(args.out) if args.out else REPO / "eval" / f"retrieval-fixture-{dt.date.today():%Y%m%d}.json"
    out.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    digest = hashlib.sha256(out.read_bytes()).hexdigest()
    print(f"\n夹具 {out.relative_to(REPO)}（{len(cases)} 条）")
    print(f"constants={constants} kb_epoch={epoch} llm_mode={fixture['llm_mode']}")
    print(f"sha256={digest}")
    print("把上面这行 sha256 填进 .github/workflows/ci-subset.yml 的 RETRIEVAL_FIXTURE_SHA256（哈希钉）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
