# ADR 0088：退款审批载体——本地账本，Shopify 只在人工放行后提交

Status: Accepted（2026-11-26 所有者确认「接受 A，按提案默认值继续」）
Date: 2026-11-26
Deciders: 所有者
Round: 62（契约：`plans/232-commerce-kernel-promotion.md`）
Related: [ADR 0047](0047-refund-review-is-a-state-transition-gate.md)、
[ADR 0063](0063-b3-money-action-non-repudiation.md)、
`docs/shopify-refund-contract-mapping.md` §5 H2

## Context

现有 `biz-mock` 的退款闸门是「受理即冻结」：`POST /refunds` 落 `PENDING_REVIEW`，
同一事务把订单置 `REFUNDING`；人工放行后才进入资金动作。这个形状让「钱在放行前没动」
可以在数据上被证明。

Shopify Admin GraphQL 的 `refundCreate` 没有「待人工放行」这个原生状态：调用即进入资金处理。
如果内核把「受理」直接映射成「调外部退款」，人工闸门就只剩上游流程的自觉，
无法在数据上证明审批发生在资金动作之前。

两条可选路径：

| 方案 | 做法 | 代价 |
| --- | --- | --- |
| A. 本地审批账本 | 本地先落 `REQUESTED`；放行才 `refundCreate` | Shopify 后台看不到待审；账本必须自己持久化 |
| B. Shopify Return 审批流 | `returnRequest → returnApproveRequest → returnProcess` | 要求已履约行项目；多一个 `write_returns` scope；退款-only 场景不适用 |

## Decision

### 1. 内核持有审批账本，商业系统在放行前只被读

`RefundWorkflow.request` 的顺序固定为：

1. 幂等键查重（同 key 同 intent 重放，同 key 不同 intent 拒绝）；
2. `getOrder` 读订单；
3. 政策与可退性校验；
4. `quoteRefund` 只读报价；
5. 落本地 `REQUESTED`。

第 5 步之前不得调用 `createRefund`。`APPROVED` 只能由人工放行动作产生；
重复放行不得再发一次资金请求，只能重放同一结果或返回 `STATE_NOT_ALLOWED`。

### 2. 幂等键在受理时生成并持久化，先于任何外部调用

Shopify 的 `@idempotent` 只在有限窗口内有效，进程崩溃后必须能用同一个 key 重建请求。
本地账本至少持久化：`tenant`、`requestId`、`intent`（含行项目与数量）、`key`、
当前状态、`commerceRefundId`、失败原因、创建/更新时间。

正式实现需要 CAS 或版本号。草案里的 `InMemoryRefundApprovalStore` 是
last-write-wins，只用于证明语义，不得直接上生产。

### 3. 审批人身份不进入内核端口

`CommerceGateway` 不携带 `actor`。审批人由宿主（网关或坐席工作台）在调用
`approve` 前完成已验签身份校验，并把「谁放行了哪一笔」写入宿主审计流。
适配器不得用 `X-Actor` 这类自报头替代已验签账号（ADR 0063）。

### 4. Return 审批流推迟到退货功能

v0 不申请 `write_returns`，不创建 `Return`。当产品要做退货/换货时，再以新 ADR
引入 `ReturnGateway`，并重新评估「本地账本 + Return 审批」是否会双写。

### 5. 内核状态名与宿主术语的映射

`CONTEXT.md` 目前把「退款审核」定义为 `PENDING_REVIEW → PROCESSING | REJECTED`
的状态迁移门，把「待审核」定义为订单已冻结、资金未动的受理态。内核新增了
`APPROVED`（已放行、未提交）与 `SUBMITTED / SETTLED / FAILED`（外部回执与对账），
这些态在现有术语表里没有位置。

**本 ADR 不修改 `CONTEXT.md`。** 在宿主对外文案仍使用现有术语时，按以下映射：

| 现有术语 | 内核状态 | 说明 |
| --- | --- | --- |
| 待审核 `PENDING_REVIEW` | `REQUESTED` | 已受理、资金未动 |
| （无） | `APPROVED` | 人工已放行、外部请求尚未成功 |
| 处理中 `PROCESSING` | `SUBMITTED` | 外部已受理，未确认到账 |
| （无） | `SETTLED` / `FAILED` | 对账终态 |
| 已驳回 `REJECTED` | `REJECTED` | 终态 |

若未来要把这套状态写进领域术语表，必须另立 ADR 并由所有者授权改
`CONTEXT.md`；实现者不得顺手改。

## Consequences

- **正面**：人工闸门在数据上可证明；Shopify 调用次数减少；未履约订单也能走同一闸门；
  与现有 `PENDING_REVIEW` 的审计习惯同构。
- **负面**：待审列表只在本地，商家在 Shopify 后台看不见；账本丢失等于待审丢失；
  需要持久化、CAS、备份与保留策略。
- **运维**：需要「本地 `REQUESTED` 长时间未放行」与
  「本地 `APPROVED` 但 `createRefund` 连续失败」两条告警。
- **安全**：审批人与请求人是否必须分离，由宿主策略决定；内核只保证状态迁移合法。

## Alternatives considered

- **B. 全部走 Shopify Return 审批**：后台可见、可操作，但 v0 的退款-only 场景会被
  已履约条件卡住，且需要额外 scope。拒绝，保留为退货功能的候选。
- **不做本地闸门，受理即退款**：实现最省，但违背 ADR 0063 的资金动作责任要求，
  也无法在真实店铺上复用现有审核流程。拒绝。

## Implementation follow-ups

1. 正式 `RefundApprovalStore` 的持久化载体（本地表或 Redis）与 CAS 方案。
2. outbox 或本地事务表：外部调用成功、本地落库失败时的补偿。
3. 待审保留期与账本备份/恢复演练。
4. 审批人与请求人分离策略由宿主实现，内核不强制。
