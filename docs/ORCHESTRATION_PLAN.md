# Orchestration Rollout Plan · ADR 0072

> Status snapshot (2026-10-25): **H1-H4 已完成并冻结；H5 未启动**。H5 只在下方量化触发条件出现时进入 ADR 评估，不因 H4 收口自动开工。
> Latest: `4a40ce1 fix(quality): tighten H4 observability semantics`；CI `40148389371` success；JVM **679 tests / 116 suites**
> Historical baseline: `7f84c03 feat(orchestration): add role kernel and directed handoff contract`；CI `40095780918` success；JVM **653 tests / 107 suites**
> Authority: [ADR 0072](adr/0072-agent-handoff-graph-and-role-kernel.md)
>
> 本计划只编排 ADR 0072 之后的扩展路径。它不是“多 Agent 全量矩阵”计划；每个阶段的目标都是
> 把业务广度叠在 ShopPilot 现有防御式运行时上。

## 1. Goal

把当前“路由纯函数 + 工具面裁剪 + Handoff 契约”推进到**可运行的专家角色协作**：

1. 同一会话内，售前咨询需要办理动作时，能显式迁到售后；
2. Handoff 的上下文、轨迹、拒绝原因可观测、可审计、可落工单；
3. 工具风险等级显式化，角色只能看到低爆炸半径候选；
4. 质检先做旁路观测，不进入请求链路；
5. MCP 只做最后的连接器适配，不成为授权或审计边界。

## 2. Non-goals

在 H1-H4 全部收口前，不做：

- 自由 Agent 协作 / Agent 互聊；
- LLM Router；
- `AFTER_SALES -> PRE_SALES` 回切；
- 实时质检 Agent 干预对话；
- 新增售中、质检、投诉等更多角色；
- MCP / LangGraph 接入；
- 把授权、幂等、审批、审计迁出 `ToolDispatcher`。

## 3. Global constraints

- 180 条 gold 一字不改；所有新判据独立成列。
- `ToolDispatcher` 是唯一副作用网关；角色工具面只裁剪候选集。
- 一次只执行一张票；票收尾必须补 `Status` 与 `## Handoff notes`。
- 每个阶段必须有 JVM 判据；活体验收只在需要跨容器/浏览器时跑。
- 所有新指标使用封闭枚举标签，避免 cardinality 失控。
- 不把“话题切换”当成 Handoff 违规；用户从售后问句切回售前是正常路由，不是回切。

---

## 4. Phase H1 · Session-scoped Role Kernel

**Goal:** 让 `PRE_SALES -> AFTER_SALES` 在同一会话内真实发生一次，并保证上下文不丢。

**Design decision:**

- Session 增加两个可序列化字段：`currentRole`、`handoffTrail`。
- 每轮仍先做确定性 Router；Router 的初始选择不算 Handoff。
- 只有满足同诉求升级条件才调用 `HandoffGraph.next`：
  - 上一轮角色是 `PRE_SALES`；
  - 本轮路由到 `AFTER_SALES`；
  - 本轮存在动作意图或订单号等办理证据；
  - 当前 trail 深度 < 1。
- 用户从售后话题切回售前时，按普通路由重置为 `PRE_SALES`；这**不是** `AFTER_SALES -> PRE_SALES`
  Handoff，不落工单、不计 blocked。
- session JSON 必须兼容旧会话：缺字段按“无角色 / 空 trail”处理，不迁移旧键。

**Tasks:**

- [x] H1-T1 扩展 `SessionStore.Session` 与序列化兼容测试
- [x] H1-T2 定义 `HandoffTrigger`：区分“普通换话题”与“同诉求升级”
- [x] H1-T3 在 `AgentStateMachine` 接入 session role / trail
- [x] H1-T4 允许 Handoff 时写 trace `HANDOFF=...`、递增 `shoppilot_handoff_total`
- [x] H1-T5 Handoff 后不重复追问已确认槽位
- [x] H1-T6 扩展 `eval/handoff-golden.jsonl`，新增跨轮升级 / 换话题不升级判据

**Acceptance:**

- 售前问“发什么快递”，下一轮给订单号查物流：trace 出现一次 `ALLOWED` Handoff。
- 同一会话继续问退款：不再重复索要订单号。
- 售后话题后问“酒店早餐几点”：普通路由到 `PRE_SALES`，无 Handoff、无工单。
- 180 条 gold 通过率不下降；Handoff golden 全过。
- JVM verify 全绿；新增指标可按 `from/to/result` 查询。

**Estimated size:** M。风险主要在 session 兼容与“换话题 vs 升级”的判据，不在模型。

