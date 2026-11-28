# ShopPilot Commerce Kernel

> 版本：`0.1.0-SNAPSHOT`。**独立 Maven reactor，不属于根仓的四个模块**，
> 也不依赖 Spring、网关、`biz-mock` 或工单服务。
>
> 目标：把「在真实电商系统上安全执行退款」做成可复用的端口、不变量、TCK 与适配器，
> 而不是把 ShopPilot 的演示链路原样搬出去。

## 模块

| 模块 | 内容 |
| --- | --- |
| `shoppilot-commerce-contract` | `Money`、`OrderSnapshot`、`RefundIntent`、`CommerceGateway`、本地审批 `RefundWorkflow`、`RefundQuoter`、TCK |
| `shoppilot-commerce-shopify` | Shopify Admin GraphQL `2026-10` 适配器，实现 `CommerceGateway` 并通过同一份 TCK |

## 构建

```powershell
.\mvnw.cmd -f kernel/pom.xml -B -ntp verify
python kernel/scripts/check_coverage.py
```

只跑一个模块时必须走 aggregator（TCK 是 contract 的 `test-jar`）：

```powershell
.\mvnw.cmd -f kernel/pom.xml -pl shoppilot-commerce-shopify -am -B -ntp verify
```

CI：`.github/workflows/kernel-ci.yml`，只在 `kernel/**` 变更时触发；
不需要 secrets、Docker 或模型。

## 冻结的不变量

1. 金额只走 `Money(minorUnits, ISO-4217)`；端口上没有 `double` 或裸 `long`。
2. 订单是资金轴与履约轴两条，不压成一个 `OrderStatus`。
3. `RefundIntent` 没有金额字段；金额由服务端从订单快照推导。
4. 审批与提交分离：`request` 只落本地 `REQUESTED`，`approve` 才碰外部系统。
5. 幂等键先于外部调用；同 key 同 intent 重放，同 key 不同 intent 拒绝。
6. `SUBMITTED` 只表示已提交；`SETTLED` 只由支付通道 `SUCCESS` 驱动。
7. 租户隔离：跨租户订单/退款一律 `NOT_FOUND`，不确认存在。

完整裁决见：

- [ADR 0088](../docs/adr/0088-refund-approval-ledger.md)：本地审批账本
- [ADR 0089](../docs/adr/0089-refund-settlement-read-model.md)：六态终态读模型
- [ADR 0090](../docs/adr/0090-buyer-term-and-store-scoped-customer.md)：全局买家与店铺级 Customer

## TCK 怎么复用

适配器实现 `CommerceGateway` 后，以 `test` scope 依赖 contract 的 `test-jar`，
继承 `CommerceGatewayContractTest` 并注入自己的 fixture：

```xml
<dependency>
  <groupId>com.shoppilot</groupId>
  <artifactId>shoppilot-commerce-contract</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <type>test-jar</type>
  <scope>test</scope>
</dependency>
```

运行时 jar 不含 JUnit、AssertJ 或 TCK 类；这一点由 kernel CI 与
`jar tf` 证据共同守住。

## 当前状态

- contract：30 条测试绿（13 TCK + 13 工作流 + 4 状态机）。
- Shopify：26 条测试（24 条执行 + 2 条 live 默认跳过；执行部分含 13 条 TCK
  与 11 条适配器/资金安全/幂等/限流映射）。
- 默认构建零网络、零凭据。
- **AC8 已在 Shopify Partner 免费开发商店执行真实退款写操作**（2026-11-27，
  Admin GraphQL `2026-10`）；实测暴露并修掉一个真实资金缺口，见下节。
- **尚未接入 ShopPilot 网关**；本 reactor 是可独立复用的内核，不是现有 agent 的运行改动。

## AC8 真实开发商店实测（2026-11-27）

环境：免费开发商店，App scopes 为
`read_orders, write_orders, read_order_edits, write_order_edits, read_all_orders`。

### 关键发现（已修）

`2026-10` 的 `refundCreate` **不传 `transactions` 时不会真正退款**：Shopify 会创建
退款单、消耗行项目 `refundableQuantity`，但返回
`totalRefundedSet = 0`、`transactions = []`，订单仍是 `PAID`。手工网关和已捕获的
Bogus 网关订单都复现。适配器现在会先读取订单的 `CAPTURE`（优先）或 `SALE` 交易，
再显式提交：

```text
transactions: [{ orderId, kind: REFUND, gateway, amount, parentId }]
```

没有足够金额的成功支付交易时返回 `STATE_NOT_ALLOWED`，不再创建零金额退款。
回执金额取报价金额；`totalRefundedSet` 仍必须存在，非零但与报价不一致时
返回 `INTERNAL`。

### 实测证据

