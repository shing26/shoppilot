# 153 会话级角色内核：PRE_SALES -> AFTER_SALES 的同诉求升级

**Status:** implemented（2026-10-24）

**Claimed by:** zcode session 2026-10-24（ADR 0072 H1）

> 依据：[ADR 0072](../../../docs/adr/0072-agent-handoff-graph-and-role-kernel.md)、
> [ORCHESTRATION_PLAN](../../../docs/ORCHESTRATION_PLAN.md) H1。

## What to build

把第一阶段“角色化执行内核”从每轮独立路由推进到会话级：

1. `SessionStore.Session` 增加 `currentRole` / `handoffTrail`，旧 JSON 缺字段兼容。
2. 新增 `HandoffTrigger` 纯函数，区分：
   - **同诉求升级**：上一轮 `PRE_SALES`，本轮需要售后工具；
   - **普通换话题**：上一轮售后、本轮售前，或上一轮售前但本轮仍是咨询。
3. 只有同诉求升级才走 `HandoffGraph`，当前深度 < 1 时允许一次 `PRE_SALES -> AFTER_SALES`。
4. 允许 Handoff 时写 trace、递增指标、更新 session role / trail。
5. Handoff 后不重复追问已确认槽位（订单号已在本轮或历史买家轮次中）。

## Blocked by

—

## 范围与验收

- 上一轮问“发什么快递”，下一轮给订单号查物流：trace 出现一次 `ALLOWED` Handoff。
- 同一会话继续问退款：不重复索要订单号。
- 上一轮售后、本轮问“酒店早餐几点”：普通路由到 `PRE_SALES`，无 Handoff、无工单。
- 旧 session JSON 缺新字段时按“无角色 / 空 trail”读取，不迁移旧键。
- 180 条 gold 判据零改动；`eval/handoff-golden.jsonl` 扩展 trigger 判据。
- JVM verify 全绿。

## Non-goals

- 不做 blocked Handoff 落单（H2）。
- 不做 `AFTER_SALES -> PRE_SALES` 回切。
- 不引入 MCP / LangGraph。
- 不新增业务语料。

## Handoff notes

**关键决策**

1. `HandoffTrigger` 把“同诉求升级”收窄为：上一轮 `PRE_SALES`、本轮 `AFTER_SALES`、
   `intent.isAction()` 或 `entityFound=true`。售前政策继续路由到售后分区但不建工具链路。
2. 售后 -> 售前按 route reset 处理，不叫回切、不落工单：这是正常话题切换。
3. Session 增量字段不迁移旧键；旧 JSON 缺 `currentRole` / `handoffTrail` 按未路由 / 空 trail 读取。
4. H1 遇到深度超限时仍按请求角色服务，但不记 Handoff；blocked 落单留给 H2。
5. `runModelPath` 改为接收 effective route，避免 session 升级后再用 requested role 裁工具面。

**验证落点**

- `mvnw verify` → 四模块 **663 tests / 109 suites** 全绿。
- `eval/handoff-golden.jsonl` 17 条由 JVM 重算，覆盖 route / edge / trigger 三类。
- 活体：同一 conversation 内第 1 轮 `route=PRE_SALES`，第 2 轮
  `HANDOFF=PRE_SALES->AFTER_SALES depth=0 result=ALLOWED`。
- 180 条 gold / 判据面零改动。

**三个现场追问**

1. **为什么售后 -> 售前不算 Handoff？** 因为用户换话题是常态；把它建模成回切会把正常咨询变成违规落单。
2. **为什么深度超限不立刻落单？** H1 的目标是证明允许边与上下文保留；blocked 的工单契约、payload
   schema 与 active handoff 判据留给 H2，避免把 topic reset 误伤成工单。
3. **为什么 session 只加字段不迁移？** Redis session 有 TTL；旧键按未路由读取即可，迁移只增加一次性风险。
