# 145 反思观测面 + 响应 token 口径回填（R12）

**Status:** ready-for-agent

## What to build

让反思变成可枚举可判的（触发 / 不触发 / 次数用尽），并把响应里的 token 字段补成全量。
可独立验证的行为：一次反思回合的 `done` 帧 `plan[]` 里能读到 `attempt=2` 且 `reflection=true`，
`shoppilot_reflection_total{outcome}` 两条各 +1，trace 有一行小写头 `reflection=...`；
响应 token 字段在该请求的全部 LLM 调用上求和（含入口层），不再是最后一跳或 0。

- `AgentResult.PlanStep` **只增字段**：`attempt`（1-based，按工具出现次数）+ `reflection`（bool）；
  **失败的那一枪不进 `plan[]`**（否则 `eval_task.py` 取第一个同名工具步会把反思后成功判成「没办成」）。
- 新 counter `shoppilot_reflection_total{outcome="triggered"|"exhausted"}`；指标名 55/57 → **56/58**
  （README:402 与 `docs/EVIDENCE.md` 同步）。
- trace 新增**小写头**行 `reflection=<tool> attempt=<n>`（挂 `AgentState.PLAN`；gold 的 `executed_tools`
  只收大写无空格头，写成大写会把工具序列判红）。
- **R12 三处缺口**：① 预算检查后收尾分支复用 `sum(roundReplies)+final`；② `fallback()` 与
  `pendingApprovalAnswer()` 回填已发生轮次；③ 缓存命中路径把入口层调用计入响应字段。
- **SSE 不加新事件**；**`FallbackReason` 不 +1**（README「枚举 10 / 降级 9」/ EVIDENCE / architecture-faq 零同步）。

## Blocked by

[144](144-reflection-branch.md)（先有反思分支，才有可观测对象）。

## 口径

- 依据：[ADR 0069](../../../docs/adr/0069-round35-agent-autonomy-upgrade-reopen-basis.md) §五与 §六；
  139 的字段 / 载体清单是唯一口径；R12 的触发条件 = 「实现轮动观测面时顺手补」（`registered-debt.md`）。
- JVM：反思回合 `reflection=true` / `attempt=2`、非反思回合无此标记；counter `triggered` 与 `exhausted` 各 1 条。
- `eval_task.py` 夹具回归：失败步不进 `plan[]` 的不变量（task_done 不被反转）。
- 公开口径唯一变动 = 指标名计数；`plan_steps_total` 值域说明（`3`/`4`）随 143 一起写进 EVIDENCE。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/eval_suites.py
python scripts/eval_task.py
git diff --check; git status --short
```

## Handoff notes

（解决时补：关键决策、验证落点、三个现场追问）