| 场景 | 订单 | 结果 |
| --- | --- | --- |
| 不传 `transactions` | `#1001` / `#1002` / `#1006` | 退款单已建，金额 0、无交易，订单仍 PAID，可退数量被消耗 |
| 显式 `REFUND` + 父 CAPTURE | `#1007` | 24.95 USD，`REFUND/SUCCESS`，订单 `REFUNDED` |
| 两次部分退款 | `#1008` | 10 + 10，`PARTIALLY_REFUNDED` → `REFUNDED` |
| 同键同参重放 | `#1008` / `#1010` | 返回同一 refund id |
| 同键改 note | `#1008` / `#1010` | userError：idempotency key used with a different request payload |
| 适配器 live 全链路 | `#1010` | 首退、重放、同键冲突、末次退款均 SETTLED，订单 REFUNDED |
| 行项目 + 运费 | `#1012` | 总额 15（其中运费 5），`REFUND/SUCCESS`，订单 REFUNDED |
| 手工网关 + 显式交易 | `#1013` | 10 USD，`REFUND/SUCCESS`，订单 REFUNDED（仅记账，无真实资金） |
| 退空后同键重放 | `#1015` | 首退/末退各 10 USD `SETTLED`，订单 REFUNDED；退空后同键同参仍返回首笔 refund id |
| API 建 SALE 缺网关 reference | `#1003` | `refundCreate` 返回 userError `Reference can't be blank`，订单仍 PAID、可退数量 2 未消耗；根因待查 |

现场响应头包含 `x-shopify-api-version: 2026-10`、operation name/signature、
`x-shopify-api-gql-engine: cardinal`、`X-Request-ID`；退款 mutation 实测
cost 约 20–30，`maximumAvailable=2000`、`restoreRate=100/s`。

### 幂等重放（`e7004e6`）

`createRefund` 在报价之前先查进程内重放缓存：键为 `tenant + "|" + key`，
指纹为 `order + 排序后的 lineItemId×quantity + refundShipping + reason`。
同键同指纹返回原 `RefundReceipt`，同键不同指纹返回 `INVALID_REQUEST`。
缓存是 access-order `LinkedHashMap`，上限 10,000 条，**只在进程内**；
跨进程/重启的持久幂等仍依赖后续 `RefundApprovalStore` / outbox。

### 真实限流形状（2026-11-27）

- GraphQL：6 个 `orders(first: 250)` 别名并发，单查询 `requestedQueryCost=936`；
  6 个请求中 2 个返回 HTTP 200 + `errors[0].extensions.code=THROTTLED`、
  `message=Throttled`、`currentlyAvailable=128`。
- 8 个别名（1248）先被 `MAX_COST_EXCEEDED` 拒绝，确认单查询上限仍为 1000。
- Admin REST：`orders.json?limit=1` 80 并发，32 个 HTTP 429、
  `Retry-After: 2`、body 为
  `{"errors":"Exceeded 2 calls per second for api client..."}`。

### 仍然没验证或没做

- `Refund.staffMember` 在免费开发商店不可读：需要 `read_users`，且 App 必须是
  finance embedded 或安装在 Plus/Advanced 店铺；适配器不依赖该字段。
- Webhook、`returnCreate`、退货/换货未验证：当前 App 没有
  `read_webhooks` / `write_webhooks` / `read_returns` / `write_returns` scope。
- `restockType=CANCEL` 需要 `locationId`，当前 App 无 location 权限；适配器固定
  使用 `NO_RESTOCK`，不承诺回库。
- 多笔 CAPTURE 的跨交易拆分未实现：适配器选单笔金额足够的最近 CAPTURE/SALE，
  不足即失败关闭。
- 60 天边界未触发：App 已有 `read_all_orders`，且测试店铺没有更早订单。
- 真实 HTTP 429 与 GraphQL `THROTTLED` 已按上面的并发探测触发；适配器仍只做
  映射，不做主动退避或 `RateBudget`。

## 明确没做

- 持久化审批账本、CAS、outbox、崩溃恢复演练。
- Webhook 验签、去重、乱序处理与结算对账任务。
- 退货/换货、回库、税费/关税、商店积分、多币种。
- 分页、限流预算、Maven Central 发布与签名。
- 网关/工单/对话工具循环的接入。

## 路线

1. AC8 写链路已于 2026-11-27 实测，**待独立 Verifier 复验与所有者签收**；
   继续补真实网关对账与 Webhook/退货链路，再谈生产可用。
2. 补持久化 `RefundApprovalStore` 与 outbox。
3. 把「有界工具循环 + 降级工单 + 租户隔离」的 runtime 部分抽到同一 reactor，
   让退款只是第一个宿主适配器。

转正契约与验收项见 [`plans/232-commerce-kernel-promotion.md`](../plans/232-commerce-kernel-promotion.md)。
