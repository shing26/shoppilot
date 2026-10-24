# 155 Tool Risk Tier：工具爆炸半径显式化

**Status:** implemented（2026-10-25）

**Claimed by:** zcode session 2026-10-25（ADR 0072 H3）

> 依据：[ADR 0072](../../../docs/adr/0072-agent-handoff-graph-and-role-kernel.md)、
> [ORCHESTRATION_PLAN](../../../docs/ORCHESTRATION_PLAN.md) H3。

## What to build

1. tool-api 定义 `ToolRiskTier`：READ_ONLY / LOW_RISK_WRITE / MONEY_WRITE / IRREVERSIBLE_WRITE。
2. `ToolName` 声明静态风险等级：
   - `QUERY_ORDER_DETAIL` / `QUERY_LOGISTICS` -> `READ_ONLY`
   - `MODIFY_DELIVERY_ADDRESS` -> `LOW_RISK_WRITE`
   - `APPLY_REFUND` -> `MONEY_WRITE`
3. `ToolDispatcher` 记录每次工具尝试的风险面：tool / risk / role / requiresApproval。
4. `ToolSurfacePolicy` 输出可解释 decision：允许/拒绝原因、角色、域、意图。
5. 状态机 trace 带 surface reason；handoff golden 增加风险面判据。

## Blocked by

—

## 范围与验收

- `PRE_SALES` 在电商域也拿不到 `MONEY_WRITE`。
- `AFTER_SALES` 的既有工具面不变；退款 HITL 行为不变。
- 每次工具尝试都有 risk 指标；route trace 能解释工具面判定。
- 180 条 gold 零改动；JVM verify 全绿。

## Non-goals

- 不新增工具。
- 不改审批状态机。
- 不把风险判断交给 LLM。
- 不引入 MCP。

## Handoff notes

**关键决策**

1. 风险等级挂在 `ToolName`，与意图 / apiName 同层；它是共享契约元数据，不是网关私有事实。
2. 初始映射：查询 -> `READ_ONLY`，改地址 -> `LOW_RISK_WRITE`，退款 -> `MONEY_WRITE`。
   当前没有 `IRREVERSIBLE_WRITE` 实例，但枚举保留以避免未来用 boolean 硬塞。
3. `requiresApproval` 改为委托 `requiresHitl`，保证 ADR 0047 旧行为与新的风险口径同源。
4. `ToolDispatcher` 每次工具尝试记 `shoppilot_tool_risk_attempts_total{tool,risk,hitl,role}`；
   actor 只进 HITL 日志，不进指标标签。
5. `ToolSurfacePolicy.decide` 输出封闭枚举 reason；状态机 trace 与 handoff golden 共用。

**验证落点**

- `mvnw verify` → 四模块 **674 tests / 114 suites** 全绿。
- `ToolRiskTierTest`：风险分级、HITL 与审批同源、当前无 irreversible 工具。
- `ToolDispatcherRiskMetricTest`：READ_ONLY / MONEY_WRITE 尝试的 metric 标签。
- `HandoffGoldenSetTest` 扩到 23 条，新增 6 条工具面 reason 判据。
- 180 条 gold / 退款 HITL 行为零改动。

**三个现场追问**

1. **为什么不在 `ToolDispatcher` 里维护风险映射？** 工具名与风险是跨服务契约；放 tool-api 才能防止
   四个服务各抄一份。
2. **为什么 role 只进 metric 标签而 actor 不进？** role 是封闭枚举；actor/账号会拖垮 Prometheus 基数。
3. **为什么引入 `IRREVERSIBLE_WRITE` 但当前没有工具？** 它是治理分级里的真实类别；现在省掉它，
   未来不可逆动作就会被误塞进 `MONEY_WRITE`。
