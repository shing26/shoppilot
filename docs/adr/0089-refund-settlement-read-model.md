# ADR 0089：退款终态读模型——`SETTLED` 只由支付通道 `SUCCESS` 驱动

Status: Accepted（2026-11-26 所有者确认「接受 A，按提案默认值继续」）
Date: 2026-11-26
Deciders: 所有者
Round: 62（契约：`plans/232-commerce-kernel-promotion.md`）
Related: [ADR 0009](0009-escalation-lands-in-persisted-ticket.md)、
[ADR 0063](0063-b3-money-action-non-repudiation.md)、
`docs/shopify-refund-contract-mapping.md` §5 H4

## Context

现有 `biz-mock` 把 `PROCESSING` 当作退款流程的终点，对外靠「到账由支付渠道处理」兜住。
这在 mock 里成立，在真实店铺里不成立：Shopify 的退款会生成 `OrderTransaction`，
其 `status` 可能是 `PENDING`、`SUCCESS`、`FAILURE` 或 `ERROR`。`refundCreate` 返回成功
只说明退款单已创建，不说明钱已退出。

如果把「退款单创建成功」说成「已退款」，就是对外虚报；如果只说「处理中」，
又无法回答买家最关心的「到底到没到」。

## Decision

### 1. 内核用六态读模型，终态与提交态分离

```
REQUESTED -> APPROVED -> SUBMITTED -> SETTLED
         \-> REJECTED        \-> FAILED
```

- `REQUESTED`：本地已受理，资金未动；
- `APPROVED`：人工已放行，外部请求尚未成功；
- `SUBMITTED`：外部已受理，**不承诺到账**；
- `SETTLED`：支付通道终态 `SUCCESS`；
- `FAILED`：支付通道 `FAILURE`/`ERROR`，或不可重试的提交错误；
- `REJECTED`：人工驳回。

终态（`SETTLED`/`FAILED`/`REJECTED`）不可再迁移。

### 2. `createRefund` 可以立即返回 `SETTLED`，但只能因为看到 `SUCCESS`

`RefundReceipt` 允许 `SUBMITTED` 与 `SETTLED` 两种状态。适配器只有在响应中的
`OrderTransaction(kind=REFUND, status=SUCCESS)` 上才能返回 `SETTLED`；
仅有「退款单创建成功」时必须返回 `SUBMITTED`。

这条同时修正草案里的一处自相矛盾：`CommerceGateway` 的 Javadoc 原写
「createRefund 最多返回 SUBMITTED」，但 `RefundReceipt` 与 Shopify 适配器都允许
`SETTLED`。以本 ADR 为准。

### 3. 结算推进靠轮询，Webhook 是下一版

v0 用 `RefundWorkflow.refresh` 调 `getRefundStatus` 推进
`SUBMITTED → SETTLED|FAILED`。Webhook 验签、按投递 ID 去重、乱序容忍与对账补偿
放到 v1；在 Webhook 上线前，轮询任务必须可重入、可限流，并按 `tenant` 隔离。

### 4. 对外话术按状态分档，禁止越级

- `REQUESTED/APPROVED`：退款申请已受理，等待放行；
- `SUBMITTED`：退款已提交，正在等待支付渠道处理；
- `SETTLED`：退款已到账（以支付通道终态为准）；
- `FAILED`：退款未成功，需要人工处理。

任何界面、日志、指标都不得把 `SUBMITTED` 标成「已退款」。

## Consequences

- **正面**：对外承诺与真实资金状态一致；失败有明确出口；可度量
  `SUBMITTED → SETTLED` 时延与失败率。
- **负面**：新增对账职责与一条轮询链路；`FAILED` 之后若要重试，需要新 intent 与新 key，
  不能复用已终结的请求。
- **已知缺口**：参考实现的 `markFailed` 只改本地状态，不回滚订单快照；
  真实适配器必须以支付通道为准做补偿，不能假设本地快照自动一致。
- **指标**：建议把「提交到结算时延」和「结算失败率」作为宿主指标，
  而不是内核强制指标；内核只暴露状态。

## Alternatives considered

- **沿用 `PROCESSING` 作终点**：实现最省，但无法区分「已提交」与「已到账」，
  在真实店铺上会持续产生客诉。拒绝。
- **`refundCreate` 成功即 `SETTLED`**：话术好看，但属于虚报。拒绝。
- **只保留 `SUBMITTED`，不建模失败**：失败会沉在日志里，无法升级为工单。拒绝。

## Validation items（真实开发商店）

1. 测试模式下 Shopify 的退款交易是立即 `SUCCESS` 还是先 `PENDING`？
2. 一笔退款有多个 `OrderTransaction` 时，部分成功、部分失败如何表达？
   v0 采用「任一 `FAILURE/ERROR` 即 `FAILED`」，需在适配器 README 写清。
3. 退款到账后是否通知买家，属于宿主结果回流链路，不属于内核端口。
