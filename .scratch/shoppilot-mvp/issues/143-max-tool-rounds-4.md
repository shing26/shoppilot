# 143 工具循环上限换代：4 轮（配置面 + 超限用例重钉）

**Status:** implemented（2026-10-19）

**Claimed by:** zcode implement session 2026-10-19（round35 实现轮；145 的前置依赖）

## What to build

把工具循环的硬上限从 2 轮换成 **4 轮**，全档统一、默认即新行为；计划步数与工具轮同源、自动 ≤4，
**不设第二个校验面**。这是一条可独立验证的完整路径：默认配置起网关 → 模型可连续发起 4 次工具调用 →
第 5 次落 `TOOL_ROUNDS_EXHAUSTED` 工单。

- `application.yml` 的 `shoppilot.agent.max-tool-rounds` 改为 `${SHOPPILOT_AGENT_MAX_TOOL_ROUNDS:4}`
  （默认 4；env 只作调档手段，ADR 0012 家法）。
- `ConfigValidationTest` 的占位符钉死清单登记 `SHOPPILOT_AGENT_MAX_TOOL_ROUNDS`（开面必须登记，不绕门禁）。
- `.env.example` 按 `SHOPPILOT_LLM_DAILY_TOKEN_BUDGET` 先例注释登记一行。
- `GatewayProperties.Agent.maxToolRounds` 的 `@Min(1)` 保留；默认值 4 由 JVM 断言钉住（从生产 yml 绑定）。
- `GatewayMainPathJvmTest` 两个 `TOOL_ROUNDS_EXHAUSTED` 用例按默认 4 重钉：stub 5 回复 / `times(4)` 派发 /
  `times(5)` 规划（**语义不变，改的是被钉的数字**）。

## Blocked by

None — can start immediately。

## 口径

- 依据：[ADR 0069](../../../docs/adr/0069-round35-agent-autonomy-upgrade-reopen-basis.md) §三（三账）；
  ADR 0008 / ADR 0036 原文一字不改，换代指针在 ADR 0069 / spec v2 / tracker。
- 超限出口不变：预算检查后模型仍要工具 → `TOOL_ROUNDS_EXHAUSTED` 落工单。
- `plan_steps_total{steps}` 的值域将出现 `3`/`4`（只是读数，不改口径）。
- **全档统一不降档**：local 3B 的 `plansteps` 更红照登，判据不动（round20 家法）。
- `verify-plan.ps1` 读 `steps="2"` 不受影响（两步链仍记 2）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
git diff --check; git status --short
```

## Handoff notes

**关键决策**：

1. **默认值单一来源仍是 yml**：`max-tool-rounds: ${SHOPPILOT_AGENT_MAX_TOOL_ROUNDS:4}`；env 只作调档手段（ADR 0012 家法）。
   `GatewayProperties.Agent` 的 `@Min(1)` 与 `ConfigValidationTest` 的两条既有断言（≥1、占位符清单）一字未动，
   只**新增**一条默认值断言（`maxToolRoundsDefaultsToFour`，从生产 yml 绑定而来，不是用例自己钉的数）。
2. **三条按轮数计账的用例重钉**（票面写「两个 `TOOL_ROUNDS_EXHAUSTED`」，实际受影响的是三条：
   轮尽落单、写动作轮尽落单、预算检查后收尾出答案——第三条的桩序列同样按轮数写死，不改必然 NPE）：
   `GatewayMainPathJvmTest` 的桩序列 3 → 5 回复、派发 2 → 4、规划 2-3 → 4-5；语义不变，改的是被钉的数字。
3. **`PlanExecutionTest` / `ConversationOwnershipTest` / `TraceCorrelationAcrossAsyncTest` 的桩值也跟到 4**
   （它们 mock 的是 `GatewayProperties`，值只作脚手架；`PlanExecutionTest.twoStepPlanResolvesPriorStepReference`
   的桩序列补了一条收尾正文——4 轮预算下两步链不再撞上限，收尾走正常的流式总结那一支）。
   同文件 `planStepsAreReportedInExecutionOrder` 补 `llm.stream` 桩：**这是本次唯一一处「用例形状」变化**，
   根因就是「两步链不再是预算上限上的链」。

**验证落点**：

- `.\mvnw.cmd -B -ntp -pl shoppilot-gateway -am -Dtest='ConfigValidationTest,GatewayMainPathJvmTest,PlanExecutionTest,ConversationOwnershipTest,TraceCorrelationAcrossAsyncTest' -Dsurefire.failIfNoSpecifiedTests=false test`
  → `68` 条全绿（含新增默认值断言）。
- 全量 `.\mvnw.cmd -B -ntp verify` 见同一提交的收口记录；`git diff --check` / `git status --short` 无混入。
- 口径：`plan_steps_total{steps}` 值域自此会出现 `3`/`4`（只是读数、不改口径，已登记 EVIDENCE）；
  `verify-plan.ps1` 读的 `steps="2"`（两步链）不受影响。

**三个现场追问**：

1. 为什么默认值断言放在 `ConfigValidationTest` 而不是 `GatewayMainPathJvmTest`？——后者全程 mock `GatewayProperties`，
   在那里断言 4 等于用例自己钉自己；只有跑生产 yml 绑定的那条缝能证明「默认值真的换了」。
2. 为什么 `PlanExecutionTest` 的默认桩也换成 4？——它就是状态机语义的 0 token 主战场，
   桩值留在 2 会让「默认配置下的行为」这一层没有任何用例覆盖；旧值 2 仍是合法配置（env 可调），只是不再是默认。
3. 轮次换代会不会动 gold？——不会：gold 的 `plansteps` 判据读的是 `steps` 计数与中止形态，
   本轮只把上限从 2 抬到 4，一枪都没改判据、阈值与分母；local 3B 的 `plansteps` 更红照登（round20 家法）。
