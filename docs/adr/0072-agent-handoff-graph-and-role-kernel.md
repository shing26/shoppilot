# Agent Handoff 有向图与角色化执行内核

Date: 2026-10-24　Status: **Accepted**（2026-10-24 所有者确认）
Owner basis: 2026-10-24 所有者确认“覆盖更完整业务链路、让 AI 承担更多类型自主任务”的路径，
并明确第一阶段不是多 Agent 矩阵，而是**角色化执行内核 + 有向 Handoff**。
Context source: 2026-10-24 外部多 Agent 编排建议与本仓现状对齐讨论。
Rollout plan: [ORCHESTRATION_PLAN.md](../ORCHESTRATION_PLAN.md)（H1 session kernel → H2 blocked handoff → H3 tool risk → H4 quality sidecar → H5 optional MCP）。
Builds on: [ADR 0071](0071-round37-llm-wiring-store-persona-and-product-entries.md)（先把真实模型接回主链路；本决策在其防御式运行时上扩角色编排，不推翻 0071 的模型接线与店铺上下文）。

Context:

ShopPilot 已有可验证的防御式运行时：T0/T1/T2 意图门、行业门、工具轮次上限、写动作守卫、
幂等、HITL、降级工单、审计与结果回流。它当前是“单链路客服运行时”。业务广度扩展时，若引入
自由协作的多 Agent，会绕开这些控制门；若引入 LangGraph/MCP 之类的协议先行，又会把治理逻辑
搬到 ShopPilot 运行时之外。

因此需要回答：如何在不推翻现有控制门的前提下扩展业务类型与自主度？

Decision:

**一、第一阶段是“角色化执行内核”，不是自由多 Agent**

- 只引入两个角色：`AFTER_SALES` 与 `PRE_SALES`。
- Router 是确定性纯函数：`POLICY_SHIPPING` / `POLICY_PROMO` -> `PRE_SALES`；
  其余意图（含 `UNKNOWN`）-> `AFTER_SALES`。不新增 LLM 路由调用。
- `UNKNOWN` 不交给售前发挥；继续走既有空召回 / 转人工出口。

**二、Handoff 是有向图，不是 Agent 互聊**

- 第一阶段只允许一条边：`PRE_SALES -> AFTER_SALES`。
- 初始路由深度为 0；最大 Handoff 深度为 **1**，这是和工具轮次上限同一哲学的编排层硬约束。
- 不允许 `AFTER_SALES -> PRE_SALES` 回切。
- 同角色不是 Handoff。
- 被拒原因封闭为 `SAME_ROLE` / `NO_EDGE` / `DEPTH_EXHAUSTED`，避免指标标签基数爆炸。

**三、角色工具面只裁剪“可见候选集”，不是第二套授权**

- `ToolSurfacePolicy` 是唯一裁剪点；`PRE_SALES` 在电商域也拿不到工具。
- 真正的鉴权、幂等、状态校验、HITL、审计仍全部在 `ToolDispatcher`。
- 行业门优先于角色面：非电商域任何角色都不见电商工具。
- `AFTER_SALES` 保持现有售后工具面与 Prompt 不变，保证 180 条 gold 口径零扰动。
- `PRE_SALES` 追加一句边界型提示词段：不新增业务事实，不承诺政策数字。

**四、HandoffContext 是一等契约**

契约包含：会话、from/to、用户原话、归一意图、已确认槽位、已执行/失败工具、pending 状态、
handoff trail、prompt/policy 版本。序列化必须：

- 允许未知字段；
- 不因 Handoff 丢失已确认槽位；
- 进工单 payload 前裁剪长度、不展开工具参数。

**五、路由事实进 trace、指标与工单**

- trace 增加 `route=<role> toolSurface=<surface>`。
- 新增 `shoppilot_agent_role_total{role}`。
- 降级工单 payload 可带 `agentRole` / `toolSurface` / `handoffTrail`；没跑到路由层就不伪造。
- Handoff 被拒的运行时出口留给下一片；本片先把图、契约、判据和观测事实钉住。

**六、Handoff golden set 独立成列**

`eval/handoff-golden.jsonl` 覆盖：售前意图、售后动作、UNKNOWN、行业门、允许边、反向边、
深度超限、同角色。判据由 JVM 重算，不连模型、不连中间件，独立于 180 条 gold，不并分母。

Non-goals（第一阶段明确不做）:

- 不做自由 Agent 协作。
- 不做 LLM Router。
- 不引入 MCP / LangGraph。
- 不做实时质检 Agent。
- 不做 `AFTER_SALES -> PRE_SALES` 回切。
- H5 MCP 适配器**不自动开工**：满足先决条件后，只有量化触发条件出现才开 ADR 评估。
- 不新增业务语料，只重组现有意图面。

Consequences:

- 现有 180 条 gold 的意图、工具与 Prompt 口径不变；只有 `POLICY_SHIPPING` / `POLICY_PROMO`
  两类本就无工具的政策咨询被显式标成 `PRE_SALES`。
- 新增 `AgentRole` / `HandoffGraph` / `HandoffContext` / `ToolSurfacePolicy` / `RouteContext`
  及其测试；本机四模块 JVM verify **653 tests / 107 suites** 全绿，路由事实成为后续扩角色的观测基线。
- 运行时跨会话 Handoff 尚未启用：本片先落“路由 + 工具面 + 契约 + golden set”，避免在
  session 状态、上下文丢失与回切规则未验证前就打开自由迁移。
- **H4 update（2026-10-25）**：只读 quality sidecar 汇总 metrics 与 Redis audit；
  blocked / friction 异常模式配 Prometheus 告警。它不自动改路由，也不在请求路径加模型调用。
  观测口径：handoff 决策计时是显式毫秒桶的 histogram（1/5/10/25/50/100/250ms）；
  tool-success-fallback 在 fallback 出口读既有 trace，不加 per-request 状态；
  slot-ask friction 带 `phase`（same_role/cross_handoff）与 `sameSlot` 标签，
  只有 cross_handoff + sameSlot 记为 `shoppilot_handoff_context_loss_total` 并进入告警。
- **H3 update（2026-10-25）**：`ToolName` 增加静态 `ToolRiskTier`，`ToolDispatcher` 记录
  `shoppilot_tool_risk_attempts_total{tool,risk,hitl,role}`；`ToolSurfacePolicy.decide`
  输出可解释 reason，状态机 trace 与 golden 共用。授权与 HITL 强制点仍在执行路径。
- **H1 update（2026-10-24）**：session 增加 `currentRole` / `handoffTrail`，`HandoffTrigger`
  只把“上一轮售前 + 本轮需要售后工具”判定为同诉求升级；普通换话题是 route reset，不是回切。
  活体验证已出现 `HANDOFF=PRE_SALES->AFTER_SALES depth=0 result=ALLOWED`。
- MCP / LangGraph 不是本决策的一部分。未来若接入，协议层只做连接与发现；
  授权、幂等、审批与审计仍留在 ShopPilot。
- MCP 评估的量化触发（满足先决条件后任意一条才开 ADR，不自动开工）：业务工具数 > 15、
  后端系统数 > 5、新增一个后端要改 > 3 个文件、或出现有证据的跨进程/跨团队工具目录共享需求。
