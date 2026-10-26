# 164 动作工具准入与物流意图边界

**Status:** implemented（2026-10-27）

**Claimed by:** zcode session 2026-10-27（调优阶段 1）

> 依据：[TUNING_PLAN](../TUNING_PLAN.md)、[TUNING_BASELINE](../../../docs/TUNING_BASELINE.md)、票 162。

## What to build

1. T0 物流词补“代收”；“发了没”已存在，但需要用例钉住不被“这单”抢走。
2. 第一人称不再是动作证据：只有“给我/帮我”但没有动作、订单或政策语义的闲聊判 UNKNOWN。
3. 保留第一人称订单状态问法（我买的东西现在啥情况了 → ACTION_ORDER）与政策型第一人称（不缓存）。
4. 纯物流问法下，模型若误选 queryOrderDetail，在 dispatch 前确定性改绑 queryLogistics。
5. 不改 gold、阈值、分母、FallbackReason。

## Blocked by

—

## 范围与验收

- 90003 有没有人代收 → ACTION_LOGISTICS。
- 90002 这单发了没 → ACTION_LOGISTICS。
- 给我讲个笑话 / 能不能便宜点卖给我 / 帮我写一首诗 → UNKNOWN，不调动作工具。
- 物流意图 + 模型误选 queryOrderDetail → 改绑 queryLogistics，trace 留痕。
- 180 gold 复跑后 LOGISTICS / UNKNOWN 分区不低于基线；本机无 key 时按票 162 边界登记。

## Non-goals

- 不改 ToolSurfacePolicy 的 UNKNOWN 动态工具面契约。
- 不改 Handoff golden。
- 不做多意图 ACT-ORD-13（下一票）。

## Verify

- T0RuleLayerTest / GatewayMainPathJvmTest
- ./mvnw.cmd -B -ntp verify（PATH 需前置 JDK 21）
- 活体：两句物流 + 三句闲聊，检查意图、工具与答案

## Handoff notes

**验证读数**

- 定向：T0RuleLayerTest + GatewayMainPathJvmTest，**94 条全绿**。
- 全量：**696 tests / 117 suites**，0 failures / 0 errors。
- 活体（demo 档，真实 HTTP）：
  - `90003 有没有人代收` → `ACTION_LOGISTICS`，工具 `queryLogistics`；
  - `90002 这单发了没` → `ACTION_LOGISTICS`，工具 `queryLogistics`；
  - `给我讲个笑话 / 能不能便宜点卖给我 / 帮我写一首诗` → `UNKNOWN`，`toolUsed=false`，`plan=[]`。
- 未重跑 180 gold（本机无有效 key），边界见票 162 / TUNING_BASELINE。

**关键决策**

1. 第一人称只是“可能与我有关”，不是动作证据；实体/动作词/政策形状才是。
2. “啥情况/什么情况/啥状态/什么状态”补进订单词，保住 ACT-ORD-12。
3. 物流工具改绑只在“纯物流问法”发生，带“详情/状态”的多意图不抢。
4. 改绑在 dispatch 前，鉴权/槽位/幂等/行业门全部照过。
