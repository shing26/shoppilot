# Shopify 退款适配器

> 状态：`0.1.0-SNAPSHOT`，属于独立 `kernel/` reactor。实现
> `com.shoppilot.commerce.CommerceGateway`，用同一份 TCK 证明与内核端口一致；
> AC8 已于 2026-11-27 在免费开发商店执行真实退款写操作（见 `kernel/README.md`
> 与 `docs/EVIDENCE.md`），**尚待独立 Verifier 复验**。

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
| `createRefund` | `refundCreate` + `@idempotent(key:)`；`refundLineItems`、`NO_RESTOCK`、可选 `shipping.fullRefund`；提交前先查进程内重放缓存 |
| `getRefundStatus` | 读 `OrderTransaction(kind=REFUND)`：`SUCCESS → SETTLED`，`FAILURE/ERROR → FAILED`，其余 `SUBMITTED` |

固定使用 Admin GraphQL `2026-10`，端点：

```text
https://<shop>.myshopify.com/admin/api/2026-10/graphql.json
```

认证头为 `X-Shopify-Access-Token`。租户 id 直接作为 shop 域名使用。

## 测试分层

- `ShopifyCommerceGatewayContractTest`：继承合同模块的 13 条 TCK，另加 11 条
  资金安全/幂等/限流用例（重复退运费、退款金额缺失、零金额真实形状、金额不一致、
  CAPTURE 关联、无 CAPTURE、HTTP 429、GraphQL `THROTTLED`、退空后重放、改参拒绝、
  跨租户隔离），共 24 条，跑在 `FakeShopifyTransport` 上，不触网。
- `FakeShopifyTransport`：内存版的四个 GraphQL 操作解释器，只覆盖本适配器发出的查询形状；
  它证明的是**映射与错误语义**，不是 Shopify 的真实行为。
- `ShopifyLiveSmokeTest`：默认跳过；只有设置环境变量才执行，且**只读一张订单**，
  不创建退款。
- `ShopifyLiveRefundTest`：默认跳过；只有 `SHOPIFY_LIVE_WRITE=1` 才执行真实写操作，
  覆盖首退、同键重放、同键改参、末次退款与退空后重放。

### Live smoke

```powershell
$env:SHOPIFY_SHOP = 'your-dev-store.myshopify.com'
$env:SHOPIFY_TOKEN = 'shpat_...'
$env:SHOPIFY_ORDER_NAME = '#1001'   # 可选；不填则按 #1001 尝试
.\mvnw.cmd -f kernel/pom.xml -pl shoppilot-commerce-shopify -am -B -ntp verify `
  -Dtest=ShopifyLiveSmokeTest -Dsurefire.failIfNoSpecifiedTests=false
```

需要的 scope 至少包括读订单；创建退款另需写退款权限。写链路、真实限流与逐项
实测结论见 `docs/shopify-refund-contract-mapping.md` §8.1。

## 明确没做

- **退货/换货**：不创建 `Return`，不申请 `write_returns`，不处理退货面单与质检。
- **回库**：固定 `NO_RESTOCK`；`CANCEL` 等策略未实现。
- **税费/关税/订单调整**：金额只来自行项目单价 × 数量与运费，不单独建模税费。
- **商店积分、多币种**：只支持店铺结算币种；`Money` 不允许跨币种运算。
- **分页与限流预算**：单页查询；没有 `RateBudget`，429 只映射为 `THROTTLED`。
  真实 HTTP 429 与 GraphQL `THROTTLED` 形状已于 2026-11-27 实测，但不做主动退避。
- **Webhook**：没有验签、去重、乱序处理与对账任务；结算推进靠 `getRefundStatus`。
- **持久化/outbox**：审批账本与幂等键持久化不在本模块；崩溃恢复语义未验证。

## 资金安全边界

1. `shippingRefunded` 由 `Order.totalRefundedShippingSet` 推导；只要该金额为正，
   后续含运费的报价返回 `STATE_NOT_ALLOWED`，不会重复退运费。
2. `totalRefundedSet` 或 `totalRefundedShippingSet` 缺失时，`getOrder` 与
   `createRefund` 都失败关闭为 `INTERNAL`，不会退化为 0。
3. `refundCreate` 的响应若没有交易节点，仍返回 `SUBMITTED`；这是保守值。
   开发商店在显式 `REFUND` 交易下返回 `SUCCESS`，但“无交易节点仍已受理”
   没有被真实验证。

## 幂等重放边界

- 重放判断在 `getOrder` / `quoteRefund` **之前**：同 key 同指纹直接返回原
  `RefundReceipt`，因此订单被最后一笔退款退空后，重放不会退化为
  `STATE_NOT_ALLOWED`；同 key 不同指纹返回 `INVALID_REQUEST`。
- 指纹 = 订单 + 排序后的 `lineItemId × quantity` + `refundShipping` + `reason`；
  缓存键 = 租户 + 幂等键，跨租户不共享。
- 缓存是进程内 `LinkedHashMap` access-order，上限 10,000 条；进程重启或跨实例
  仍依赖 Shopify `@idempotent` 的 24 小时窗口。**持久幂等仍由后续
  `RefundApprovalStore` / outbox 负责，这里不宣称已具备。**
- 真实 `#1015` 实测：订单 `REFUNDED`、可退数量 0 后，用首笔 key + 相同 intent
  重放，仍返回首笔 refund id。

## 已知缺口

1. 运费退款是「全退或全不退」；`totalRefundedShippingSet` 只要为正就视为已退完，
   部分退运费的场景会被保守拒绝。
2. `userErrors` 统一映射为 `INVALID_REQUEST`，没有细分到具体字段。
3. 401/403 映射为 `INTERNAL`，不是 `UNAUTHORIZED`；错误码枚举还没有认证类。
4. 多笔退款交易部分成功、部分失败时，任一 `FAILURE/ERROR` 即判 `FAILED`，
   没有部分成功模型。
5. 没有分页、主动限流预算、Webhook 验签/去重/对账与持久化审批账本；
   重放缓存只在进程内。

## 相关文档

- 端口与 TCK：`kernel/shoppilot-commerce-contract/README.md`
- 逐项对照与七个冲突：`docs/shopify-refund-contract-mapping.md`
- 正式裁决：`docs/adr/0088-refund-approval-ledger.md`、`0089-refund-settlement-read-model.md`、
  `0090-buyer-term-and-store-scoped-customer.md`
