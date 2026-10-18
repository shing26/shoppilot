# 138 失败三分法按当前 `ToolStatus` 全量重列

**Label:** wayfinder:grilling　**Status:** ready-for-agent　（map：[135](135-wayfinder-agent-autonomy-map.md)）

## Question

spec 决策 5 的失败三分法（①安全闸→原出口 / ②确定性业务失败→照旧中止 / ③操作性失败→才反思换路）
要按当前 `ToolStatus` **全量重列每一格**：六个成员各归哪一类，边界用什么 JVM 用例钉死（含变异对照）。

## 已核实的事实（不必重查）

- `ToolStatus` 当前 6 成员：`OK / NOT_FOUND / STATE_NOT_ALLOWED / PENDING_APPROVAL / IDEMPOTENT_REPLAY / TIMEOUT`
  （`shoppilot-tool-api/.../view/ToolStatus.java`）。
- gold 的 plan 判据是**前缀匹配**（`eval_suites.py:103`）：`PLN-ABORT-*` 的形态靠 `abort_ok`
  （details 前缀 `plan-aborted`）与 `rejected_ok`（`plan-rejected`）——**②类照旧中止是保住这两条的键**。
- spec 决策 5 的原文要求：分类边界漂移会打 gold → JVM 钉死三分法每一格（变异对照）。
- 与之相邻、必须在票内划界的既有语义：写动作守卫（票 41 的纠偏一次）、空召回出口（循环外）、
  审批闸（受理非失败）——它们的清单来自 [137](137-safety-gates-non-retryable.md)。

## 口径

- 本票是**决策票**：产出「成员 → 类别 → 出口/中止/反思」的映射表 + 每格要钉的 JVM 用例名（草）。
- 「反思最多一次、同工具同型失败第二次按原语义收口」（spec 决策 4）在本票写成可判定的规则（触发/不触发/次数上限），
  不许判「看起来聪明」。

## Blocked by

[137](137-safety-gates-non-retryable.md)（安全闸清单先定，才能归类）。

## Verify

```powershell
git diff --check && git status --short
```

## Handoff notes

（解决时补：映射表 + 用例草单 + 给 [140](140-eval-suite-and-dev-budget.md) 的输入）
