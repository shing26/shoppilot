# 159 买家「我的订单」只读入口

**Status:** implemented（2026-10-26）

**Claimed by:** zcode session 2026-10-26（ADR 0075）

> 依据：[ADR 0075](../../../docs/adr/0075-round40-buyer-my-orders.md)。

## What to build

1. biz-mock 增加 `GET /api/orders/mine`：按当前租户 + 当前买家查最近 20 单，复用 `OrderView`。
2. 网关增加 `GET /api/v1/support/orders/mine`：身份只从已验签令牌取，转发时带内部 token 与 tenant/customer 头。
3. 买家端侧栏增加“我的订单”：订单号、状态、金额、品类、时间；点击把订单号带入对话查询。
4. 游客不展示订单；登录/一键体验后自动加载。

## Blocked by

—

## 范围与验收

- 买家只能看到自己的订单；换店、换人不可见（biz-mock HTTP 测试）。
- 网关转发时下游收到的 customer 恒为令牌里的值，不接受查询参数。
- 订单列表为空时显示空态，不伪装成错误。
- `npm run typecheck`、`npm run build` 通过；产物入库。
- 180 条 gold 零改动；JVM verify 全绿。

## Non-goals

- 不做订单详情页、写动作、分页、搜索。
- 不改既有工具与工单链路。

## Verify

- `./mvnw.cmd -B -ntp -pl shoppilot-biz-mock,shoppilot-gateway -am test`
- `cd frontend-buyer && npm run typecheck && npm run build`
- 浏览器：登录买家后看订单卡片，点击订单进入对话。

## Handoff notes

**关键决策**

1. 订单读面只读，路径是买家侧 `/api/v1/support/orders/mine`，不走 ops 前缀，也不新增公开端点。
2. 网关只从已验签 `TenantContext` 取 tenant/customer，转发 biz-mock 时补 `X-Internal-Token`；
   查询参数里的买家号一律不读。
3. biz-mock 复用既有 `@TenantId` 租户过滤 + `customerId` 条件，返回最近 20 单，复用 `OrderView`，
   不新增跨店/跨买家查询方法。
4. 买家端只做订单卡入口：订单号、状态、金额、品类、时间；点击把订单号带入对话，不新增详情路由。
   游客不展示订单，保持既有的“先登录才能看自己的数据”口径。
5. 订单状态只做产品文案映射，不新增业务状态机。

**验证落点**

- `TenantIsolationAndIdempotencyTest` 新增真实 HTTP 用例：T001/C001 只回自己的最近 20 单，
  换店、换人无交集；返回体不含 tenantId。
- `BuyerOrdersEndpointTest` 2 条：买家令牌足够；下游 tenant/customer 恒来自令牌，查询参数无效。
- `npm run typecheck` / `build` 通过；`static/buyer/` 产物已重建。
- 浏览器活体验证 **6/6**：订单侧栏存在、登录后加载 20 单、卡片含金额与状态、点击订单进入对话、
  390px 无横向溢出、无页面异常。
- 截图：[我的订单](../../../docs/buyer-my-orders.png)。
- 180 条 gold 零改动；全量 JVM verify 全绿。

**未达成 / 边界**

- 订单列表固定最近 20 单，不分页、不搜索；订单详情仍通过对话查询，不新增独立详情页。
- 既有 `verify-buyer.mjs` 未在本轮重跑；本轮使用独立浏览器脚本验证新入口。

**三个现场追问**

1. **为什么订单入口不直接跳订单详情？** 产品目前没有买家订单详情路由，硬造一个会带出地址与
   退款状态的展示口径；先把“选订单 → 问客服”这条真实路径打通，风险最小。
2. **为什么列表限 20 单？** 演示与真实客服场景都只需要最近订单；分页要新增参数与索引口径，
   不在本轮夹带。
3. **为什么游客看不到订单？** 游客是演示身份，不该把某个固定买家的订单当成“自己的”；
   这与既有游客不展示工单的口径一致。
