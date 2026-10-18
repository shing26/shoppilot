# 139 反思的观测面与公开口径

**Label:** wayfinder:grilling　**Status:** ready-for-agent　（map：[135](135-wayfinder-agent-autonomy-map.md)）

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

## Handoff notes

（解决时补：字段与载体清单 + 公开口径是否变动 + 给 [141](141-prompt-v1-2-0.md) 与 [142](142-round-scoping-and-adr-0069.md) 的输入）
