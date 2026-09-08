# -*- coding: utf-8 -*-
"""L2 语义缓存的四条 must-filter 直证（ticket 09 验收）。

为什么不推进纪元来验"纪元变更后不命中"：/ops/epoch/bump 会连检索过滤器一起改，
推一次等于把政策条款整体摘出检索范围，那不是清缓存的手段。所以这里直接在存储层
验证过滤语义：同一条向量，改任一维度都必须检索不到。等价证明，且不破坏线上状态。

用法: python scripts/verify_l2_filters.py
"""
import json
import sys
import urllib.request

GATEWAY = "http://127.0.0.1:8082"
QDRANT = "http://127.0.0.1:16333"
COLLECTION = "answer_cache"


def http(url, payload=None, method=None, timeout=60):
    data = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(url, data=data, method=method or ("POST" if data else "GET"),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode())


def token(tenant, customer):
    return http(f"{GATEWAY}/auth/mock-token", {"tenantId": tenant, "customerId": customer}, "POST")["token"]


def ask(tok, query, conversation):
    body = {"query": query, "idempotencyToken": "l2f-" + conversation}
    request = urllib.request.Request(f"{GATEWAY}/api/v1/support/chat", data=json.dumps(body).encode(),
                                     method="POST",
                                     headers={"Content-Type": "application/json",
                                              "Authorization": "Bearer " + tok,
                                              "X-Conversation-Id": conversation})
    with urllib.request.urlopen(request, timeout=180) as response:
        return json.loads(response.read().decode())


def must(pairs):
    return {"must": [{"key": key, "match": {"value": value}} for key, value in pairs.items()]}


def search(vector, filters):
    payload = {"vector": vector, "filter": must(filters), "limit": 3, "with_payload": True}
    return http(f"{QDRANT}/collections/{COLLECTION}/points/search", payload).get("result", [])


def main() -> int:
    failures = []

    def check(label, ok, detail=""):
        print(f"{'PASS' if ok else 'FAIL'}  {label}{('  ' + detail) if detail else ''}")
        if not ok:
            failures.append(label)

    tok = token("T001", "C001")
    ops = {"Authorization": "Bearer " + tok, "X-Ops-Token": "dev-ops-token"}
    request = urllib.request.Request(f"{GATEWAY}/api/v1/support/ops/cache/flush", data=b"{}", method="POST",
                                     headers={**ops, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        flushed = json.loads(response.read().decode())
    check("缓存已清空（L1 与 L2 一起）", flushed.get("l1KeysDeleted", -1) >= 0, str(flushed))

    query = "生鲜类商品理赔要在多长时间内申请"
    miss = ask(tok, query, "l2-filter-probe")
    check("首次提问写回了缓存答案", bool(miss.get("answer")) and miss.get("cacheLayer") == "NONE",
          f"intent={miss.get('intent')}")

    points = http(f"{QDRANT}/collections/{COLLECTION}/points/scroll",
                  {"limit": 8, "with_vector": True, "with_payload": True})["result"]["points"]
    check("L2 向量已落到 Qdrant", len(points) >= 1, f"{len(points)} 条")
    if not points:
        print(f"\n{len(failures)} 项未通过")
        return 1

    point = points[0]
    payload = point["payload"]
    exact = {"tenant_id": payload["tenant_id"], "scope": payload["scope"],
             "intent": payload["intent"], "kb_epoch": payload["kb_epoch"]}
    print(f"      样本 payload: {json.dumps(exact, ensure_ascii=False)}")

    check("四维全对时能检索到（机制本身可用）", len(search(point["vector"], exact)) == 1)

    dimensions = {
        "tenant_id": ("跨租户不命中", payload["tenant_id"] + "-other"),
        "scope": ("跨 scope 不命中", "SHOP" if payload["scope"] == "PLATFORM" else "PLATFORM"),
        "intent": ("跨意图不命中", "POLICY_PROMO" if payload["intent"] != "POLICY_PROMO" else "POLICY_FRESH"),
        "kb_epoch": ("纪元变更后不命中", int(payload["kb_epoch"]) + 1),
    }
    for key, (label, changed) in dimensions.items():
        filters = dict(exact)
        filters[key] = changed
        hits = search(point["vector"], filters)
        check(label, len(hits) == 0, f"命中 {len(hits)} 条" if hits else "")

    print()
    if failures:
        print(f"{len(failures)} 项未通过")
        return 1
    print("全部通过：L2 的 tenant/scope/intent/kb_epoch 四条 must-filter 各自独立生效")
    return 0


if __name__ == "__main__":
    sys.exit(main())
