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
- Shopify：15 条测试（13 TCK + 2 条资金安全用例）+ 1 条 live smoke 默认跳过。
- 默认构建零网络、零凭据。
- **尚未对真实开发商店执行退款写操作**；AC8 的 10 条沙箱验证仍阻塞，
  需要 Shopify Partner 开发商店的 shop 域名与 Admin API token。
- **尚未接入 ShopPilot 网关**；本 reactor 是可独立复用的内核，不是现有 agent 的运行改动。

## 明确没做

- 持久化审批账本、CAS、outbox、崩溃恢复演练。
- Webhook 验签、去重、乱序处理与结算对账任务。
- 退货/换货、回库、税费/关税、商店积分、多币种。
- 分页、限流预算、Maven Central 发布与签名。
- 网关/工单/对话工具循环的接入。

## 路线

1. 完成 AC8 的真实开发商店验证，修掉只会在真实 API 上暴露的形状差异。
2. 补持久化 `RefundApprovalStore` 与 outbox，再谈生产可用。
3. 把「有界工具循环 + 降级工单 + 租户隔离」的 runtime 部分抽到同一 reactor，
   让退款只是第一个宿主适配器。

转正契约与验收项见 [`plans/232-commerce-kernel-promotion.md`](../plans/232-commerce-kernel-promotion.md)。
