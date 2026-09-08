# 11 — 业务办理闭环：查订单与查物流

**What to build:** 买家问"10023 发货没"，网关抽取订单号、跨进程查 biz-mock、把结果回填模型组织成人话并经 SSE 推送 `tool_executing` 状态；缺订单号时追问一次。落实 ADR 0008。

**Blocked by:** 07 — 意图三级级联判定补全；10 — 工具契约与跨进程调用边界

**Status:** done

**Verify:** 问"10023 发货没" -> 两轮工具内出答案；故意不给订单号 -> 触发 `slot_ask` 且绝不猜订单号；A 店问 B 店订单 -> 返回未找到且不泄露字段。

- [x] 工具循环硬上限 2 轮，超限强制 `FALLBACK`；覆盖"先查订单列表再查物流"的真实链式场景
- [x] 必填槽位缺失走 `SLOT_ASK`，追问一次仍缺则 `ESCALATE`，绝不猜槽位
- [x] 会话状态外置 Redis：`session:{tenantId}:{conversationId}`，含最近 6 轮、槽位、状态，TTL 30 分钟
- [x] 订单归属双条件校验：`order.tenantId == session.tenantId && order.customerId == session.customerId`
- [x] 业务办理请求绝不进缓存（含实体即 dynamic）
- [x] 用例：A 店买家查 B 店订单返回"未在本店找到该订单，若您在其他店铺购买请联系对应店铺客服"，且不泄露 B 店任何字段

## Handoff notes

**关键决策**

1. **工具循环硬上限 2 轮**（`shoppilot.agent.max-tool-rounds: 2`），超限强制 `FALLBACK`。脱离时延预算谈自由 Agent 循环是自杀式设计：每多一轮就多一次模型往返 + 一次跨进程调用，SLO 直接失控。2 轮覆盖"先查订单再查物流"这类真实链式场景。
2. **缺槽位走 `SLOT_ASK`，只追问一次**（`max-slot-asks: 1`），仍缺则 `ESCALATE`。**绝不猜槽位**——猜一个订单号出来，用户会以为系统真的查到了。
3. **会话状态外置 Redis**：`session:{tenantId}:{conversationId}`，含最近 6 轮、待填槽位、当前状态，TTL 30 分钟（`session-ttl: 30m`）。外置是为了追问能跨请求续上，也为了实例重启不丢对话。
4. **订单归属双条件校验**：`order.tenantId == session.tenantId && order.customerId == session.customerId`。只校验租户会漏"同店内两个买家互查"，只校验买家会漏跨店，两个条件都要在。
5. **业务办理请求绝不进缓存**：含实体即 `dynamic`（T0 fail-closed），加上 `Intent.cacheAdmissible()` 只放行 `POLICY_*`，两道闸都关着。
6. **读工具在槽位齐全时由网关直接执行**（`GATEWAY_CONFIDENT_SLOTS`：`queryOrderDetail`/`queryLogistics`/`applyRefund` 的 `orderNo`，改址的 `orderNo + receiverPhone`），trace 里标 `gateway-derived`；**写工具永不自动执行**。这是 ticket 16 评测实测逼出来的改动：本地 3B 模型在改地址这类 5 槽位自由文本上抽取准确率只有 60-66%，让模型逐字抄收件人姓名和详细地址既慢又容易编。
7. **trace 步骤携带 `modelArgs=<json>`** 而不是工具返回值，因为评测要判的是"模型填的参数对不对"，返回值会掩盖参数抽取错误。

**你需要能当场回答的三个追问**

- *Q：网关直接执行工具，那还要 Function Calling 干什么？* A：分工不同。网关只在"槽位来自用户原话且可确定性抽取"时接管读操作；意图判定、参数不全时的追问、写操作前的确认、以及把结构化结果组织成人话仍然归模型。写操作不接管是硬边界——自动改址/退款的错误代价不对称。
- *Q：2 轮上限会不会把复杂问题判死？* A：会，而且是故意的。超限走 `FALLBACK` 落工单，比让用户在屏幕前等第 5 轮更负责。`shoppilot_tool_round_exhausted_total` 计数，真流量里这个数就是"该扩到 3 轮"的证据。
- *Q：A 店买家问 B 店订单，怎么保证不泄露？* A：仓储层按 `(tenantId, orderNo)` 查，查不到就是查不到，返回 `NOT_FOUND`，网关文案固定为"未在本店找到该订单，若您在其他店铺购买请联系对应店铺客服"。响应里没有任何 B 店字段，因为根本没读出来。`TenantIsolationAndIdempotencyTest` 有这条越权用例。

**验证记录**

`scripts/verify-action-loop.ps1`：`tool_executing` 与 `tool_result` 成对出现；不给订单号时触发 `slot_ask` 且不编造订单号；跨租户查询返回 NOT_FOUND 且响应体不含对方字段。`FallbackReasonTest`（带 fake biz-mock 驱动真实状态机）6 项覆盖 TOOL_UNAVAILABLE / SLOT_UNRESOLVED / INTENT_UNRESOLVED 等出口。2 轮上限本身由 `shoppilot_tool_round_exhausted_total` 计数与配置项 `max-tool-rounds: 2` 保证，**没有**针对它的 JVM 内单测——这是缺口，README 已知限制里写明。
