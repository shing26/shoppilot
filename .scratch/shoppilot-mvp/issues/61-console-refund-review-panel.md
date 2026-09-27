# 61 — 调试台审核面板（列表 / 放行 / 驳回）

**What to build:** 退款审批闸门必须**端上可演示**。一道只存在于 curl 里的闸门，在演示现场等于不存在 —— 那正是外部审计给另一个项目的判词（「踪影只能在状态机代码里演示」），而它给本项目的正面判词恰是「端上可演示」。本票在调试台加审核队列面板：列表（待审核退款单）+ 放行 / 驳回，风格照现有工单队列。

**Blocked by:** 59（审核端点 `GET /api/refunds/pending`、`POST /api/refunds/{id}/review`）。

**Status:** ready-for-agent

**依据：所有者政策覆盖**（ADR 0046 / 0047 Q4）。

口径：

- **落点**：`shoppilot-gateway/src/main/resources/static/index.html` 加审核队列面板（列表 + 放行 / 驳回），风格照现有工单队列；数据经网关代理到 biz-mock 的审核端点（浏览器不直连 biz-mock）。
- **`scripts/verify-console.mjs`（现 36/36）加断言**：面板出现在 `PENDING_APPROVAL` 之后、放行后队列清空、驳回后订单状态回到可申请态。
- 不新增 SSE 事件、不改既有工单队列行为。

- [ ] 调试台新增审核队列面板（列表 + 放行 / 驳回），风格照现有工单队列
- [ ] 面板数据经网关代理取 `GET /api/refunds/pending`，处置走 `POST /api/refunds/{id}/review`
- [ ] `verify-console.mjs` 加断言：受理后出现在队列 / 放行后清空 / 驳回后订单回可申请态
- [ ] `node --check scripts/verify-console.mjs` 通过
- [ ] 活体 `pwsh -NoProfile -File scripts/verify-console.mjs`（起栈；未跑成按未达成登记）

**Verify**
```bash
node --check scripts/verify-console.mjs
pwsh -NoProfile -File scripts/verify-console.mjs
```