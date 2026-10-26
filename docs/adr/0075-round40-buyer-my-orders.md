# round40 开轮依据：买家「我的订单」只读入口

Date: 2026-10-26　Status: **Accepted**（2026-10-26 所有者指令「继续做产品」）
Owner basis: 买家端已有产品布局，但买家只能看工单，看不到自己的订单；
所有订单操作仍必须通过对话或人工，缺少一个真实产品常见的“我的订单”入口。
Tickets: 实现票 [159](../../.scratch/shoppilot-mvp/issues/159-buyer-my-orders.md)（票面即本轮 spec）。

## Context

ADR 0074 完成买家端双栏布局后，侧栏只有工单与服务说明；对话里的“订单 90001”仍是买家先记住编号再手打。
biz-mock 已有订单实体、`@TenantId` 租户过滤与 `customerId` 归属条件，但没有买家侧列表读面。

## Decision

1. 新增**只读**买家订单列表：`GET /api/v1/support/orders/mine`。
   - 网关只从已验签身份取 tenant/customer，转发 biz-mock；
   - biz-mock 用既有租户过滤 + `customerId` 条件查询，最多返回最近 20 单；
   - 返回复用 `OrderView`，不新增 DTO。
2. 买家端侧栏增加“我的订单”卡片：订单号、状态、金额、品类、时间；点击即把订单号带入对话查询。
3. 不改订单写入、工具调用、审批、工单与 gold 判据。
4. 不做订单详情页、商品浏览、历史分页或跨店铺切换。

## Non-goals

- 不新增公开订单端点；订单列表必须经过已验签买家身份。
- 不做订单取消 / 改地址等写动作；这些仍走对话工具与既有安全门。
- 不做分页、搜索或订单详情独立路由。

## Consequences

- biz-mock 新增 `myOrders` 服务方法与只读 controller；网关新增买家代理端点。
- 隔离由既有 `@TenantId` 与 `customerId` 条件保证；新增 HTTP 测试需证明换店/换人不可见。
- 买家端新增订单卡片与 API 封装；`static/buyer/` 产物重建入库。
- 若本轮未做活体浏览器验收，必须在票面照登。
