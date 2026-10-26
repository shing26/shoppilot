# 165 多意图优先级与结构化槽位

**Status:** implemented（2026-10-27）

**Claimed by:** zcode session 2026-10-27（调优阶段 1）

> 依据：[TUNING_PLAN](../TUNING_PLAN.md)、TUNING_BASELINE、票 162/164。

## What to build

1. 明确“改地址”多意图时，动作优先于“现在是什么状态”的订单查询。
2. 模型若误选 queryOrderDetail，dispatch 前改绑 modifyDeliveryAddress。
3. 缺槽位必须走既有 slotAsk 结构化追问，不允许散文追问替代。
4. 只在“地址词 + 改/修改/换”时改绑；“地址是什么”的读问法不改。
5. 地址槽位必须溯源：模型填的收件人/电话/省市区/详址必须在买家说过的话里出现，否则移除并按缺槽追问。

## Blocked by

—

## 范围与验收

- ACT-ORD-13（90001 这单帮我改个地址吧，另外现在是什么状态）：
  intent ACTION_ADDRESS、slotAsked=true、不调用业务系统。
- 模型误选 queryOrderDetail 时被改绑，trace 留 tool-bound。
- 不改 gold / 阈值 / 分母 / 工具授权。
- 180 gold 复跑后 ACTION_ORDER 不低于基线；本机无 key 时登记边界。

## Non-goals

- 不改 ToolSurfacePolicy。
- 不处理退款多意图。
- 不新增槽位体系。

## Verify

- GatewayMainPathJvmTest
- ./mvnw.cmd -B -ntp verify（PATH 需前置 JDK 21）
- 活体：ACT-ORD-13 返回结构化追问

## Handoff notes

**验证读数**

- 定向：T0RuleLayerTest + GatewayMainPathJvmTest，**95 条全绿**。
- 全量：**697 tests / 117 suites**，0 failures / 0 errors。
- 活体（demo 档）：ACT-ORD-13 返回 `intent=ACTION_ADDRESS`、`slotAsked=true`、`toolUsed=false`、`plan=[]`；
  追问为“还需要您补充收件信息：收件人姓名、收件人手机号。”，没有调用业务系统。
- 未重跑 180 gold（本机无有效 key），边界见票 162 / TUNING_BASELINE。

**关键决策**

1. 复用票 164 的 dispatch 前改绑，不新增第二套动作派生。
2. 只认明确的改地址语义（地址词 + 改/修改/换），避免把“地址是什么”改成写动作。
3. 缺槽位仍由既有 ToolDispatcher 判定，状态机只负责选对工具；slotAsk 不重写。
4. 退款多意图不在本票范围，避免把“能退款吗”误改成 applyRefund。
5. 地址槽位必须溯源：模型填的收件人/电话/省市区/详址必须在买家说过的话里出现，否则移除并按缺槽追问；
   否则 demo 模型会把“演示收件人/13800001111/文一西路100号”真的写进订单。
6. 追问文案把 `receiverName/receiverPhone` 映射为中文标签，不向买家暴露字段名。
5. 活体发现 demo 模型会编整套“演示收件信息”；只改选工具不够，必须在 ToolDispatcher 做地址溯源，否则会真的写库。
