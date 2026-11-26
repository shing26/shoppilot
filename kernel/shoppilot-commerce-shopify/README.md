# Shopify 退款适配器

> 状态：`0.1.0-SNAPSHOT`，属于独立 `kernel/` reactor。实现
> `com.shoppilot.commerce.CommerceGateway`，用同一份 TCK 证明与内核端口一致；
> 尚未对真实开发商店执行写操作。

## 跑法

```powershell
.\mvnw.cmd -f kernel/pom.xml -B -ntp verify
```

依赖 JDK 21（仓库自带 `mvnw.cmd` 已指向本机 JDK 21）。只跑本模块时必须走
aggregator 并带 `-am`，否则 contract 的 `test-jar` 还没生成：

```powershell
.\mvnw.cmd -f kernel/pom.xml -pl shoppilot-commerce-shopify -am -B -ntp verify
```

## 当前实现范围

| 端口方法 | 实现 |
| --- | --- |
| `getOrder` | 按订单号 `#1001` 或 GID 查订单；映射金额、行项目、已退数量、已退运费、资金/履约两轴 |
| `quoteRefund` | 根据行项目单价 × 数量 + 可选运费计算；只读 |
| `createRefund` | `refundCreate` + `@idempotent(key:)`；`refundLineItems`、`NO_RESTOCK`、可选 `shipping.fullRefund` |
| `getRefundStatus` | 读 `OrderTransaction(kind=REFUND)`：`SUCCESS → SETTLED`，`FAILURE/ERROR → FAILED`，其余 `SUBMITTED` |

固定使用 Admin GraphQL `2026-10`，端点：

```text
https://<shop>.myshopify.com/admin/api/2026-10/graphql.json
```

认证头为 `X-Shopify-Access-Token`。租户 id 直接作为 shop 域名使用。

## 测试分层

- `ShopifyCommerceGatewayContractTest`：继承合同模块的 13 条 TCK，另加 2 条资金安全用例
  （重复退运费、退款金额缺失），跑在 `FakeShopifyTransport` 上，不触网。
- `FakeShopifyTransport`：内存版的四个 GraphQL 操作解释器，只覆盖本适配器发出的查询形状；
  它证明的是**映射与错误语义**，不是 Shopify 的真实行为。
- `ShopifyLiveSmokeTest`：默认跳过；只有设置环境变量才执行，且**只读一张订单**，
  不创建退款。

### Live smoke

```powershell
$env:SHOPIFY_SHOP = 'your-dev-store.myshopify.com'
$env:SHOPIFY_TOKEN = 'shpat_...'
$env:SHOPIFY_ORDER_NAME = '#1001'   # 可选；不填则按 #1001 尝试
.\mvnw.cmd -f kernel/pom.xml -pl shoppilot-commerce-shopify -am -B -ntp verify `
  -Dtest=ShopifyLiveSmokeTest -Dsurefire.failIfNoSpecifiedTests=false
```

需要的 scope 至少包括读订单；创建退款另需写退款权限。真实写操作前必须先跑
`docs/shopify-refund-contract-mapping.md` 第 8 节的 10 条沙箱验证。

## 明确没做

- **退货/换货**：不创建 `Return`，不申请 `write_returns`，不处理退货面单与质检。
- **回库**：固定 `NO_RESTOCK`；`CANCEL` 等策略未实现。
- **税费/关税/订单调整**：金额只来自行项目单价 × 数量与运费，不单独建模税费。
- **商店积分、多币种**：只支持店铺结算币种；`Money` 不允许跨币种运算。
- **分页与限流预算**：单页查询；没有 `RateBudget`，429 只映射为 `THROTTLED`。
- **Webhook**：没有验签、去重、乱序处理与对账任务；结算推进靠 `getRefundStatus`。
- **持久化/outbox**：审批账本与幂等键持久化不在本模块；崩溃恢复语义未验证。

## 资金安全边界

1. `shippingRefunded` 由 `Order.totalRefundedShippingSet` 推导；只要该金额为正，
   后续含运费的报价返回 `STATE_NOT_ALLOWED`，不会重复退运费。
2. `totalRefundedSet` 或 `totalRefundedShippingSet` 缺失时，`getOrder` 与
   `createRefund` 都失败关闭为 `INTERNAL`，不会退化为 0。
3. `refundCreate` 的响应若没有交易节点，仍返回 `SUBMITTED`；这是保守值，
   但不能证明 Shopify 真的受理，需用开发商店验证。

## 已知缺口

1. 运费退款是「全退或全不退」；`totalRefundedShippingSet` 只要为正就视为已退完，
   部分退运费的场景会被保守拒绝。
2. `userErrors` 统一映射为 `INVALID_REQUEST`，没有细分到具体字段。
3. 401/403 映射为 `INTERNAL`，不是 `UNAUTHORIZED`；错误码枚举还没有认证类。
4. 多笔退款交易部分成功、部分失败时，任一 `FAILURE/ERROR` 即判 `FAILED`，
   没有部分成功模型。
5. 没有分页、限流预算、Webhook 验签/去重/对账与持久化审批账本。

## 相关文档

- 端口与 TCK：`kernel/shoppilot-commerce-contract/README.md`
- 逐项对照与七个冲突：`docs/shopify-refund-contract-mapping.md`
- 正式裁决：`docs/adr/0088-refund-approval-ledger.md`、`0089-refund-settlement-read-model.md`、
  `0090-buyer-term-and-store-scoped-customer.md`