**Execution status (2026-10-24):** H1 已实现。全量 JVM **663 tests / 109 suites** 全绿；
活体验证：第 1 轮 `route=PRE_SALES`，第 2 轮 `HANDOFF=PRE_SALES->AFTER_SALES depth=0 result=ALLOWED`。

---

## 5. Phase H2 · Blocked Handoff 与可查工单

**Goal:** 只对“试图继续迁移但被图拒绝”的情况落工单；不让正常话题切换误伤。

**Design decision:**

- blocked 只发生在**主动 Handoff 请求**上：active handoff、depth 超限、图内不存在边。
- 普通换话题是 Router reset，不产生 blocked。
- `FallbackService` 增加受控编排 payload：
  - `handoffTrail`（最多 5 步）
  - `blockedReason`（封闭枚举）
  - `confirmedSlots`（脱敏后）
  - `route` / `toolSurface`
- 新增 `shoppilot_handoff_total{from,to,result=BLOCKED}`；reason 记入 trace 与工单 payload。
- trace 记录 `HANDOFF_BLOCKED=from->to reason=...`。

**Tasks:**

- [x] H2-T1 定义 active handoff 判据，避免 topic reset 误判
- [x] H2-T2 `HandoffGraph` 拒绝结果接入工单出口
- [x] H2-T3 `FallbackService` 写入 handoff payload
- [x] H2-T4 工单服务侧校验 payload 长度与字段
- [x] H2-T5 golden 增加 depth 超限 / no edge / same role 落单判据

**Acceptance:**

- depth 超限时不再继续推理，买家拿到可查工单。
- 工单 payload 包含 trail、blockedReason、confirmedSlots；不展开工具参数。
- 正常“售后 -> 售前”话题切换不落工单。
- JVM verify 全绿；工单详情不泄露 transcript 之外的新敏感面。

**Estimated size:** M。

**Execution status (2026-10-24/25):** H2 已实现。blocked 只对 active handoff 生效；
工单 payload 以 JSON 字符串落入 `tickets.payload`，活体验证包含 `blockedReason=DEPTH_EXHAUSTED`、
trail、`confirmedSlots.orderNo`。全量 JVM **668 tests / 112 suites** 全绿。

---

## 6. Phase H3 · Tool Risk Tier

**Goal:** 把“爆炸半径”从隐式判断变成 `ToolDispatcher` 的显式元数据与运行时策略。

**Design decision:**

```java
public enum ToolRiskTier {
    READ_ONLY,
    LOW_RISK_WRITE,
    MONEY_WRITE,
    IRREVERSIBLE_WRITE
}
```

- 风险等级是静态元数据 + 运行时策略，不由 LLM 判断。
- 初始映射：
  - `QUERY_ORDER_DETAIL` / `QUERY_LOGISTICS` -> `READ_ONLY`
  - `MODIFY_DELIVERY_ADDRESS` -> `LOW_RISK_WRITE`
  - `APPLY_REFUND` -> `MONEY_WRITE`
- 角色可见性继续由 `ToolSurfacePolicy` 裁剪；执行仍由 `ToolDispatcher` 授权。
- `MONEY_WRITE` 继续走现有 HITL；本阶段不新增审批形态。

**Tasks:**

- [x] H3-T1 在 tool-api 定义 `ToolRiskTier`
- [x] H3-T2 为每个 `ToolName` 声明风险等级
- [x] H3-T3 `ToolDispatcher` 增加风险面日志 / 指标
- [x] H3-T4 角色工具面按风险等级输出可解释原因
- [x] H3-T5 golden 增加风险面判据

**Acceptance:**

- 任何工具调用都能回答：谁、什么角色、什么风险、是否需要 HITL。
- `PRE_SALES` 永远见不到 `MONEY_WRITE`。
- 现有退款 HITL 行为不变。
- JVM verify 全绿。

**Estimated size:** S/M。

**Execution status (2026-10-25):** H3 已实现。`ToolName` 静态声明 `READ_ONLY` / `LOW_RISK_WRITE` / `MONEY_WRITE`；
`ToolDispatcher` 每次尝试记录 `shoppilot_tool_risk_attempts_total{tool,risk,hitl,role}`，
HITL 尝试另写含 actor 的日志。`ToolSurfacePolicy.decide` 输出可解释 reason；
handoff golden 扩到 23 条。全量 JVM **674 tests / 114 suites** 全绿。

---

## 7. Phase H4 · Quality Sidecar

**Goal:** 先做只读质检，不做实时干预。

**Design decision:**

