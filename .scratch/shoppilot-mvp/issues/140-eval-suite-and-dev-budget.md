# 140 评测集与预算

**Label:** wayfinder:grilling（第一步是 AFK 实测）　**Status:** ready-for-agent　（map：[135](135-wayfinder-agent-autonomy-map.md)）

## Question

新评测集的**编号、规模、判据列、预算抬幅**怎么定：集名与编号（part9 已被酒店占用）、条数与分区
（动态规划/反思/超限收口）、judge 新列是否单独成列、dev 预算抬到多少（试跑实测，不拍脑袋）、
是否进 `GOLD_CASE_FILES`。

## 已核实的事实（不必重查）

- `eval/cases-part9-hotel.jsonl` 已被 round32 占用（`kind: hotel`，**不并入 180 条 gold**）→ 新集从 **part10** 起。
- `scripts/eval_suites.py` 按 `kind` 分发判据、自带 0 token 夹具；`plan` kind 的判据是前缀匹配 +
  `plan-aborted` / `plan-rejected` 形态断言（见 [138](138-failure-triage-reclass.md)）。
- ADR 0012 的日预算闸门当前值 `SHOPPILOT_LLM_DAILY_TOKEN_BUDGET=260000`（`.env`）；spec 预估新集 10–20 万 token
  （每条 3–6k），**按实测抬**、不废除闸门本身。
- 先例：part9 的套件带逐条节流（0.25s，≈4 QPS）避免客户级限流（round33 实测 429）。

## 口径

- **第一步是 AFK 实测**：小样试跑量出「每条平均 token / 时长 / 触发限流情况」，读数进 resolution；
  裁定（规模/预算/列）是 HITL。
- 判据红线：180 条 gold / 阈值 / 分母零触碰；新集单独成列（ADR 0050 先例），不进 gold 文件集。

## Blocked by

[136](136-autonomy-budget-and-steps.md)（4 步链决定用例形态）、[138](138-failure-triage-reclass.md)（三分法决定「超限收口」用例）。

## Verify

```powershell
git diff --check && git status --short
```

## Handoff notes

（解决时补：实测读数 + 集编号/规模/列/预算 + 试跑命令）
