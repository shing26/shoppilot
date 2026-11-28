# 票 235：退款回读结构化（U12 收窄）

Round: 63（内部试用就绪第一波；ADR 0091）
ADR: 0047（退款审核闸门）、0091（程序范围）
Status: in-progress　Claimed by: Codex　Blocked by: 无（234 已于 2026-11-28 收口）
契约：[plans/235-refund-readback-structured.md](../../plans/235-refund-readback-structured.md)（2026-11-28 冻结）

## What to build

保留现有 `refundReview` 与中文文案，新增机器可读的到账边界字段：

1. `OrderView` / `OrderDetailView` 增加 `payoutBoundary`，无退款为 null；
2. 有退款时值为 `PAYMENT_CHANNEL`，表示到账权威在支付渠道，不在本仓；
3. `verify-refund-approval.ps1` 增加结构化断言，同时保留原「放行 / 支付渠道」文本断言；
4. 前端类型与订单详情展示同步，不把 `RELEASED` 写成「已到账」。

## 边界

- 不换本地模型、不追 local 27/2、不改 gold / 阈值 / 分母 / `judge()`。
- 不删除或弱化自由文本断言；结构化字段是 add-only。
- 不新增退款终态，不把 `PROCESSING` 改名为 `REFUNDED`。

## Verify

见契约 AC1–AC8。

## Handoff notes

**开票时事实（2026-11-28）**

- `RefundReviewState` 与 `OrderView.refundReview` 已存在；映射为
  `PENDING_REVIEW→PENDING_REVIEW`、`PROCESSING→RELEASED`、`REJECTED→REJECTED`。
- `verify-refund-approval.ps1` 第 4 步仍靠自由文本「放行」+「支付渠道」；
  local 3B 回读措辞不稳定。
- 本票只做结构化字段与断言，不解决 local 3B 工具派发率。
