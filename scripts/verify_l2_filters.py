# -*- coding: utf-8 -*-
"""L2 语义缓存的四条 must-filter 直证（ticket 09 验收）。

为什么不推进纪元来验"纪元变更后不命中"：/ops/epoch/bump 会连检索过滤器一起改，
推一次等于把政策条款整体摘出检索范围，那不是清缓存的手段。所以这里直接在存储层
验证过滤语义：同一条向量，改任一维度都必须检索不到。等价证明，且不破坏线上状态。

用法: python scripts/verify_l2_filters.py
"""
import json
import sys
import time
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


def counter(name):
    """读网关计数器；没注册（一次都没发生过）时按 0 算。"""
    try:
        body = http(f"{GATEWAY}/actuator/metrics/{name}")
    except Exception:
        return 0.0
    values = [m.get("value") for m in body.get("measurements", []) if m.get("statistic") == "COUNT"]
    return float(values[0]) if values else 0.0


def flush(tok):
    """清空 L1 与 L2。前提阶段每轮重试前都要先清：不清的话第二次提问会命中 L1，
    根本不产生 CACHE_WRITE，重试就只是在测一个没在写回的分支。"""
    request = urllib.request.Request(
        f"{GATEWAY}/api/v1/support/ops/cache/flush", data=b"{}", method="POST",
        headers={"Authorization": "Bearer " + tok, "X-Ops-Token": "dev-ops-token",
                 "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode())


def main() -> int:
    failures = []

    def check(label, ok, detail=""):
        print(f"{'PASS' if ok else 'FAIL'}  {label}{('  ' + detail) if detail else ''}")
        if not ok:
            failures.append(label)

    tok = token("T001", "C001")
    flushed = flush(tok)
    check("缓存已清空（L1 与 L2 一起）", flushed.get("l1KeysDeleted", -1) >= 0, str(flushed))

    query = "生鲜类商品理赔要在多长时间内申请"
    # 会话号每次换新：探针必须独立，不能带着上一次跑剩下的对话历史进来。
    embed_failed = False
    points = []
    write_step = "无 CACHE_WRITE 步骤"
    # 前提（L2 里得真有一条本轮写回的向量）依赖本机 Ollama 可用，而它在这台机器上会抖：
    # 2026-09-10 11:18 那轮就是这么红的——向量化失败计数器 +1，四条 must-filter 一条都没被跑到。
    # 所以这里重试的是**前提**（把条目弄进 L2），不是重试断言；每轮先 flush，否则第二次直接命中 L1。
    for attempt in range(1, 4):
        flush(tok)
        embed_before = counter("shoppilot_cache_embed_unavailable_total")
        miss = ask(tok, query, f"l2-filter-{int(time.time())}-{attempt}")
        if attempt == 1:
            check("首次提问写回了缓存答案", bool(miss.get("answer")) and miss.get("cacheLayer") == "NONE",
                  f"intent={miss.get('intent')}")
        # CACHE_WRITE 那一步的 detail 会直接说明写回为什么没发生：
        # skipped:no-write-target = 拿不到查询向量（Ollama 向量化失败时就是这样，见 README 已知限制），
        # rejected:xxx = 写回资格判定拦下。裸报"0 条"会让人以为是 Qdrant 的问题。
        write_step = next((s.get("detail", "?") for s in (miss.get("trace") or [])
                           if s.get("state") == "CACHE_WRITE"), "无 CACHE_WRITE 步骤")
        # 写回跑在网关的独立线程池上（AgentStateMachine#writeBackExecutor：不让用户等缓存落盘），
        # 首答返回与 Qdrant 可见之间有毫秒级窗口，所以这里等一个有界窗口而不是抓一次就判失败。
        deadline = time.time() + 8
        while time.time() < deadline:
            points = http(f"{QDRANT}/collections/{COLLECTION}/points/scroll",
                          {"limit": 8, "with_vector": True, "with_payload": True})["result"]["points"]
            if points:
                break
            time.sleep(0.2)
        embed_after = counter("shoppilot_cache_embed_unavailable_total")
        embed_failed = embed_after > embed_before
        if points:
            break
        print(f"      第 {attempt} 次没拿到 L2 条目（cache_write={write_step}，"
              f"向量化失败 {embed_before} -> {embed_after}），{'查询向量拿不到，重试' if embed_failed else '等下一轮'}")
    check("L2 向量已落到 Qdrant", len(points) >= 1,
          f"{len(points)} 条，cache_write={write_step}，最后一路向量化失败计数={'有增量' if embed_failed else '无增量'}")
    if not points:
        # 与 verify-polarity 同一口径：前提不成立时既不判红也不判绿，报 exit 3。
        # 向量没落盘只说明这一步没法被检验，不说明 must-filter 失效——把环境抖动报成防线失效，
        # 会让人去查一段没有问题的代码。
        if embed_failed:
            print("\n前置不成立：查询向量化在本机失败（shoppilot_cache_embed_unavailable_total 有增量），"
                  "L2 里没有条目可查，四条 must-filter 无法被检验。")
            print("处置：确认向量服务在跑（默认 :11434）后重跑本脚本；这一步不是防线失效的证据。（exit 3）")
            return 3
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
