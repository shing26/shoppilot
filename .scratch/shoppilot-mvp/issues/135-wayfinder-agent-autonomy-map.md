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
- [137 安全闸清单重列](137-safety-gates-non-retryable.md) — **三段式清单**：闸 6 条（旧四类 + 审批闸「终态受理」+ 行业门「前置裁剪」——反思重试/换路即绕闸，一律走原出口）/ 循环外 6 出口登记为边界（限流·情绪门·显式转人工·请求级回放·空召回·角色拒「面外」）/ 非闸明确排除；**反思只在工具循环内、只作用于③操作性失败**。给 138 的输入：闸集 + `PENDING_APPROVAL` 不硬塞三类 + **`ToolStatus` 实为 7 成员**（票面 6 漏 `UNAVAILABLE`）。附带发现：写动作守卫没吃行业门（登记 R11，实现轮修）。
- [138 失败三分法按当前 `ToolStatus` 全量重列](138-failure-triage-reclass.md) — **7 成员四栏映射**（①闸 3：NOT_FOUND/STATE_NOT_ALLOWED/IDEMPOTENT_REPLAY；②**空**（判据保留）；③操作性 2：TIMEOUT/UNAVAILABLE；⓪非失败 2：OK/PENDING_APPROVAL）+ **反思规则六条**：触发=读工具③失败、动作=失败事实回填重规划（占 4 轮预算）、**上限=按工具合计 1 次**（第二次按原语义 fallback）、**写工具不反思**、模型输出层错误与 LLM 失败不反思、**反思不得绕闸**。JVM 用例草单 + 变异对照两条已列；gold `PLN-ABORT-*` 形态原样保住。**三问均未获答、全部按建议值记录**（修正入口 142）。
- [139 反思的观测面与公开口径](139-reflection-observability-and-enum.md) — **观测三件 + 口径零变动**：`PlanStep` 只增 `attempt`+`reflection`（**失败的那一枪不进 plan[]**——否则 `eval_task.py` 的 `next(tool==target)` 会把反思后成功判成没办成）+ 新 counter `shoppilot_reflection_total{outcome=triggered|exhausted}`（指标名 55/57→**56/58**）+ trace **小写头**行（gold 解析器只收大写头，写错会把工具序列判红）；**SSE 不加事件**；**`FallbackReason` 不 +1**（README「枚举 10 / 降级 9」零同步）。三态判据（触发/不触发/次数用尽）已成文。**两问均未获答、按建议值记录**。
- [141 提示词换代：v1.1.0 → v1.2.0](141-prompt-v1-2-0.md) — **实质改动 = 第 4 条一句扩写**（「若系统随后给了失败上下文，换一种做法再试一次；仍失败照实说明并转人」，反思配合折进现条、**保持 11 条**）；**预算数字不进提示词**（配置唯一源，第 9 条「轮次有限」不动）；版本三件 = 新文件 v1.2.0 + `meta.json` + **四处钉子同步**（`PromptCatalogTest` 的版本/11 条/首尾锚点、两个 `promptVersion` 断言）；评测读数按 v1.2.0 归因（与 v1.1.0 不可混比）。**两问均获所有者裁定**。

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
