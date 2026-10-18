# 143 工具循环上限换代：4 轮（配置面 + 超限用例重钉）

**Status:** ready-for-agent

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

（解决时补：关键决策、验证落点、三个现场追问）
