# Interview Feedback Ledger

用于记录 `v1.0.0` 冻结后的真实面试信号。没有达到 ADR 0031 门槛的内容只登记；达到门槛才允许开新票。

| Date | Company / Role | Question or gap | Seen count | Fixable in 1 day | Existing trigger | Decision |
| --- | --- | --- | ---: | --- | --- | --- |

## Decision Rules

- `0-1` 次出现：只登记。
- 同一缺口出现 `2+` 次且可在 1 个工作日内补齐：开一个 tracer-bullet ticket。
- 同一缺口出现 `2+` 次但超过 1 个工作日：先写新 spec，不直接实现。
- 要改指标、判据、阈值或 gold：必须命中 ADR 0030 的对应条件。
- 需要重写架构：作为新 effort 立项，不悄悄重开 `v1.0.0`。
