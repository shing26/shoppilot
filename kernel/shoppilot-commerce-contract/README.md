# ShopPilot Commerce Contract（内核合同模块）

> 状态：`0.1.0-SNAPSHOT`，属于独立 `kernel/` reactor，**不进入根 `pom.xml`**。
> 端口与不变量由 ADR 0088/0089/0090 裁决；本模块只提供代码形状与 TCK。
>
> 约束：本模块不参与 `.\mvnw.cmd verify`，因此不会改变根仓 878 条 JVM 测试、
> `docs/STATUS.md` 与收口审计常数。

## 跑法

```powershell
.\mvnw.cmd -f kernel/pom.xml -B -ntp verify
```

要求 JDK 21（仓库自带的 `mvnw.cmd` 已指向本机 JDK 21）。

## 这个草案冻结了什么

1. **金额只走 `Money(minorUnits, ISO-4217)`**，不在端口上出现 `long fen` 或 `double`。
2. **订单是两轴**：`FinancialStatus` 与 `FulfillmentStatus` 分开，不再用一个 `OrderStatus` 同时表达钱和货。
3. **`RefundIntent` 没有金额字段**：只表达“退哪些行项目、各几件、是否退运费”。金额由 `quoteRefund`（端口）与 `RefundQuoter`（纯函数参考实现）算。
4. **`CommerceGateway` 只有四个方法**：读订单、报价、提交退款、读退款状态。
5. **退款状态机是单向的**：`REQUESTED → APPROVED → SUBMITTED → SETTLED | FAILED`，`REQUESTED → REJECTED`；终态不可再迁移。
6. **审批与提交分离**：`RefundWorkflow.request` 只落本地 `REQUESTED`，不碰外部系统；`approve` 才调 `createRefund`。
7. **幂等键先于外部调用**：同一 key + 同一 intent 必须重放同一 receipt；同一 key + 不同 intent 必须拒绝。
8. **未 `SETTLED` 不宣称到账**：`SUBMITTED` 只表示已提交，终态由 `getRefundStatus` 或 webhook 对账推进。
9. **订单同时保留业务单号与商业系统 id**：`OrderRef` 是对外单号（如 `#1001`），
   `OrderSnapshot.commerceOrderId` 是 Shopify GID 这类外部主键；适配器不得假设二者相同。

## 目录

```
src/main/java/com/shoppilot/commerce/       领域类型 + 端口 + RefundWorkflow + RefundQuoter
src/test/java/com/shoppilot/commerce/tck/   CommerceGateway 契约测试（TCK）
src/test/java/com/shoppilot/commerce/fake/  参考内存实现 + 能跑通 TCK 的示例
```

TCK 通过 `maven-jar-plugin` 打成 `test-jar`（只含 `com/shoppilot/commerce/tck/**`），
适配器以 `test` scope 依赖。运行时 jar 不含 JUnit、AssertJ 或 TCK。

## 适配器怎么用这套 TCK

Shopify（或其他电商）适配器实现 `CommerceGateway` 后，继承 `CommerceGatewayContractTest`，提供自己的 fixture：

```java
class ShopifyCommerceGatewayContractTest extends CommerceGatewayContractTest {
    @Override protected CommerceGateway gateway() { return new ShopifyCommerceGateway(...); }
    @Override protected TenantId tenant() { return new TenantId("your-dev-store.myshopify.com"); }
    @Override protected TenantId otherTenant() { return new TenantId("another-store.myshopify.com"); }
    @Override protected OrderRef paidOrder() { return new OrderRef("#1001"); }
    @Override protected String lineItemId() { return "gid://shopify/LineItem/..."; }
    @Override protected Money lineItemUnitPrice() { return Money.of(5000, "CNY"); }
    @Override protected Money shipping() { return Money.of(1000, "CNY"); }
}
```

TCK 只使用 `CommerceGateway` 的公开方法，不依赖参考实现，也不依赖 Spring。
依赖声明见 `kernel/README.md` 的 `test-jar` 示例。

## 明确没做的

- 合同模块本身没有 Shopify HTTP 客户端、OAuth、GraphQL、Webhook 验签；
  这些在兄弟模块 `kernel/shoppilot-commerce-shopify/` 里，不属于内核端口。
- 没有数据库/Redis 持久化；`RefundApprovalStore` 的内存实现只用于测试。
- 没有 outbox：`approve` 在外部调用成功、本地状态落库失败时，依赖幂等键重试；真正的生产实现需要 outbox 或本地事务表。
- 没有结算失败回滚：参考实现 `markFailed` 只改状态，不回滚订单快照；真实适配器必须按支付通道对账。
- 没有并发控制：`RefundApprovalStore` 的 `update` 是 last-write-wins；正式实现需要版本号或 CAS。
- 没有多币种换算：`Money` 只允许同币种运算。

## 下一步

1. 决定 `RefundApprovalStore` 的正式存储（本地表还是 Redis）与 outbox 方案。
2. 用 Shopify 开发商店跑 `docs/shopify-refund-contract-mapping.md` 第 8 节的 10 条沙箱验证。
3. 三份 ADR 已接受：0088（审批载体）、0089（终态读模型）、0090（买家术语）。
4. 本模块已转正为独立 `kernel/` reactor，并纳入内核覆盖率门槛与独立 CI；
   仍未并入根 reactor，也尚未接网关。
