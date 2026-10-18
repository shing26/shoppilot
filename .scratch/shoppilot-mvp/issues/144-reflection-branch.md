# 144 反思分支：读工具操作性失败一次（占轮、不绕闸）

**Status:** implemented（2026-10-19）

**Claimed by:** zcode implement session 2026-10-19（round35 实现轮：143 配置面收口 + 144 反思分支；与并行 session 同工作树，143/147/148 的落地由两个 session 各自登记）

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

**关键决策**：

1. **三处挂点、同一规则、两种动作形态**。规则三处完全相同：只认**读工具**、键 = `ToolName` 合计 **1 次**、
   失败枪**占轮**（`rounds` 已含它）。动作按路径的形态落地——
   **主循环**（`degraded()` 分支）= 失败事实回填（与那条 assistant 的 `tool_call` 配对）+ 一条失败上下文
   → `continue` 进下一轮**模型重规划**（票 138 规则二的原文形态）；
   **网关派生枪**与**槽位续办枪**= 这两条路径**没有"下一轮"**（派生枪是模型刚明确没发 function call 后
   网关自己开的确定性一枪，续办枪的工具与槽位来自会话待办），所以反思 = 带上失败事实**重试同一枪一次**
   （重试仍走同一个 `dispatch`，槽位 / 溯源 / 幂等 / 行业门全套照过）。
2. **闸判定先于反思是结构保证，不是顺序约定**：反思分支只挂在 `dispatch.degraded()`（③操作性）上；
   ①闸（`NOT_FOUND` / `STATE_NOT_ALLOWED`）走 `failedStep` 的 `plan-aborted` 原出口，编造拦截 / 缺槽 /
   受理 / 幂等回放 / 未知工具 / 计划表达式拒收 / LLM 失败各有各的原出口——反思在结构上够不到它们。
   重试撞闸就走该闸原出口、不再反思（第二条变异对照正是这条的守门人）。
3. **写工具一律不反思**：`applyRefund` / `modifyDeliveryAddress` 的③失败照旧 `TOOL_UNAVAILABLE` + 工单。
   理由（ADR 0069 §四）：结果不明时自动重试资金动作风险大于收益，改参重试还会绕开两层幂等。
4. **观测面零新增**（counter / trace 小写行 / `PlanStep.attempt|reflection` 全是票 145 的落地面）：
   144 的判据全部由**既有**可观测面读出——派发次数、规划次数、`plan[]`、下一轮 `messages` 的失败事实、
   `fallbackReason`、trace 的 `plan-aborted`。失败枪不进 `stepResults` / `plan[]`（沿用现语义），
   `eval_task.py`「取第一个同名工具步」不受扰动；gold 的 `PLN-ABORT-*` 形态原样保住（①闸不反思）。
5. **失败上下文文案**（`REFLECTION_CONTEXT`）与 v1.2.0 提示词第 4 条尾句同调（换工具或换参数再试一次），
   但**不教模型给失败分类**——分类判定全在状态机（ADR 0069 否决了提示词驱动的反思）。

**验证落点**：

- `.\mvnw.cmd -B -ntp -pl shoppilot-gateway -am -Dtest=ReflectionLoopJvmTest -Dsurefire.failIfNoSpecifiedTests=false test`
  → **10/10 绿**：票面七格（`notFoundAbortsPlanWithoutReflection` / `stateNotAllowedAbortsPlanWithoutReflection` /
  `pendingApprovalNeverReflects` / `idempotentReplayContinuesWithoutReflection` /
  `operationalTimeoutReflectsOnceThenSucceeds` / `secondOperationalFailureOfSameToolFallsBack` /
  `reflectionRetryHittingGateAbortsWithoutSecondReflection`）+ 三格覆盖另两处挂点与写工具
  （`writeToolOperationalFailureNeverReflects` / `gatewayDerivedReadFailureReflectsOnce` / `resumedReadFailureReflectsOnce`）。
- **变异对照两条实测为真**（各跑一次、记录转红用例名、还原后复跑绿）：
  ① **抽掉反思分支**（`if (false && reflectionEligible(...))`）→ **`operationalTimeoutReflectsOnceThenSucceeds` 红**
  （`verify(bizMock, times(2))` 落空：只剩一次派发）；
  ② **闸判定挪到反思之后**（把反思分支的触发面从 `dispatch.degraded()` 放宽到 `failedStep(...)`，闸出口留在原处）
  → **`reflectionRetryHittingGateAbortsWithoutSecondReflection` 红**（重试撞闸又被反思吞掉、派发到第三枪），
  并连带 **`notFoundAbortsPlanWithoutReflection` 红**（①闸整体被吞）。还原后 10/10 复绿。
- 全量 `.\mvnw.cmd -B -ntp verify` 见同一提交的收口记录；`git diff --check` / `git status --short` 无混入。
- 判据红线：gold / 阈值 / 分母 / `judge()` **零触碰**；提示词文件一字未动（v1.2.0 已在票 147 落地）。

**三个现场追问**：

1. 为什么派生/续办两处的动作不是"下一轮重规划"？——那两条路径没有"下一轮"：模型刚刚明确没发 function call
   （派生）或工具与槽位来自会话待办（续办），枪是网关自己开的。规则（读工具 / 一次 / 占轮 / 写工具不反思）
   三处同一，动作按路径形态落地；要"重规划"就得把派生枪挪进循环体，那会同时改掉 `plan_steps_total` 的
   计账时机（派生枪今天不进 `plan_steps_total`），属于票外的读数变动，不做。
2. 反思会不会把闸放宽？——不会。反思只在③上触发；重试走同一个 `dispatch`，槽位 / 溯源（`isUntrustedOrderNo`）/
   幂等 / 审批 / 行业门全套照过。第二条变异对照就是这条的守门人（放宽即红）。
3. 失败枪不进 `plan[]`，会不会让 `toolUsed=true` 与 `plan=[]` 同现？——会，且这正是票 138 要求的形态
   （失败枪不进 `plan[]` 是 `eval_task.py` 取"第一个同名工具步"不变量成立的前提）。出现场景 = 反思后模型
   改用正文收尾（没有再调工具）；此时 `plan[]` 空、`toolUsed=true`、`fallbackReason=null`，读 plan 的
   `task_done` 不会把它当成办成（它读的是 plan 里的工具步）。
