# 11 — 业务办理闭环：查订单与查物流

**What to build:** 买家问"10023 发货没"，网关抽取订单号、跨进程查 biz-mock、把结果回填模型组织成人话并经 SSE 推送 `tool_executing` 状态；缺订单号时追问一次。落实 ADR 0008。

**Blocked by:** 07 — 意图三级级联判定补全；10 — 工具契约与跨进程调用边界

**Status:** ready-for-agent

**Verify:** 问"10023 发货没" -> 两轮工具内出答案；故意不给订单号 -> 触发 `slot_ask` 且绝不猜订单号；A 店问 B 店订单 -> 返回未找到且不泄露字段。

- [ ] 工具循环硬上限 2 轮，超限强制 `FALLBACK`；覆盖"先查订单列表再查物流"的真实链式场景
- [ ] 必填槽位缺失走 `SLOT_ASK`，追问一次仍缺则 `ESCALATE`，绝不猜槽位
- [ ] 会话状态外置 Redis：`session:{tenantId}:{conversationId}`，含最近 6 轮、槽位、状态，TTL 30 分钟
- [ ] 订单归属双条件校验：`order.tenantId == session.tenantId && order.customerId == session.customerId`
- [ ] 业务办理请求绝不进缓存（含实体即 dynamic）
- [ ] 用例：A 店买家查 B 店订单返回"未在本店找到该订单，若您在其他店铺购买请联系对应店铺客服"，且不泄露 B 店任何字段

## Handoff notes

（收尾时填写：关键决策 + 你需要能当场回答的三个追问）
