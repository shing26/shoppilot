# 139 反思的观测面与公开口径

**Label:** wayfinder:grilling　**Status:** implemented（决策票：字段/载体/口径裁定，零代码改动）　（map：[135](135-wayfinder-agent-autonomy-map.md)）

**Claimed by:** zcode wayfinder session 2026-10-18（第三张：139）

## Question

反思/重试要暴露哪些**可判定**的观测字段，以及会不会动公开口径：
`plan` / `AgentResult.PlanStep` 是否带 attempt 序号与反思标记；SSE 帧要不要加事件；
指标名要不要新增；**`FallbackReason` 枚举要不要 +1**（它会动 README 的「枚举 10 / 降级 9」公开口径）。

## 已核实的事实（不必重查）

- 现契约：`AgentResult.PlanStep(tool, status, latencyMillis, arguments)`（票 48）；`done` 帧带 `plan[]` 与
  `context{ruleIds, historyTurns, estimatedPromptTokens}`（票 48/49）；同步与流式同形。
- `FallbackReason` 当前 10 值；README 的「枚举 10 / 降级 9（= 枚举 − 主动转人工）」是**公开口径**，
  改它要同步 README/EVIDENCE/architecture-faq（票 57 的家法：改生成物的来源票再重跑生成器）。
- 先例：ADR 0050 的 task 判据**单独成列、不并总分**；票 66 的 `source` 标签**加标签不加名**（指标名不换代）。
- 现状指标名 552 读数时代为 55/57（三模块 vs 四模块口径，README 两数并存）。

## 口径

- 本票是**决策票**：产出「字段清单 + 载体（AgentResult/SSE/指标）+ 是否动公开口径及同步面」。
- 反思的观测必须是**枚举可判**的（spec 风险节：不能判「看起来聪明」）。

## Blocked by

[136](136-autonomy-budget-and-steps.md)（4 轮的形态定了才知道要观测什么）。

## Verify

```powershell
git diff --check && git status --short
```

## Resolution（2026-10-18，grilling 两问均未获回答——按本票建议值记录并逐条标「建议」；决策票，零代码改动）

**会话记录**：两问（观测载体组合 / `FallbackReason` 是否 +1）均未获回答；按建议值记录，
所有者可在 [142](142-round-scoping-and-adr-0069.md) 落 ADR 0069 前修正（137/138 同例）。

**判据一句话**：反思的观测必须能判**三态**（触发 / 不触发 / 次数用尽）——
载体 = `plan[]` 只增字段 + 1 个新指标 + trace 小写行；**公开口径零变动**（`FallbackReason` 不 +1）。

### 一、字段与载体清单（建议值）

| 载体 | 变化 | 内容 | 依据 / 约束 |
| --- | --- | --- | --- |
| `AgentResult.PlanStep` | **只增字段** | `attempt`（int，1-based，按工具出现次数）+ `reflection`（bool，本步是反思重试时 true） | ADR 0044 / 票 48「只增字段」先例；**失败的那一枪不进 `plan[]`**（沿用 138/现语义——否则 `eval_task.py` 的 `next(step if tool == target)` 会先撞到失败步，把反思后成功的回合判成「没办成」） |
| 指标 | **+1 个名字** | `shoppilot_reflection_total{outcome="triggered"\|"exhausted"}` | 指标名 55→**56**（三模块）/ 57→**58**（四模块）；README:402 + EVIDENCE 同步（票 57 家法）。如需按工具聚合，实现轮可加 `tool` 标签（加标签不加名，票 66 先例） |
| trace | 新增**小写头**行 | `reflection=<tool> attempt=<n>`（挂 `AgentState.PLAN`） | **必须小写头**：gold 的 `executed_tools` 只收「大写且无空格的头」（`corrective=` 同例）——写成大写会把工具序列判红 |
| SSE | **不加新事件** | 失败与重试两次 `tool_executing`/`tool_result` 自然可见 | 不加线上事件面；机器判据走 `done` 帧的 `plan[]`（同步/流式同形照旧） |
| 同步响应 | 随 `plan[]` 自动带出 | 同上 | 无新字段、无新端点 |

