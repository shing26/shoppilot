# Shopify 退款契约对照表（biz-mock → Shopify Admin API）

> 状态：**决策输入，不是可执行 ticket**。本文只回答一件事：把 `biz-mock` 的退款链路换成 Shopify 真实契约，哪些能直接对上、哪些必须改、哪些 v0 先不做。
>
> 取证口径：仓库代码按当前工作树；Shopify 侧按 **Admin API 2026-10**（2026-11-26 抓取官方文档）。尚未在开发商店实跑，文档没写死的行为在第 9 节列为沙箱验证项。

## 0. 一句话结论

能直接对上的只有三件事：**租户 = 店铺**、**退款写操作有幂等键**、**退款不得超过已付金额**。

其余都要改模型。最大冲突不是字段名，而是两边的退款本体不同：

- `biz-mock`：**金额驱动的两态退款单**（`PENDING_REVIEW → PROCESSING | REJECTED`），钱到没到不在模型里。
- Shopify：**行项目驱动的退款**（`refundLineItems` + `shipping` + `duties`）+ **Return 审批流**（`REQUESTED → OPEN → CLOSED / DECLINED`）+ **Transaction 终态**（`PENDING / SUCCESS / FAILURE / ERROR`）。

所以适配器不是“把 HTTP 地址从 `biz-mock` 换成 Shopify”，而是把 `ApplyRefundRequest(orderNo, amountFen, reason)` 重写成 `RefundIntent(order, lineItems, shipping, disposition)`，并把“受理 / 放行 / 到账”拆成三个可独立观测的状态。

## 1. 仓库侧事实

| 事实 | 落点 |
| --- | --- |
| 退款单状态 `PENDING_REVIEW / PROCESSING / REJECTED` | `shoppilot-biz-mock/src/main/java/com/shoppilot/bizmock/domain/Refund.java:83`、`:116` |
| 幂等唯一键 `(order_id, idempotency_token)` | `Refund.java:19` |
| 一单最多一笔在办退款：`active_order_id` 唯一索引 | `shoppilot-biz-mock/src/main/resources/db/migration/V9__refund_active_uniqueness.sql:12-18` |
| 受理：7 天窗口、`PAID/SHIPPED/DELIVERED` 可退、金额不得超过订单实付 | `BizMockService.java:57`、`:298-313` |
| 受理写入：退款单 `PENDING_REVIEW` + 订单 `REFUNDING` 同事务 | `BizMockService.java:322-329` |
| 并发双退：DB 唯一键兜底，冲突后按 token 重放或按在办退款拒绝 | `BizMockService.java:330-339` |
| 受理返回 `PENDING_APPROVAL`，不是 `OK` | `BizMockService.java:348`、`ToolStatus.java:19` |
| 放行：`PENDING_REVIEW → PROCESSING`，订单保持 `REFUNDING` | `BizMockService.java:400-406` |
| 驳回：`REJECTED` + 按时间戳推导订单回滚 | `BizMockService.java:408-414`、`:423-440` |
| 审核人必须是已验签账号，否则 403 | `RefundReviewController.java:63` |
| 买家读回三态 `PENDING_REVIEW / RELEASED / REJECTED` | `RefundReviewState.java:12-16` |
| 订单状态是一维枚举 `CREATED…REFUNDED` | `OrderStatus.java:1-19` |
| 退款工具参数是 `orderNo + reason + amountFen` | `ApplyRefundRequest.java:7-9` |

已知缺口（仓库自己也登记过）：`PROCESSING` 之后没有终态，订单永远停在 `REFUNDING`；到账不在模型里；退款工单跨进程失败只有计数，没有 outbox。

## 2. Shopify 侧事实（2026-10）

