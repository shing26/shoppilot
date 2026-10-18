# 135 Wayfinder map：agent 自主性升级（动态规划 + 反思重试）

**Label:** wayfinder:map　**Status:** active（map 本身不领活，见 Notes 的认领约定）

> 本文件是 **canonical artifact**：低分辨率全局视图，每个 session 加载一次。
> 只给 gist 与链接；一个 decision 只存在一个地方——它的 ticket。

## Destination

**agent-autonomy-spec v2**：把六决策按当前结构重锚（四服务 / 身份域 / `Domain` / 审批闸 / 空召回确定性出口 /
评测体系现状），并把**开轮依据 ADR（0069）草稿**写足（覆盖 ADR 0008/0036 的数字、沿用 0043 的换代指针格式）——
到「可以直接走 `/to-tickets` 拆票」为止。**map 只产出 decisions，不产出实现代码**；开轮本身仍由所有者政策覆盖决定。

## Notes

- **域**：agent 编排层（`AgentStateMachine` / `PlanExpression` / `ToolDispatcher` / 提示词 / 评测）。
- **每个 session 先读**：`AGENTS.md` 的必读顺序；本 map；被 zoom 的 ticket 全文；`agent-autonomy-spec.md`（v1，含六决策原文）。
- **常设约束（不单开票）**：
  - 执行策略：本机资源分档（JVM 桩 0 token 先行；dev 活体按额度与显存分档；`plansteps` 在 local 3B 上更红照登，判据不动）。
  - **gold 零触碰**：180 条 gold / 阈值 / 分母 / 既有验收判据一个字不改；新判据只加在 JVM 与既有 0 token 门禁内。
  - 本仓纪律照旧：一次一票、Handoff notes 三问、既有 ADR 原文不动（覆盖写新 ADR + 换代指针）。
- **认领约定（本仓为 local-markdown tracker，无 assignee 字段）**：开工前在 ticket 里加一行
  `**Claimed by:** <session/人> <日期>`——有这行即已认领；`ready-for-agent` 且无该行 = 可领。
- **blocking**：用本仓既有 `## Blocked by` 约定（无 native links）；所有 blocker 关闭后即 unblocked。
- **frontier**：open + unblocked + 未认领的子票（见下方「Not yet specified」之上的票面清单）。

## Decisions so far

<!-- the index — one line per closed ticket: enough to judge relevance, then zoom the link for the detail the ticket holds -->

- [136 自主度数字重锚：4 轮 / 4 步是否维持](136-autonomy-budget-and-steps.md) — **维持 4/4，只动一个数**：`max-tool-rounds` 2→4（计划步数与工具轮数同源，自动 ≤4，不设第二个校验面）；`${SHOPPILOT_AGENT_MAX_TOOL_ROUNDS:4}` 默认进 yml + 登记进 ConfigValidationTest 占位符清单；覆盖 ADR 0008/0036 的**依据 = 三账结构**（能力 / 安全 / 有界），归类**所有者政策覆盖**（交 142 落 ADR 0069）；**全档统一不降档**，local 更红照登。

## Not yet specified

<!-- see "Fog of war": in-scope fog you can't ticket yet; graduates as the frontier advances -->

- **反思与提示词的咬合细节**（等「提示词换代」票；数字已定 4/4，见 [136](136-autonomy-budget-and-steps.md)）。
- **预算试跑的规模设计**（等「评测集与预算」票先量出单条成本）。

## Out of scope

- **跨会话记忆、知识反向沉淀**——spec 决策 1 已定「登记不动」（round19 登记第 3/8 项触发线不变），不属于本 map。
- **实现代码本身**——destination 之外；拆票后由 `/to-tickets` → `/implement` 走。
- **IM 多平台、知识服务化**——各自主线已收口（票 99 / 票 91），重开走各自触发条件。
- **叙事换代（README agent 段 / architecture-faq / CHANGELOG）**——所有者 2026-10-18 裁定不纳入本 map；
  实现轮收口时按本仓既有收尾流程处理。

## Tickets（children，135 之外的七张）

| 票 | 类型 | 一句话 | Blocked by |
| --- | --- | --- | --- |
| [136 自主度数字重锚：4 轮 / 4 步是否维持](136-autonomy-budget-and-steps.md) | grilling | 核心票：驱动 ADR 覆盖幅度、plan 契约、提示词、评测与 `plansteps` 风险 | — |
| [137 安全闸清单重列](137-safety-gates-non-retryable.md) | grilling | 旧四类 + 新结构五个出口逐个归类「不可重试」 | — |
| [138 失败三分法按当前 `ToolStatus` 全量重列](138-failure-triage-reclass.md) | grilling | 每格归 ①安全闸/②确定性失败/③可反思；gold 前缀匹配事实核实 | 137 |
| [139 反思的观测面与公开口径](139-reflection-observability-and-enum.md) | grilling | `plan`/PlanStep 契约、SSE 帧、指标名、`FallbackReason` 枚举是否 +1 | 136 |
| [140 评测集与预算](140-eval-suite-and-dev-budget.md) | grilling（AFK 实测先行） | 新集编号（part9 已被酒店占用）、规模、judge 新列、dev 预算抬幅 | 136、138 |
| [141 提示词换代](141-prompt-v1-2-0.md) | grilling | v1.1.0 → v1.2.0：4 轮/4 步 + 反思要模型配合 | 136、139 |
| [142 轮次切分与开轮依据 ADR（0069）](142-round-scoping-and-adr-0069.md) | grilling | 收敛票：ADR 落稿 + 拆票顺序 + spec v2 定稿 | 136-141 全部 |
