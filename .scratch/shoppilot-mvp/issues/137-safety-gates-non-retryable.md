# 137 安全闸清单重列：新结构下哪些出口不可重试

**Label:** wayfinder:grilling　**Status:** ready-for-agent　（map：[135](135-wayfinder-agent-autonomy-map.md)）

## Question

spec 决策 4 列的「安全闸四类不可重试」是 2026-10-01 的结构。当前结构下，**「不可重试」的确定性出口完整清单**是什么——
旧四类之外，审批闸、角色拒、空召回出口、写动作守卫、限流各自算不算、以什么形式算？

## 已核实的事实（不必重查）

- **旧四类**（spec 决策 4）：归属拒绝、编造拦截（`ToolDispatcher.isUntrustedOrderNo`）、幂等回放
  （`IDEMPOTENT_REPLAY`）、终态拒绝（`STATE_NOT_ALLOWED`）。
- **新结构候选**（2026-10-01 之后落地）：
  - 审批闸 `PENDING_APPROVAL`（round21 票 59）：**受理不是失败**，走正常出口——但它是不是「反思不得触碰」的语义？
  - 角色拒 `role.denied`（round30 / ADR 0063）：发生在**网关层**，不在工具循环内——要不要在清单里显式写明「循环外的闸」。
  - 空召回确定性出口 `INTENT_UNRESOLVED`（ADR 0068）：在**检索之后、模型之前**——同样在循环外。
  - 写动作守卫（票 41）：模型承诺写动作但没调工具时的纠偏（一次）——与「反思」语义相邻，必须划界。
  - 限流（ADR 0007）：准入层，循环外。
  - `ShopRuleIndex` 探针（ADR 0068）：**不是闸**，是缓存共享前置——列为「非闸」以免清单膨胀。
- 相关既有语义：`TOOL_ROUNDS_EXHAUSTED` 超限收口；`ToolStatus` 当前 6 成员
  （`OK / NOT_FOUND / STATE_NOT_ALLOWED / PENDING_APPROVAL / IDEMPOTENT_REPLAY / TIMEOUT`）。

## 口径

- 本票是**决策票**：产出「不可重试清单 + 每条所在层（网关/状态机/biz-mock）+ 与反思的边界一句话」。
- 理由线照 spec：反思重试被安全闸拦下的调用 = 换着花样绕闸；跨租户串号 0 次不可被威胁。

## Blocked by

无（frontier）。

## Verify

```powershell
git diff --check && git status --short
```

## Handoff notes

（解决时补：清单 + 每条的依据 + 给 [138](138-failure-triage-reclass.md) 的输入）
