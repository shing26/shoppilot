# 142 轮次切分与开轮依据 ADR（0069）

**Label:** wayfinder:grilling　**Status:** ready-for-agent　（map：[135](135-wayfinder-agent-autonomy-map.md)）

## Question

**收敛票**：把 136-141 的决策收成两件产物——① **ADR 0069 草稿**（开轮依据：覆盖 ADR 0008/0036 数字的依据、
失败三分法语义、spec 决策 1-6 的重锚结论；旧 ADR 原文不动加换代指针，照 ADR 0043 格式）；
② **轮次切分与拆票顺序**（拆几轮/几张实现票、票号从 143 起、每票的 Verify 与判据面）。

## 已核实的事实（不必重查）

- ADR 编号：下一个是 **0069**（0068 已被 round34 占用）；票号：135-142 为本 map，实现票从 **143** 起。
- spec v1 的计划轮廓已过时（「ADR 0052」号位让给了 program、「票号 69 起」「round23 只做 agent 升级」都失效）——
  v2 的定稿在本票完成（作为本票的 linked asset，不粘贴进 body）。
- 开轮本身仍由**所有者政策覆盖**决定（v1.0.0 冻结线；ADR 0030 五条触发未命中）——
  ADR 0069 里要写明「本轮的覆盖依据」，但不预设所有者一定批。
- 一次一票、Handoff 三问、判据红线（gold/阈值/分母/既有验收）照旧。

## 口径

- 本票的 resolution = ADR 0069 草稿 + spec v2 定稿 + 拆票表（三件 linked assets）。
- **不写实现代码**；map 到本票为止（destination 达成）。

## Blocked by

[136](136-autonomy-budget-and-steps.md)、[137](137-safety-gates-non-retryable.md)、[138](138-failure-triage-reclass.md)、
[139](139-reflection-observability-and-enum.md)、[140](140-eval-suite-and-dev-budget.md)、[141](141-prompt-v1-2-0.md)（全部）。

## Verify

```powershell
git diff --check && git status --short
python .scratch/shoppilot-mvp/round3-closeout-audit.py
```

## Handoff notes

（解决时补：ADR 0069 与 spec v2 的落点 + 拆票表 + map 收口）
