# 136 自主度数字重锚：4 轮 / 4 步是否维持

**Label:** wayfinder:grilling　**Status:** ready-for-agent　（map：[135](135-wayfinder-agent-autonomy-map.md)）

## Question

工具循环 **2 → 4** 轮、计划 **≤2 → ≤4** 步这两个数字，在当前结构下**维持 4/4**，还是调整
（如 3/3、或轮与步分开取值）？以及它们如何进可配置面（env 变量名、默认值、与 ADR 0012 家法一致）。

## 已核实的事实（不必重查）

- ADR 0008：工具循环硬上限 2；ADR 0036：Plan ≤2，且**步骤数不是独立校验，就是工具循环的上界**——
  覆盖这两个数字需要新 ADR 写覆盖依据 + 旧 ADR 原文不动加换代指针（ADR 0043 先例）。
- 配置面：`GatewayProperties.Agent(maxToolRounds, maxSlotAsks, sessionTtl, historyTurns)`；
  `PlanExpression` 支持 `{steps[i].result.<field>}` 引用前步结果。
- 空召回确定性出口（ADR 0068）在**工具循环之前**，不占轮次；审批闸 `PENDING_APPROVAL` 不是失败。
- local 3B 出不了两步链（`plansteps` 步 0/7，已登记）；4 步只会更红——**照登，判据不动**（round20 家法）。
- gold 的 plan 判据是**前缀匹配**（`eval_suites.py:103` `tools[:len(expect["plan"])] == expect["plan"]`）——
  计划变长不破，中途插重试会破。

## 口径

- 本票是**决策票**：产出数字、env 名与默认值、以及「为什么是这个数」的理由；不写实现代码。
- 决策要考虑：超限照旧落 `TOOL_ROUNDS_EXHAUSTED` 工单（ADR 0008 的出口语义不变）。

## Blocked by

无（frontier）。

## Verify

```powershell
git diff --check && git status --short
```

## Handoff notes

（解决时补：决策 + 依据 + 影响的 ticket）