| 事实 | 依据 |
| --- | --- |
| `refundCreate` 的输入是 `orderId + refundLineItems + shipping + duties + transactions + note + notify + allowOverRefunding`，**没有“直接给一个金额”的字段** | [RefundInput](https://shopify.dev/docs/api/admin-graphql/latest/input-objects/RefundInput) |
| 行项目退款的必填是 `lineItemId + quantity`，可选 `restockType`（`CANCEL` / `RETURN` / `NO_RESTOCK`）与 `locationId` | [RefundLineItemInput](https://shopify.dev/docs/api/admin-graphql/latest/input-objects/RefundLineItemInput) |
| 运费可全额或按金额退 | [ShippingRefundInput](https://shopify.dev/docs/api/admin-graphql/latest/input-objects/ShippingRefundInput) |
| `refundCreate` 自 2026-04 起**必须**带 `@idempotent(key)`；键保留 **24 小时**，重复请求返回缓存响应（缓存响应可能因库内状态变化而与首次不同） | [refundCreate](https://shopify.dev/docs/api/admin-graphql/latest/mutations/refundCreate)、[Implementing idempotency](https://shopify.dev/docs/apps/build/apis/graphql-admin/implementing-idempotency) |
| `Refund` 对象**没有 status**；“钱是否真的退了”要看关联 `OrderTransaction.status` | [Refund](https://shopify.dev/docs/api/admin-graphql/latest/objects/Refund) |
| Transaction 状态：`PENDING / SUCCESS / FAILURE / ERROR / AWAITING_RESPONSE / UNKNOWN`；类型含 `REFUND` | [OrderTransactionStatus](https://shopify.dev/docs/api/admin-graphql/latest/enums/OrderTransactionStatus)、[OrderTransactionKind](https://shopify.dev/docs/api/admin-graphql/latest/enums/OrderTransactionKind) |
| 订单是两轴：`displayFinancialStatus`（`PAID / PARTIALLY_REFUNDED / REFUNDED / VOIDED…`）+ `displayFulfillmentStatus`（`UNFULFILLED / PARTIALLY_FULFILLED / FULFILLED…`） | [OrderDisplayFinancialStatus](https://shopify.dev/docs/api/admin-graphql/latest/enums/OrderDisplayFinancialStatus)、[OrderDisplayFulfillmentStatus](https://shopify.dev/docs/api/admin-graphql/latest/enums/OrderDisplayFulfillmentStatus) |
| Return 状态：`REQUESTED / OPEN / CLOSED / DECLINED / CANCELED`；`returnApproveRequest` 把 `REQUESTED → OPEN` | [ReturnStatus](https://shopify.dev/docs/api/admin-graphql/latest/enums/ReturnStatus)、[returnApproveRequest](https://shopify.dev/docs/api/admin-graphql/latest/mutations/returnApproveRequest) |
| `returnRequest` 建 `REQUESTED`（需商家批准）；`returnCreate` 直接建 `OPEN`（假定已批准），且要求订单至少有一个**已履约且未退款**的行项目 | [returnRequest](https://shopify.dev/docs/api/admin-graphql/latest/mutations/returnRequest)、[returnCreate](https://shopify.dev/docs/api/admin-graphql/latest/mutations/returnCreate) |
| `returnProcess` 可同时做处置（restock/dispose）与退款（`financialTransfer.issueRefund`），并可 `notifyCustomer` | [returnProcess](https://shopify.dev/docs/api/admin-graphql/latest/mutations/returnProcess) |
| `returnDeclineRequest` 把退货置 `DECLINED`，行项目可挂到新退货 | [returnDeclineRequest](https://shopify.dev/docs/api/admin-graphql/latest/mutations/returnDeclineRequest) |
| 订单取消是独立的 `orderCancel`，不可逆；部分退款应走 `refundCreate` 而不是取消 | [orderCancel](https://shopify.dev/docs/api/admin-graphql/latest/mutations/orderCancel) |
| 权限：`refundCreate` 需要 `orders` scope；退货需要 `write_returns` | 同上各页 |
| 限流：按 app + store 的 GraphQL cost 桶；Standard 恢复 **100 点/秒**，单查询上限 **1000 点** | [GraphQL rate limits](https://shopify.dev/docs/apps/build/apis/graphql-admin/rate-limits) |
| Webhook：HMAC 验签、`X-Shopify-Webhook-Id` 去重、**5 秒内返回 200**、失败重试 **8 次/4 小时**、不保证顺序；连续 8 次失败后 Admin API 配置的订阅会被自动删除 | [Verify deliveries](https://shopify.dev/docs/apps/build/webhooks/verify-deliveries)、[Delivery structure](https://shopify.dev/docs/apps/build/webhooks/delivery-structure) |
| 默认只能读最近 **60 天**订单；更早需要 `read_all_orders` | [Order](https://shopify.dev/docs/api/admin-graphql/latest/objects/Order) |
| Customer 是**店铺级**对象（“added to the store”“orders at the store”），不是跨店全局身份 | [Customer](https://shopify.dev/docs/api/admin-graphql/latest/objects/Customer) |

## 3. 状态映射

### 3.1 `biz-mock` 现状

```
Order:   CREATED -> PAID -> SHIPPED -> DELIVERED -> COMPLETED
                    \-> REFUNDING -> (没有 REFUNDED 终态)
Refund:  PENDING_REVIEW -> PROCESSING | REJECTED
                          ^             ^
                     APPROVE        REJECT + 按时间戳回滚 Order
```

### 3.2 Shopify

```
Order 资金轴: PENDING / AUTHORIZED / PARTIALLY_PAID / PAID
              -> PARTIALLY_REFUNDED -> REFUNDED
              -> VOIDED / EXPIRED
Order 履约轴: UNFULFILLED -> PARTIALLY_FULFILLED -> FULFILLED

Return:  REQUESTED -> OPEN -> CLOSED
             \-> DECLINED
             \-> CANCELED

Refund:  没有 status；金额是否到账看 OrderTransaction:
         PENDING / AWAITING_RESPONSE -> SUCCESS | FAILURE | ERROR
```

### 3.3 逐态映射

| `biz-mock` | Shopify 对应 | 判定 |
| --- | --- | --- |
| `Order.PAID` | `displayFinancialStatus=PAID` | 直连 |
| `Order.SHIPPED / DELIVERED / COMPLETED` | 履约轴 `PARTIALLY_FULFILLED / FULFILLED`；资金轴仍是 `PAID` | 改造：一维拆两轴 |
| `Order.REFUNDING` | 没有单一状态；= 本系统有在办退款 + Shopify 资金轴 `PAID / PARTIALLY_REFUNDED` | 改造：改成本地派生状态 |
| `Order.REFUNDED` | `displayFinancialStatus=REFUNDED` | 新增：当前仓库从未到达 |
| `Order.CANCELLED` | `orderCancel`（不可逆），不是退款 | 不做 v0 |
| `Refund.PENDING_REVIEW` | 没有原生退款态；`Return.REQUESTED`（有退货）或本地审批账本 | 改造，见 H2 |
| `Refund.PROCESSING` | `refundCreate` / `returnProcess` 已发出，Transaction `PENDING / AWAITING_RESPONSE` | 适配 |
| `Refund.REJECTED` | `Return.DECLINED`（有退货）或本地审批账本 | 适配 |
| 缺失的终态 | Transaction `SUCCESS` = 已退；`FAILURE / ERROR` = 需对账并升级 | 新增 |
| 买家看到的 `RELEASED` | 只能承诺“退款已发出”，不能承诺“已到账” | 措辞收紧 |

## 4. 逐项对照表

判定口径：**直连** = 语义等价；**适配** = 换字段/换调用；**改造** = 领域模型要动；**不做** = v0 明确不支持。

| # | 维度 | `biz-mock` | Shopify 2026-10 | 判定 | 适配器动作 |
| --- | --- | --- | --- | --- | --- |
| 1 | 租户 | `tenantId` | shop domain + 每店 access token | 直连 | `TenantId = shop`；token 按店取 |
| 2 | 买家 | `customerId` 全局唯一 | Customer 店铺级 | 改造 | 增加渠道身份 → 店铺 Customer 的映射；见 H6 |
| 3 | 订单标识 | 字符串 `10023` | GID + `name`（`#1001`） | 适配 | 先按 name 查 GID；查不到 → `NOT_FOUND` |
| 4 | 订单状态 | 一维 8 值 | 资金轴 + 履约轴 | 改造 | `OrderSnapshot(financial, fulfillment)`；见 H3 |
| 5 | 退款对象 | `Refund(orderId, amountFen, reason)` | `Refund(order, refundLineItems, shipping, duties, transactions)` | 改造 | `RefundIntent` 替代 `ApplyRefundRequest` |
| 6 | 退款金额 | 调用方直接给 `amountFen` | 由行项目数量 + 运费 + 税/关税推导 | 改造 | 先 `quoteRefund` 算可退额，再让模型确认；见 H1 |
| 7 | 金额类型 | `long` 分 | `Money` 十进制字符串 + 币种（shop / presentment 双币种） | 改造 | 引入 `Money`；禁止 `long fen` 跨边界 |
| 8 | 部分退款 | 一笔金额，最多到订单实付 | 按行项目数量，可多次部分退直到全额 | 改造 | 支持多笔；见 H5 |
| 9 | 退款原因 | 自由文本 `reason` | `note`（无枚举）；退货有 `declineReason` 等 | 适配 | `reason → note`；结构化原因留在本地 |
| 10 | 受理 | 落 `PENDING_REVIEW` 行 | 无原生“待审退款”；只有 `Return.REQUESTED` 或本地账本 | 改造 | v0 用本地审批账本；见 H2 |
| 11 | 放行 | `PENDING_REVIEW → PROCESSING` | `refundCreate` 或 `returnProcess(issueRefund)` | 适配 | 放行时才调 Shopify |
| 12 | 驳回 | `REJECTED` + 按时间戳回滚订单 | `returnDeclineRequest` 或本地账本；不改资金轴 | 适配 | 不再“回滚订单状态”；资金轴本来没动 |
| 13 | 终态 | 无 | Transaction `SUCCESS / FAILURE / ERROR`；资金轴 `REFUNDED` | 改造 | 新增对账；见 H4 |
| 14 | 幂等 | `(order_id, token)` 唯一 + 网关回放 | `@idempotent` 必填，24h 保留 | 直连 + 加固 | 本地仍保留唯一账本；键必须先落库再发请求 |
| 15 | 一单在办 | `active_order_id` 唯一，一单只能一笔 | 允许多笔部分退款 | 改造 | 放宽为“累计不超可退额”；见 H5 |
| 16 | 超额保护 | 金额 > 订单实付即拒 | `allowOverRefunding` 默认 false | 适配 | 保持 false；本地先 quote |
| 17 | 退款时限 | 硬编码 7 天 | 平台没有统一时限，是商户退货政策 | 改造 | 抽 `PolicyProvider`；见 H7 |
| 18 | 可退前置 | `PAID/SHIPPED/DELIVERED` | 有已捕获付款 + 存在可退行项目 | 改造 | `refundable = captured - refunded > 0 && 有可退项` |
| 19 | 退货/restock | 无 | `Return` + `restockType(CANCEL/RETURN/NO_RESTOCK)` | 不做 v0 | v0 固定 `NO_RESTOCK`；退货下轮 |
| 20 | 税费/关税 | 无 | `refundDuties`、`orderAdjustments` | 不做 v0 | v0 只退行项目 + 运费 |
| 21 | 运费 | 无 | `shipping.fullRefund` 或金额 | 做 v0（仅全额） | 支持 `fullRefund=true` |
| 22 | 通知买家 | web 不推送；出站走工单 | `refundCreate.notify` / `returnProcess.notifyCustomer` | 适配 | 默认 false，通知仍走自己的渠道 |
| 23 | 审计 | 本地 audit + `X-Actor` | `Refund.staffMember` 只读；API 调用归因到 app | 改造 | 放行人仍记本地审计；见 H2 |
| 24 | 权限 | JWT 角色 + 内部 token | OAuth scope：`orders` / `write_returns` | 适配 | scope 校验 + 安装/卸载事件 |
| 25 | 限流 | 网关限流 | GraphQL cost 桶，Standard 100 点/秒，单查询 1000 点 | 改造 | 新增 `RateBudget`；工具轮预算 ≠ API 调用预算 |
| 26 | 错误语义 | `ToolStatus` 枚举 | `userErrors`（HTTP 200）+ 4xx/5xx + `THROTTLED` | 适配 | 建映射表；`THROTTLED` 可重试，`userErrors` 不可重试 |
| 27 | 事件 | 无退款事件 | Webhook + 对账 | 改造 | 验签、按 `X-Shopify-Webhook-Id` 去重、5 秒内 200 |
| 28 | 一致性 | 同步读后写 | 退款创建后 Transaction 可能仍 `PENDING`；Webhook 乱序 | 改造 | 读模型允许“已发出未到账”；定时对账 |
| 29 | API 版本 | 无 | 季度版本，当前 2026-10 | 新增 | 固定版本 + 升级窗口 |
| 30 | 公共应用合规 | 无 | GDPR webhooks 等 | 不做 | v0 用自定义应用/开发商店 |

## 5. 七个必须裁决的冲突

### H1 金额驱动 vs 行项目驱动

`biz-mock` 允许模型直接给 `amountFen`，只校验不超过订单实付。Shopify 的 `RefundInput` 没有金额字段；金额由退哪些行项目、退几件、退多少运费/税推导。

**后果**：工具 schema 必须从“订单号 + 金额”变成“订单号 + 行项目选择 + 数量 + 是否退运费”。模型不能再凭空给一个金额，必须先把订单详情（行项目、单价、已退数量）喂给它，再让它选。

**建议**：`quoteRefund` 先算，模型只做“选哪些、退几件”；最终金额由服务端计算，模型不填钱。

### H2 没有 `PENDING_REVIEW`

Shopify 的 Refund 一创建就进入资金处理，没有“已受理、待人工放行”的原生状态。两条路：

| 方案 | 做法 | 优点 | 缺点 |
| --- | --- | --- | --- |
| A. 本地审批账本（v0 推荐） | `PENDING_REVIEW` 存本地；放行时才 `refundCreate` | 与现有内核闸门同构；未履约订单也能用 | Shopify 后台看不到待审；要自己保证账本不丢 |
| B. Return 审批流 | `returnRequest(REQUESTED)` → `returnApproveRequest(OPEN)` → `returnProcess(issueRefund)` | 商家在 Shopify 后台可见、可操作 | 要求已履约行项目；退款-only 场景不适用；多一个 `write_returns` scope |

**建议**：v0 选 A，保持“人工闸门”在内核里；等做退货/换货时再引入 B。

### H3 一维订单状态 vs 两轴

`OrderStatus` 把“钱”和“货”合成一个枚举，`refundable()` 和 `addressModifiable()` 都挂在它上面。Shopify 是资金轴与履约轴独立：一个订单可以 `PAID + PARTIALLY_FULFILLED`，也可以 `PARTIALLY_REFUNDED + FULFILLED`。

**后果**：`OrderStatus` 不能直接映射；否则“已发货但部分退款”这种真实状态无处安放。

**建议**：内核的 `OrderSnapshot` 用两个字段；`refundable` / `addressModifiable` 改成派生规则，不再看单一枚举。

### H4 放行 ≠ 到账

`biz-mock` 的 `PROCESSING` 是终点，话术靠“到账由支付渠道处理”兜住。Shopify 把这件事显式化成 `OrderTransaction.status`：`PENDING` 可能持续，`FAILURE/ERROR` 需要重试或升级。

**后果**：`RELEASED` 只能表示“退款已发出”；真正的终态要等 Transaction `SUCCESS`。这会新增一个“对账”职责，也会新增一种失败出口。

**建议**：内核增加 `RefundStatus` 读模型：`REQUESTED / APPROVED / SUBMITTED / SETTLED / FAILED`；`SETTLED` 只由 Transaction `SUCCESS` 驱动。

### H5 一单在办唯一 vs 多笔部分退款

`biz-mock` 用 `active_order_id` 唯一索引保证“一单最多一笔在办退款”。Shopify 允许多笔部分退款，直到累计全额。

**后果**：这条不变量会误伤合法业务。并发保护应改成“累计退款不超过已捕获金额”，并依赖 Shopify 的 `allowOverRefunding=false` 兜底。

**建议**：本地保留“同一幂等键只一笔”的唯一约束；删除“一单只能一笔”的强约束。

### H6 买家全局唯一 vs 店铺级

`CONTEXT.md` 把“买家”定义为跨店铺全局唯一。Shopify 的 Customer 是店铺级对象，同一自然人在两家店是两个 Customer。

**后果**：这是术语与领域模型的冲突，不是实现细节。按仓库纪律，不能自行改 `CONTEXT.md`。

**建议**：提 ADR 裁决。可选方案：① 把“买家”降为“渠道身份”，店铺级 Customer 另立术语；② 保留全局买家，但明确它只在本系统内唯一，与 Shopify Customer 是多对一映射。

### H7 7 天窗口是商户政策

`REFUND_WINDOW = Duration.ofDays(7)` 是 mock 规则。Shopify 没有平台级 7 天退款限制；是否可退由商户退货政策、商品类型、履约状态决定。

**后果**：硬编码窗口会在真实店铺上直接错判。

**建议**：抽 `PolicyProvider`，按租户/店铺配置；v0 用店铺配置的固定天数，后续接退货政策。

## 6. 对内核的影响

### 6.1 新增端口

```java
interface CommerceGateway {
    OrderSnapshot getOrder(TenantId tenant, OrderRef ref);
    RefundQuote quoteRefund(TenantId tenant, OrderRef ref, RefundIntent intent);
    RefundReceipt createRefund(TenantId tenant, RefundIntent intent, IdempotencyKey key);
    RefundStatus getRefundStatus(TenantId tenant, RefundRef ref);
}

interface ReturnGateway {
    ReturnReceipt approve(TenantId tenant, ReturnRef ref);
    ReturnReceipt decline(TenantId tenant, ReturnRef ref, DeclineReason reason);
    ReturnReceipt process(TenantId tenant, ReturnRef ref, RefundIntent intent);
}

interface PolicyProvider { RefundPolicy policyFor(TenantId tenant, OrderSnapshot order); }
interface RateBudget { Lease acquire(TenantId tenant, int cost); }
interface WebhookVerifier { VerifiedEvent verify(headers, rawBody); }
```

### 6.2 新增不变量

1. **键先落库再发请求**：Shopify 幂等键 24h 过期，崩溃恢复必须能重建同一个键。
2. **审批是状态迁移，不是重试**：`APPROVED` 只能发生一次；重复放行返回 `STATE_NOT_ALLOWED`。
3. **未 `SUCCESS` 不宣称到账**：`SETTLED` 只由 Transaction 终态驱动。
4. **Webhook 按投递 ID 去重**，并容忍乱序；缺失时靠对账补。
5. **工具轮预算 ≠ 商业 API 预算**：4 轮工具循环内可能打多次 GraphQL，限流要单独记账。
6. **一个退款意图一个幂等键**：不同意图复用键会把两笔退款合并。
7. **租户 = 店铺；买家身份是渠道级的**：不能把跨店同一个人当成同一个 Customer。

## 7. 适配器 v0 的最小切片

**做**：

- 已付款订单的全额/部分退款，按行项目数量；
- 运费全额退（`shipping.fullRefund`）；
- `NO_RESTOCK` 固定；
- 单币种（shop currency）；
- 本地审批账本（`PENDING_REVIEW → APPROVED → SUBMITTED → SETTLED/FAILED`）；
- `refundCreate @idempotent`；
- Webhook 验签 + 去重 + 对账；
- 类型化错误映射（`NOT_FOUND / STATE_NOT_ALLOWED / THROTTLED / UNAVAILABLE / TIMEOUT`）；
- 审计：谁在什么时候放行了哪一笔。

**不做**：

- 退货、换货、restock、退货面单；
- 关税/税费/订单调整；
- 商店积分、多币种；
- `orderCancel`；
- GDPR webhooks、App Store 发布、多 app 安装。

**演示脚本**：开发商店里一张两行项目订单 → 退一行 → 拒一笔 → 再退剩余 → 校验 `displayFinancialStatus` 与 Transaction → 同键重放 → 模拟 Webhook 重复/乱序。

## 8. 沙箱验证清单

文档没写死、必须用开发商店实测的 10 件事：

1. `refundCreate` 只给 `transactions.amount`、不给 `refundLineItems` 是否被拒（预期被拒）。
2. 同 `@idempotent` 键重放：返回同一 refund 还是缓存响应？不同参数同键报什么错？
3. 测试模式下 Transaction 是立即 `SUCCESS` 还是先 `PENDING`？持续多久？
4. 未履约但已付款订单：`restockType=CANCEL` 与 `NO_RESTOCK` 的差异。
5. `returnCreate` 是否真的要求已履约行项目；退款-only 能否绕过。
6. 多次部分退款直到 `REFUNDED` 的 `displayFinancialStatus` 迁移。
7. 退款相关 Webhook topic 的实际集合（`refunds/create`、`orders/updated`、`returns/*`）、负载形状、乱序行为。
8. `refundCreate` 的实际 cost、限流响应头、`THROTTLED` 错误形状。
9. 60 天边界：无 `read_all_orders` 时查旧订单的报错。
10. API 创建的退款在 `Refund.staffMember` 上如何归因（预期为空或 app，而不是放行人）。

### 8.1 实测结果（2026-11-27，HEAD `40a5357`）

环境：Shopify Partner 免费开发商店，Admin GraphQL `2026-10`；App scopes
`read_orders, write_orders, read_order_edits, write_order_edits, read_all_orders`。

**关键发现（已修）**：`refundCreate` 不传 `transactions` 时，Shopify 会创建退款单并
消耗 `refundableQuantity`，但 `totalRefundedSet = 0`、`transactions = []`，订单仍是
`PAID`。手工网关与已捕获 Bogus 网关订单都复现。适配器现在读取订单的
`CAPTURE`（优先）或 `SALE` 交易，显式提交
`transactions: [{ orderId, kind: REFUND, gateway, amount, parentId }]`；没有足够金额的
成功支付交易时返回 `STATE_NOT_ALLOWED`。

| # | 项目 | 实测结论 |
| --- | --- | --- |
| 1 | `transactions.amount` / `refundLineItems` | 成功响应会回显 `refundLineItems`；超量退款被 userError 拒绝，`field=refundLineItems.0.quantity` |
| 2 | 同键重放 | 同键同参返回同一 refund id；同键改 note 返回 `Idempotency key has been used with a different request payload.` |
| 3 | 测试模式交易时序 | 显式 `REFUND` 交易立即 `SUCCESS`，未观测到 `PENDING`；订单同步进入 `PARTIALLY_REFUNDED` / `REFUNDED` |
| 4 | 未履约订单的 restock | `NO_RESTOCK` 可接受；`CANCEL` 无 `locationId` 时返回 `You need to set a location to restock items` |
| 5 | `returnCreate` | 未执行：App 无 `read_returns` / `write_returns` scope |
| 6 | 多次部分退款 | `#1008` 两笔 10 USD：`PARTIALLY_REFUNDED` → `REFUNDED`；总额 20、可退 0 |
| 7 | 退款 Webhook | 未执行：App 无 `read_webhooks` / `write_webhooks` scope |
| 8 | cost / 限流 | `refundCreate` 实测 cost 20–30；响应头含版本、operation name/signature、engine、request id；`maximumAvailable=2000`、`restoreRate=100/s`。真实 `THROTTLED` 未触发（店铺数据量小），HTTP 429 与 GraphQL `THROTTLED` 映射由单测覆盖 |
| 9 | 60 天边界 | 未触发：App 已有 `read_all_orders`，且测试店铺没有更早订单 |
| 10 | `staffMember` 归因 | 不可读：免费开发商店查询返回 `ACCESS_DENIED`，需要 `read_users` 且为 finance embedded / Plus / Advanced；适配器不依赖该字段 |

补充实测：`#1007` 显式交易退款 24.95 USD → `REFUND/SUCCESS`、订单 `REFUNDED`；
`#1010` 适配器 live 首退/重放/同键冲突/末次退款全部 SETTLED；
`#1012` 行项目 10 + 运费 5 → 总额 15、`refundShippingLines` 5、订单 `REFUNDED`；
`#1013` 手工 `SALE` + 显式 `REFUND` 也能记账到 `REFUNDED`（无真实资金）。

## 9. 工作量

只算适配器 v0，不含内核抽取：

| 工作 | 估时 |
| --- | --- |
| `Money` / `OrderSnapshot` / `RefundIntent` 模型 + 契约测试 | 1 天 |
| Shopify GraphQL 客户端、认证、分页、限流 | 2 天 |
| `quoteRefund` 与行项目映射 | 2 天 |
| 本地审批账本 + 幂等 + 审计 | 2 天 |
| Webhook 验签/去重/对账 | 2 天 |
| 错误映射 + 演示脚本 + 文档 | 2 天 |
| **合计** | **约 11 人日（含缓冲 2 周）** |

## 10. 仓库处置建议

- **不动 `biz-mock`**：它是演示与 JVM 判据的宿主，先保留。
- 新代码放独立模块（例如 `shoppilot-commerce-shopify`），通过第 6 节的端口接回内核；用绞杀者模式，不原地重构 `AgentStateMachine`。
- 端口与 TCK 已转正到独立 reactor `kernel/shoppilot-commerce-contract/`：不进入根 reactor，当前 30 条测试全绿，TCK 以 `test-jar` 发布。
- Shopify 2026-10 适配器已转正到 `kernel/shoppilot-commerce-shopify/`：继承同一份 TCK，
  假传输层 13 条 TCK + 8 条适配器/资金安全/限流映射绿，2 条 live 用例默认跳过。
- **AC8 已于 2026-11-27 在免费开发商店执行退款写操作**：发现 `refundCreate` 不传
  `transactions` 会“消耗可退数量但不退钱”，已改为显式提交 `REFUND` 交易并失败关闭；
  实测覆盖部分退款到 `REFUNDED`、同键重放、同键冲突、运费退款、手工网关记账。
  完整证据见 `kernel/README.md` 的 AC8 章节与 `docs/EVIDENCE.md`。
- 三个待裁决项已于 2026-11-26 由所有者接受并升为正式 ADR：`docs/adr/0088-refund-approval-ledger.md`、`0089-refund-settlement-read-model.md`、`0090-buyer-term-and-store-scoped-customer.md`。H6 明确选 A：保留平台全局买家，Shopify Customer 只作租户内的渠道顾客档案；`CONTEXT.md` 原文不改。
- 模块转正契约已冻结：`plans/232-commerce-kernel-promotion.md`。目标是独立 `kernel/` reactor，不并入根 `pom.xml`，因此根 878 条读数与 `docs/STATUS.md` 不动。
- 统一跑法：`.\mvnw.cmd -f kernel/pom.xml -B -ntp verify`。
- 本文只是对照表，不新增 ticket、不改判据、不改任何验收数字。

## 11. 来源

- Shopify Admin API 2026-10：<https://shopify.dev/docs/api/admin-graphql/latest>
- `refundCreate`：<https://shopify.dev/docs/api/admin-graphql/latest/mutations/refundCreate>
- `RefundInput`：<https://shopify.dev/docs/api/admin-graphql/latest/input-objects/RefundInput>
- `Refund`：<https://shopify.dev/docs/api/admin-graphql/latest/objects/Refund>
- `ReturnStatus`：<https://shopify.dev/docs/api/admin-graphql/latest/enums/ReturnStatus>
- `returnRequest`：<https://shopify.dev/docs/api/admin-graphql/latest/mutations/returnRequest>
- `returnCreate` / `returnApproveRequest` / `returnDeclineRequest` / `returnProcess`
- 幂等：<https://shopify.dev/docs/apps/build/apis/graphql-admin/implementing-idempotency>
- 限流：<https://shopify.dev/docs/apps/build/apis/graphql-admin/rate-limits>
- Webhook：<https://shopify.dev/docs/apps/build/webhooks/verify-deliveries>、<https://shopify.dev/docs/apps/build/webhooks/delivery-structure>