### 二、三态怎么判（可判定规则）

- **触发**：`plan[]` 存在 `reflection=true` 的步，或 counter `outcome="triggered"` 增量 ≥1。
- **不触发**：①闸 / ⓪非失败 / 模型输出层错误 / LLM 失败 / 写工具③失败的回合里，`plan[]` 无 `reflection=true`，counter 无增量。
- **次数用尽**：同工具第二次③失败 → counter `outcome="exhausted"` +1，且该回合带 `fallbackReason=TOOL_UNAVAILABLE`（138 的「原语义收口」）。

### 三、公开口径：零变动（建议值）

- **`FallbackReason` 不 +1**：反思不是降级出口——反思用尽后仍走 `TOOL_UNAVAILABLE`。README 的「枚举 10 / 降级 9」、
  EVIDENCE、architecture-faq **零同步**（票 57 的家法：动枚举才需要同步生成物，本票不动）。
- 唯一动的公开数字是**指标名计数**（+1，见一节）；README 两数并存口径 55/57 → **56/58**。
- 降级工单描述**不加**反思线索（建议）：counter + trace 已足够归因，避免动工单文本面。

### 四、给 [141](141-prompt-v1-2-0.md) 与 [142](142-round-scoping-and-adr-0069.md) 的输入

- **141（提示词）**：反思重试由系统发起（失败事实回填 + 一条失败上下文说明），模型只需「带失败上下文重规划」；
  提示词不新增对外承诺（「我会重试」之类），预算说明按 [136](136-autonomy-budget-and-steps.md) 的 4 轮 / 4 步写。
- **142（ADR 0069）**：观测面三件（`plan[]` 字段 / 指标 / trace 行）+ 口径零变动 + 指标名计数换代（55/57→56/58）
  写进 ADR 落地清单；`plan_steps_total` 值域扩展（3/4，136）与 `reflection_total` 新增一起进 EVIDENCE。

## Handoff notes

**关键决策**：见 Resolution 一、二、三节。一句话：**plan[] 只增 `attempt`+`reflection`、加一个
`shoppilot_reflection_total{outcome}`、trace 小写行；SSE 不加事件；`FallbackReason` 不 +1（公开口径零变动）**。
两问均未获答、按建议值记录——修正入口在 142。

**验证落点**：决策票，文档级验证 = `git diff --check` + `git status --short`。机器落点留给实现轮：
`PlanStep` 字段的 JVM 断言（反思回合 `reflection=true`/`attempt=2`；非反思回合无此标记）、
counter 的 JVM 断言（triggered/exhausted 各 1）、`eval_task.py` 夹具回归（失败步不进 plan 的不变量）、
README/EVIDENCE 的指标名计数换代。gold / 判据面零触碰。

**三个现场追问**：

1. 为什么不给 `PlanStep` 加「失败尝试」条目？——`eval_task.py` 取**第一个**同名工具步：失败步进 `plan[]`
   会把「反思后成功」判成「没办成」（task_done 反转）。失败面由 trace + counter 承载，`plan[]` 只记成功步。
2. 为什么 SSE 不加 `reflection` 事件？——两次 `tool_result` 已把「同一工具失败后又成功」摆在流上；
   新事件只增加客户端契约面，机器判据用 `done` 帧 `plan[]` 就够（`verify-console.mjs` 只断 `Array.isArray(plan)`，加字段安全）。
3. 为什么不动 `FallbackReason`？——反思不是出口、第二次失败就是原语义的 `TOOL_UNAVAILABLE`；
   动枚举要同步 README/EVIDENCE/architecture-faq 三处生成物，而「能区分」已由 counter 的 `outcome` 标签给到。
