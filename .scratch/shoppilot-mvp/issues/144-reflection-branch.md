# 144 反思分支：读工具操作性失败一次（占轮、不绕闸）

**Status:** ready-for-agent

## What to build

在工具循环里为**读工具**的③操作性失败加一次由系统发起的反思重试，并保证它不改变任何闸的判定。
可独立验证的行为：读工具先 `TIMEOUT`/`UNAVAILABLE`、带失败上下文重规划后成功（`plan[]` 只有成功那一枪）；
同一工具第二次③失败则按原语义降级落工单；写工具失败、闸拦下的调用、LLM 失败都不反思。

- **触发**：工具返回 `TIMEOUT` / `UNAVAILABLE`，且是**读工具**、该工具③类反思次数 < 1、且还有轮预算。
- **动作**：结构化失败事实回填 messages + 一条失败上下文说明 → 下一轮重规划；**占** 4 轮预算、不加轮次；
  失败的那一枪不进 `stepResults` / `plan[]`。
- **上限**：键 = `ToolName`，同一工具任何③类失败**合计 1 次**；第二次（无论哪种）按原语义收口 =
  `degraded()` 的 fallback（`TOOL_UNAVAILABLE` + 落工单）。
- **不触发**：写工具（`applyRefund` / `modifyDeliveryAddress`）的③失败照旧 fallback；①闸
  （`NOT_FOUND` / `STATE_NOT_ALLOWED` / `IDEMPOTENT_REPLAY`）与⓪非失败（`OK` / `PENDING_APPROVAL`）照旧；
  模型输出层错误（未知工具、计划表达式拒收）与 LLM 失败不反思。
- **不得绕闸**：反思后的重试仍过全部闸（编造拦截 / 幂等 / 审批 / 归属 / 终态 / 行业门）——闸判定先于反思，
  重试撞闸走该闸原出口、不再反思。
- **挂点三处同规则**：`AgentStateMachine` 的 `degraded()` 分支、网关派生路径、槽位续办路径（都只可能是读工具）。

## Blocked by

[143](143-max-tool-rounds-4.md)（反思占的 4 轮预算先要存在）。

## 口径

- 依据：[ADR 0069](../../../docs/adr/0069-round35-agent-autonomy-upgrade-reopen-basis.md) §四；
  138 的映射表与规则六条是唯一口径（ADR 0069 引用）。
- JVM 用例（0 token）至少七格：`notFoundAbortsPlanWithoutReflection`、`stateNotAllowedAbortsPlanWithoutReflection`、
  `pendingApprovalNeverReflects`、`idempotentReplayContinuesWithoutReflection`、
  `operationalTimeoutReflectsOnceThenSucceeds`、`secondOperationalFailureOfSameToolFallsBack`、
  `reflectionRetryHittingGateAbortsWithoutSecondReflection`。
- **变异对照两条必须实测为真**：抽掉反思分支 → `operationalTimeoutReflectsOnceThenSucceeds` 红；
  把闸判定挪到反思之后 → `reflectionRetryHittingGateAbortsWithoutSecondReflection` 红。
- gold 零触碰：①/②照旧中止，`PLN-ABORT-*`（`plan-aborted` 前缀）形态原样保住。
- 与写动作守卫（票 41 纠偏）划界：纠偏治「没做」（不占轮、上限 1 次），反思治「做了但失败」（占轮）；互不替代。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
git diff --check; git status --short
```

（两条变异对照各跑一次并记录转红的用例名；还原后复跑绿。）

## Handoff notes

（解决时补：关键决策、验证落点、三个现场追问）
