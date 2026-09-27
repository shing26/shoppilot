# 62 — 审批闸门活体验收（`verify-refund-approval.ps1` + 矩阵 add-only 加步）

**What to build:** 给退款审批闸门一条活体黑盒证据：申请退款 → 断言 `tool_result.status = PENDING_APPROVAL` → `GET /pending` 有该单 → `POST .../review {APPROVE}` → 断言 `PROCESSING` 且买家读回能答出「已放行」；REJECT 独立一条走回滚 + 买家读回能答出「已驳回」。

**Blocked by:** 59、60、61。

**Status:** implemented（2026-09-28）。**活体实跑：7/7 PASS、exit 0。**

**依据：所有者政策覆盖**（ADR 0046）。活体预算按 Q7 裁定的「全套」，但**没跑成的一律按未达成登记、不许摘红**（round20 家法）。

口径：

- 新增 `scripts/verify-refund-approval.ps1`（形状照 `verify-idempotency.ps1`）：申请退款 → 断言受理态 → 队列有该单 → 放行 → 断言 `PROCESSING` + 买家读回「已放行」；REJECT 独立一条走回滚 + 买家读回「已驳回」。
- `run-acceptance.ps1` 矩阵 **add-only** 加一步（**不改既有 22 步的任何判据**）。
- **风险**：矩阵耗时已从 512 s 涨到 805 s，加一步约 +30-60 s；本机资源三条硬限制（TIME_WAIT / 内存 / 显存）见 `docs/EVIDENCE.md`。

- [x] `scripts/verify-refund-approval.ps1` 新增，形状照 `verify-idempotency.ps1`
- [x] APPROVE 路径：受理态 → 队列 → 放行 → `PROCESSING` + 买家读回「已放行」
- [x] REJECT 路径：驳回 → 回滚 → 买家再申请可成 + 买家读回「已驳回」
- [x] `run-acceptance.ps1` 矩阵 add-only 加一步，既有 22 步判据不动
- [x] `pwsh -NoProfile -File scripts/check-ps-syntax.ps1` 通过
- [x] 活体实跑 **7/7 PASS、exit 0**

**Verify**
```bash
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
pwsh -NoProfile -File scripts/verify-refund-approval.ps1
```

## Handoff notes

**关键决策**

- **读回断言改用同步端点取整段答案。** 第一版用流式 SSE 原文做子串匹配，两条读回断言必红——流式把答案按 token 分帧下发（`data:"支付"` 一帧、`data:"渠道"` 另一帧），「支付渠道」这类连续词在原始 SSE 文本里永远不连续。首轮实跑 5/7 就卡在这里，改用 `/api/v1/support/chat` 取 `.answer` 后 7/7。
- **读回问句按「订单状态」措辞，不按「退款进度」措辞。** 实跑发现：本地 3B 对「我那退款到哪了」把意图判成 `ACTION_REFUND`，而该意图下网关只派生写工具（被 `isWrite` 挡住）、模型也不发 `queryOrderDetail` → 买家拿不到读回。换成「帮我查一下订单 X 现在的状态」判成 `ACTION_ORDER`，派生读路径确定性触发，读回答案带上审核态与到账边界。**这是本票发现的真实缺口，登记在下面「未达成」**，不靠改判据掩盖。
- **队列经网关 ops 代理查**（`/api/v1/support/ops/refunds/pending`），与调试台面板同一条缝；脚本不直连 biz-mock（只有 demo reset 直连，与 `verify-idempotency.ps1` 同例）。

**验证落点（活体实跑）**

```
pwsh -NoProfile -File scripts/verify-refund-approval.ps1   → 7/7 PASS, exit 0
  1 apply refund returns PENDING_APPROVAL
  2 pending queue lists the request (refundId=7)
  3 approve moves refund to PROCESSING
  4 buyer readback says released and names the payout boundary
      「您的订单号90001现在处于退款处理中，退款申请已放行，资金处理中，到账由支付渠道处理。」
  5 second refund accepted for the reject path
  6 reject moves refund to REJECTED
  7 rejected order can be refunded again (rollback)   ← 回滚的活体证据
  6 的读回原文：「…目前状态为已发货（SHIPPED）。退款申请已被驳回，订单已恢复原状态。」
     —— 一句话同时证成「驳回」与「按推导回滚」
```

- 矩阵 add-only 加一步 `refund`（排 `idem` 之后），既有 22 步判据一字未改。

**未达成（按实登记，不摘红）**

- **「退款进度」措辞的读回缺口**：本地 3B 把「退款到哪了」判成 `ACTION_REFUND` 后不调 `queryOrderDetail`，买家读回落空。属**路由/措辞**问题（要动 triage 或 Prompt，碰 180 条 gold 的面），不在本票范围，登记给后续轮次评估；本票的读回证据用的是「订单状态」措辞。
- **gold 180 条活体重跑未做**（需 dev 额度）。票 60 的「gold 不判答案文本」是静态依据，不是实测。

**你需要能当场回答的三个追问**

1. *Q：为什么读回断言不用流式原文？* A：流式按 token 分帧，原始 SSE 文本里连续词被 `data:` 与换行切开，子串匹配结构上不可能命中；同步端点返回拼好的 `.answer`，那才是「买家看到的那句话」。
2. *Q：脚本为什么把「退款到哪了」换成「订单 X 什么状态」？* A：前者在本地 3B 下落 `ACTION_REFUND` 且不调读工具（实测），换措辞是为了让**票 60 的机制**在活体上被真的走到；缺口本身没有掩盖，登记在「未达成」。
3. *Q：回滚是怎么在活体上证成的？* A：两条——驳回那一笔的读回原文出现「订单已恢复原状态」，以及第 7 步对同一订单**再次申请成功**（`PENDING_APPROVAL`）。若回滚没发生，订单仍在 `REFUNDING`，第二次申请会落 `STATE_NOT_ALLOWED`。