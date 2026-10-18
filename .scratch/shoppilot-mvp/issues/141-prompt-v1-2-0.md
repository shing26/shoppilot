# 141 提示词换代：v1.1.0 → v1.2.0

**Label:** wayfinder:grilling　**Status:** ready-for-agent　（map：[135](135-wayfinder-agent-autonomy-map.md)）

## Question

4 轮/4 步 + 反思要模型配合，提示词底座要改什么：预算与步数的说明方式、反思的触发表述、
失败分类的对客表述、以及版本资产与 `meta.json` 的换代方式（是否与 [139](139-reflection-observability-and-enum.md) 的观测字段联动）。

## 已核实的事实（不必重查）

- 现底座：`prompts/agent-system/v1.1.0.md`（round33 换代为**行业无关**底座，11 条规则）；`meta.json` fail-fast；
  `PromptCatalog` 版本进 SSE 与评测报告（票 35）。
- 提示词里的既有边界（改时不得踩）：条款未覆盖→说明并转人工（与 ADR 0068 的空召回出口**同语义**，勿重复）；
  不承诺未执行的动作（ADR 0008「不猜不骗」）；可选参数按默认值直调；纯咨询场景不承诺办理。
- local 3B 的实测弱点：长指令容易丢（round31 的「已提交但没调工具」纠偏、票 56 的编造单号都靠**代码**兜，
  不是靠提示词）——提示词换代的收益要按 dev 档判，local 只照登。

## 口径

- 本票是**决策票**：产出「改动点清单 + 版本号 + 与观测/判据的联动面」；实际改文件在实现轮。
- 历史版本文件保留（v1.0.0/v1.1.0 不删，ADR 0037 家法）。

## Blocked by

[136](136-autonomy-budget-and-steps.md)、[139](139-reflection-observability-and-enum.md)。

## Verify

```powershell
git diff --check && git status --short
```

## Handoff notes

（解决时补：改动点 + 版本号 + 判据联动）
