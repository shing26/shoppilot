# 154 Blocked Handoff 落单与工单编排 payload

**Status:** implemented（2026-10-25）

**Claimed by:** zcode session 2026-10-25（ADR 0072 H2）

> 依据：[ADR 0072](../../../docs/adr/0072-agent-handoff-graph-and-role-kernel.md)、
> [ORCHESTRATION_PLAN](../../../docs/ORCHESTRATION_PLAN.md) H2。

## What to build

1. 定义 active handoff：只有 `PRE_SALES -> AFTER_SALES` 且本轮有动作证据的升级尝试才是 blocked 候选；
   普通换话题、同角色、首轮路由不是 blocked。
2. blocked Handoff 不继续推理：trace / 指标记录后直接落可查工单。
3. 工单 payload 增加：
   - `handoffTrail`
   - `blockedReason`
   - `confirmedSlots`
   - `normalizedIntent`
   - `promptVersion` / `policyVersion`
4. 落单后 session 角色推进到请求角色，避免同一诉求反复打 blocked 工单；trail 不记录虚假 ALLOWED。

## Blocked by

—

## 范围与验收

- depth 超限的 active handoff：不进模型/工具推理，买家拿到可查工单。
- 工单 payload 含 `blockedReason=DEPTH_EXHAUSTED` 与既有 trail；不展开工具参数。
- 正常“售后 -> 售前”话题切换不落工单。
- golden 增加触发结果与是否落单判据。
- 180 条 gold 判据零改动；JVM verify 全绿。

## Non-goals

- 不新增 FallbackReason 枚举。
- 不做反向 Handoff。
- 不做实时质检。
- 不引入 MCP。

## Handoff notes

**关键决策**

1. blocked 只属于 active handoff：`PRE_SALES -> AFTER_SALES` 且有动作证据。普通换话题在
   `HandoffTrigger` 已是 route reset，不会误落工单。
2. blocked 分支不再进模型/工具推理；本轮直接落 `INTENT_UNRESOLVED` 可查工单。
3. 工单 payload 走字符串契约：编排事实整体序列化到 `payload`，而不是展开成请求顶层对象。
4. 带编排事实的降级单显式发 `source=DEGRADE`；否则工单服务 `createFromReason` 会按旧契约丢 payload。
5. blocked trace / metric 使用升级前角色；session 随后推进到请求角色，避免同一诉求反复打 blocked 单。

**验证落点**

- `mvnw verify` → 四模块 **668 tests / 112 suites** 全绿。
- HTTP contract test 捕获网关请求体，断言 `source=DEGRADE` 与序列化 payload。
- ticket 集成测试断言 `source=DEGRADE` 时 payload 字符串落库并从坐席视图返回。
- 活体四轮序列：允许 Handoff -> 回到售前 -> 再次升级 -> blocked；工单 payload 含
  `blockedReason=DEPTH_EXHAUSTED` / trail / `confirmedSlots.orderNo=90002`。
- golden 增加 `expectedTicket` 判据；180 条 gold 零改动。

**三个现场追问**

1. **为什么普通换话题不落 blocked 单？** topic reset 是 Router 的新决策；只有同诉求升级尝试被图拒绝
   才是失控，需要人工。
2. **为什么 payload 必须序列化成字符串？** 跨进程 `CreateTicketRequest.payload` 的既有契约就是
   JSON 字符串；展开成顶层对象等于改契约，而且老调用路径会忽略。
3. **为什么 blocked 后 session 推进到请求角色？** 工单已经是售后侧的交接证据；否则同一诉求每轮
   都会重复打 blocked 单。
