# 61 — 调试台审核面板（列表 / 放行 / 驳回）

**What to build:** 退款审批闸门必须**端上可演示**。一道只存在于 curl 里的闸门，在演示现场等于不存在 —— 那正是外部审计给另一个项目的判词（「踪影只能在状态机代码里演示」），而它给本项目的正面判词恰是「端上可演示」。本票在调试台加审核队列面板：列表（待审核退款单）+ 放行 / 驳回，风格照现有工单队列。

**Blocked by:** 59（审核端点 `GET /api/refunds/pending`、`POST /api/refunds/{id}/review`）。

**Status:** implemented（2026-09-28）。

**依据：所有者政策覆盖**（ADR 0046 / 0047 Q4）。

口径：

- **落点**：`shoppilot-gateway/src/main/resources/static/index.html` 加审核队列面板（列表 + 放行 / 驳回），风格照现有工单队列；数据经网关代理到 biz-mock 的审核端点（浏览器不直连 biz-mock）。
- **`scripts/verify-console.mjs`（现 36/36）加断言**：面板出现在 `PENDING_APPROVAL` 之后、放行后队列清空、驳回后订单状态回到可申请态。
- 不新增 SSE 事件、不改既有工单队列行为。

- [x] 调试台新增审核队列面板（列表 + 放行 / 驳回），风格照现有工单队列
- [x] 面板数据经网关代理取 `GET /api/refunds/pending`，处置走 `POST /api/refunds/{id}/review`
- [x] `verify-console.mjs` 加断言：受理后出现在队列 / 放行后清空 / 驳回后订单回可申请态
- [x] `node --check scripts/verify-console.mjs` 通过
- [ ] 活体 `pwsh -NoProfile -File scripts/verify-console.mjs`（起栈；未跑成按未达成登记）

**Verify**
```bash
node --check scripts/verify-console.mjs
pwsh -NoProfile -File scripts/verify-console.mjs
```

## Handoff notes

**关键决策**

- **入口放页脚，不放页头。** `aside.drawer` 的 `inset: 49px` 与 header 的 `height: 49px` 是同源几何（走查第 11 项那处修复），往 header 塞按钮要动那处几何；而 `footer` 本来就 `flex-wrap`，加一颗按钮只是多一行，不动任何既有布局。面板本身仍是**独立的第二个抽屉**（`#refundDrawer` / `#refundQueue`），与工单抽屉共用同一层 `#scrim`：`openRefundDrawer()` 先关工单抽屉，遮罩/Esc 两个都关。
- **审核态用独立枚举映射，不把业务内部状态串直接喂模型**（同票 60 的口径）。
- **网关代理三件套**：`GET /api/v1/support/ops/refunds/pending`、`POST /api/v1/support/ops/refunds/{id}/review`，均 `tenantScoped=true` —— 租户/买家身份与 `X-Internal-Token` 由网关在服务端补，浏览器只发 `{decision}`，页面从不接触 biz-mock 与内部凭证（与既有两个代理端点同形）。
- **面板断言走真实对话造单**：受理与否取决于模型肯不肯发 `applyRefund`，与 `verify-idempotency.ps1` 同源（活体依赖模型），没有为面板另造一条更宽松的判据。「驳回后订单回到可申请态」这一半是 biz-mock 语义，由 `RefundReviewTest.rejectingRollsBackTheOrderAndAllowsReapply` 在机器上钉住；面板这一趟只验「驳回后队列里不再有它」。

**验证落点**

- 全量 `.\mvnw.cmd -B -ntp verify` → **`5 + 29 + 290 = 324` 绿**（gateway 289→290）。
- 覆盖率棘轮 exit 0：gateway LINE 59.65%→**59.75%**、biz-mock 79.30%、tool-api 47.95%。
- `RestErrorEnvelopeTest` 新增 1 条：抓 `HttpRequest` 断言代理路径 / method / `X-Internal-Token` / `X-Tenant-Id` / `X-Customer-Id`。
- `node --check scripts/verify-console.mjs` 通过；内联脚本用 `new Function` 过了一遍语法（HTML 仍是**一个** `<script>` 块，`strayScripts === 1` 那条既有断言不受影响）。

**未覆盖（按未达成登记，不摘红）**

- **活体 `verify-console.mjs` 未跑**（需起栈 + Playwright 浏览器）：新增 4 条断言（`PENDING_APPROVAL` 出现在真实 SSE 流、面板经代理列出待审退款、放行清空队列、驳回清空队列）只有语法与静态核对，**没有真跑**。不得声称面板已在浏览器里验过。

**你需要能当场回答的三个追问**

1. *Q：为什么退款审核入口放页脚而工单队列在页头？* A：抽屉的 `inset: 49px` 与页头 49px 高度同源，往页头加按钮要改那处几何（走查第 11 项的修复）；页脚本就 `flex-wrap`，加按钮零布局风险。这是**布局约束**下的取舍，不是设计偏好。
2. *Q：面板断言为什么依赖模型发工具调用？* A：退款受理本来就要经模型（网关不为写动作派生工具调用）。这与 `verify-idempotency.ps1` 是同一条依赖，本票不为面板另造更宽松的判据（那会变成"用更弱的门让它变绿"）。
3. *Q：「驳回后订单回可申请态」在面板上怎么没断？* A：面板读不到订单状态（它列的是退款单）。该语义由 biz-mock 的 `RefundReviewTest` 在机器上钉住（驳回→推导回滚→再申请成功）；面板这趟只验队列清空，两者合起来才是完整判据。