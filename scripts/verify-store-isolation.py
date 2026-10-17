# -*- coding: utf-8 -*-
"""跨店隔离活体门禁（2026-10-17「跨店同问同答」缺陷修复的机器护栏）。

四条断言，全部只看确定性字段（引用出处 / 缓存层 / 降级出口），不看模型文风：

1. 门店细则必须在场：T002（鲜果驿站）问「死蟹怎么赔付」，引用的出处里必须有
   fresh-08-shop-t002-dead-crab。修复前 T001 先问会把"只引平台条款"的答案写进
   PLATFORM:ECOMMERCE 桶，T002 再问同句直接命中，自己的门店细则永远不生效。
2. 顺序效应：先让 T003 问「无理由退货窗口是几天」（平台口径答案），T001 再问同句，
   答案的出处里必须有本店的 return-07-shop-t001-window。
3. 空召回确定性出口：T004（酒店）问电商问题必须转人工（INTENT_UNRESOLVED + 工单号），
   不得再出现零引用的自由发挥答案（修复前实测编出"死蟹全额退款"）。
4. 防修过头：没有门店细则的意图（POLICY_SHIPPING）仍要跨店共享平台桶，
   且命中路径的 citationDetails 要带出处（sourceDocs 与标题一起落库）。

用法: python scripts/verify-store-isolation.py
"""
import hashlib
import json
import sys
import time
import urllib.request
from pathlib import Path

GATEWAY = "http://127.0.0.1:8082"
KNOWLEDGE = Path(__file__).resolve().parents[1] / "knowledge"

results = []
retrieval_ok = True


def http(url, payload=None, method=None, headers=None, timeout=180):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(
        url, data=data, method=method or ("POST" if data else "GET"),
        headers={"Content-Type": "application/json; charset=utf-8", **(headers or {})})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def token(tenant):
    return http(f"{GATEWAY}/auth/mock-token",
                {"tenantId": tenant, "customerId": "C155"}, "POST")["token"]


def ask(tok, query, conversation):
    return http(f"{GATEWAY}/api/v1/support/chat", {"query": query}, "POST",
                {"Authorization": "Bearer " + tok, "X-Conversation-Id": conversation})


def docs_of(resp):
    return [d.get("doc") or "" for d in (resp.get("citationDetails") or [])]


def store_rule_cited(resp, doc_stem):
    """该店铺细则的规则块是否出现在 citations 里。

    证据取 citations（ruleId）而不是 citationDetails.doc：L1 是跨版本存在的存储，
    修复前写入的条目没有 sourceDocs 字段（doc 为空但答案内容正确），
    而 ruleId 从 round33 起就一直随条目落库。ruleId 用与 MarkdownChunker 相同的公式
    现场重算（sha256(sourceDoc + '|' + headingPath)），不依赖任何手工清单。
    """
    md = KNOWLEDGE / (doc_stem + ".md")
    lines = md.read_text(encoding="utf-8").split("\n")
    title = doc_stem
    if lines and lines[0].strip() == "---":
        for line in lines[1:]:
            if line.strip() == "---":
                break
            if line.strip().startswith("title:"):
                title = line.split(":", 1)[1].strip()
    headings = [line.strip()[3:].strip() for line in lines if line.strip().startswith("## ")]
    paths = [title] + [f"{title} > {h}" for h in headings]
    expected = {hashlib.sha256(f"{doc_stem}|{p}".encode("utf-8")).hexdigest() for p in paths}
    return bool(set(resp.get("citations") or []) & expected)


def retrieval_health(resp):
    """"跑完了、0 条"与"检索降级"必须分开（2026-10-17 复测踩过：Ollama embedding 超时让
    稠密腿降级 → 写回被 ADR 0006 拒绝 → 顺序效应假红）。RETRIEVE 步的 trace 里带 degraded= 读数。"""
    for step in resp.get("trace") or []:
        detail = step.get("detail") or ""
        if "retrieve" in (step.get("state") or "").lower() or "fused=" in detail:
            return "degraded=false" in detail
    return True  # 命中路径不跑检索：没有 RETRIEVE 步 = 这问没检索，视为健康


def check(name, ok, detail):
    results.append((name, ok))
    print(f"[{'PASS' if ok else 'FAIL'}] {name} -- {detail}")
    return ok


TS = str(int(time.time()))


def main():
    global retrieval_ok
    t001, t002, t003, t004 = (token(t) for t in ("T001", "T002", "T003", "T004"))

    # 1. 门店细则必须在场
    fresh = ask(t002, "死蟹怎么赔付", "iso-fresh-t002-" + TS)
    retrieval_ok = retrieval_health(fresh)
    check("门店细则在场（T002 死蟹细则必须被引用）",
          store_rule_cited(fresh, "fresh-08-shop-t002-dead-crab"),
          f"cache={fresh.get('cacheLayer')} intent={fresh.get('intent')} degraded={not retrieval_ok} "
          f"docs={docs_of(fresh)}")

    # 2. 顺序效应：平台口径答案先进缓存，本店细则仍必须胜出
    ask(t003, "无理由退货窗口是几天", "iso-window-t003-" + TS)
    window = ask(t001, "无理由退货窗口是几天", "iso-window-t001-" + TS)
    retrieval_ok = retrieval_ok and retrieval_health(window)
    check("顺序效应（先有人喂了平台口径，T001 仍必须引本店 return-07）",
          store_rule_cited(window, "return-07-shop-t001-window"),
          f"cache={window.get('cacheLayer')} intent={window.get('intent')} docs={docs_of(window)}")

    # 3. 空召回确定性出口
    hotel = ask(t004, "死蟹怎么赔付", "iso-fresh-t004-" + TS)
    check("空召回转人工（酒店问电商问题：确定性出口 + 工单，不给自由发挥答案）",
          hotel.get("fallbackReason") == "INTENT_UNRESOLVED" and bool(hotel.get("ticketId")),
          f"intent={hotel.get('intent')} fallback={hotel.get('fallbackReason')} "
          f"ticket={hotel.get('ticketId')} cits={len(hotel.get('citations') or [])}")

    # 4. 防修过头：平台桶共享仍活着，且命中路径引用详情带出处
    shared_query = "发什么快递呢"
    ask(t002, shared_query, "iso-ship-warm-" + TS)
    hit = None
    for attempt in range(4):
        time.sleep(1.0)
        second = ask(t001, shared_query, f"iso-ship-hit-{attempt}-" + TS)
        if second.get("cacheLayer") in ("L1", "L2"):
            hit = second
            break
    check("平台桶仍跨店共享（POLICY_SHIPPING，无门店细则的意图）",
          hit is not None,
          f"cacheLayer={None if hit is None else hit.get('cacheLayer')} ")

    if hit is not None:
        details = hit.get("citationDetails") or []
        docs = [d.get("doc") or "" for d in details]
        check("命中路径 citationDetails 带出处（sourceDocs 随条目落库）",
              bool(details) and all(docs),
              f"details={len(details)} docs={docs}")

    failed = [name for name, ok in results if not ok]
    if failed:
        # 前置不成立与断言失败分开：embedding 不可用时"门店细则不在场"不是缺陷证据，
        # 与 polarity / l2 两步同口径按 exit 3 处理（那一步红不代表防线失效）。
        if not retrieval_ok:
            print("\nENV: 检索降级（dense/lexical 有腿挂了），本步按「前置不成立」处理 —— 请恢复向量服务后重跑")
            return 3
        print(f"\nFAILED: {len(failed)}/{len(results)} -- {failed}")
        return 1
    print(f"\nSTORE ISOLATION OK -- {len(results)}/{len(results)} PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