- 质检消费既有 trace / audit / 工单数据，不在请求路径加 LLM。
- 第一批只做三个报表：
  1. Handoff 次数、耗时、失败率、blocked 原因分布；
  2. “工具执行成功但用户仍转人工”的模式；
  3. 同一会话反复 Handoff / 反复追问槽位的模式。
- 输出先是指标与日报，不自动改路由。

**Tasks:**

- [x] H4-T1 定义质检读数口径
- [x] H4-T2 从 Redis audit / metrics 汇总只读报表
- [x] H4-T3 为异常模式设告警阈值
- [x] H4-T4 登记哪些发现可以触发后续 ADR

**Acceptance:**

- 能回答：过去 N 分钟多少次 Handoff、多少 blocked、多少工单带 trail。
- 不给请求增加 LLM 调用。
- 不改请求路径行为。

**Estimated size:** M。

**Execution status (2026-10-25):** H4 已实现。
`GET /api/v1/support/ops/quality/report` 只读汇总 metrics 与 Redis audit 最近 15 分钟动作；
新增 blocked handoff / tool-success-fallback / handoff-context-loss 三条 Prometheus 告警与 promtool 双侧测试。
Sidecar 不改路由、不进请求路径 LLM。全量 JVM **679 tests / 116 suites** 全绿。

---

## 8. Phase H5 · Optional MCP Adapter

**Trigger（先决条件 + 任一量化信号，只触发评估，不自动启动）:**

先决条件：

- H1-H4 收口；
- 授权、幂等、审批、审计已在 ShopPilot 侧有稳定测试。

量化信号（任意一条即开 ADR 评估，不直接开工）：

- 业务工具数 `> 15`；
- 后端系统数 `> 5`；
- 新增一个后端需要改动 `> 3` 个文件才能接入 `ToolDispatcher`；
- 出现跨进程 / 跨团队共享工具目录的实际需求（有产品或工单证据，不拿预期当需求）。

**Design decision:**

- MCP 只做工具发现、连接、版本管理。
- 不把鉴权、幂等、HITL、审计搬到 MCP。
- MCP 工具仍必须经过 `ToolDispatcher` 的 `ToolRiskTier` 策略。
- 先做只读工具试点，再考虑写工具。

**Non-goal:** 不因为 MCP 重构现有业务工具。

---

## 9. Execution order

```
H1 session role kernel
  -> H2 blocked handoff / ticket payload
    -> H3 tool risk tier
      -> H4 quality sidecar
        -> H5 optional MCP adapter
```

每个阶段独立可停。H1 未收口不进 H2；H3 未收口不谈 MCP。

## 10. Risks and mitigations

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 把话题切换误判成 Handoff | 用户被误落工单 | H1 先实现 route reset；blocked 只作用于 active handoff |
| Session 旧数据缺字段 | 旧会话读取失败 | Jackson ignoreUnknown + 缺字段默认空，不迁移旧键 |
| Handoff 后重复追问 | 体验崩坏 | `confirmedSlots` 进契约，golden 专门断言 |
| 角色面变成第二套授权 | 安全边界分裂 | ADR 明确：裁剪可见性，执行授权仍在 ToolDispatcher |
| 指标标签失控 | Prometheus 爆炸 | reason/from/to 只用封闭枚举 |
| 过早 MCP | 治理外移 | H5 只设触发条件，不在 H1-H4 引入 |

## 11. Verification matrix

| 阶段 | 必须 |
| --- | --- |
| H1 | JVM verify 全绿；Handoff golden 全过；180 gold 不下降；指标/trace 断言 |
| H2 | JVM verify 全绿；blocked 落单 golden；工单 payload schema 测试 |
| H3 | JVM verify 全绿；risk-tier golden；退款 HITL 回归 |
| H4 | 报表口径测试；不增加请求路径 LLM 调用 |
| H5 | 只读工具试点；ToolDispatcher 旁路审计为零 |

## 12. Rollback

- H1：session 字段只增不改；关闭 HandoffTrigger 即回到“每轮独立路由”。
- H2：blocked 落单开关关闭后，拒绝仅留 trace / 指标，不落工单。
- H3：risk tier 只做观测；策略开关关闭后恢复现有工具面。
- H4：sidecar 停止不影响请求。
- H5：MCP adapter 下线后，工具目录回到本地声明。

## 13. First PR

建议下一张票只做 H1，PR 控制在：

1. `SessionStore` 角色与 trail 字段；
2. `HandoffTrigger` 纯函数；
3. `AgentStateMachine` 接入；
4. trace / metrics；
5. golden 扩展；
6. 不做 blocked 落单，不引 MCP。
