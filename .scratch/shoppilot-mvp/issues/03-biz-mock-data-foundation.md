# 03 — biz-mock 数据底座与租户感知仓储

**What to build:** 一个只监听本机、校验内部 token 的业务系统，能按订单/物流/优惠券/退款/工单回答真实数据，且任何查询都无法越出本租户。落实 ADR 0002、0004、0005。

**Blocked by:** 01 — 三模块骨架与中间件容器栈

**Status:** ready-for-agent

**Verify:** seed 跑两次后统计订单总数，再用 A 店身份查 B 店订单 -> 两次数量一致不翻倍；查询返回"未在本店找到该订单"而非 500。

- [ ] 表：`tenants` `customers` `customer_shops` `orders` `order_addresses` `logistics` `coupons` `refunds` `tickets`；金额一律以分为单位的整型存储
- [ ] 订单状态机 `CREATED -> PAID -> SHIPPED -> DELIVERED -> COMPLETED`，分支 `CANCELLED` 与 `REFUNDING -> REFUNDED`；非法转移在仓储层拒绝
- [ ] 租户感知仓储：所有查询强制拼接归属条件（Hibernate `@TenantId` 或 entity filter），禁止裸 JPQL 绕过，新增实体默认受管
- [ ] seed 脚本幂等可重跑：3 租户 / 200 买家 / 5 万订单 / 约 20 万物流节点，含跨店买家样本（同一买家在 A、B 两店各有订单）
- [ ] 只监听 127.0.0.1，校验 `X-Internal-Token`，缺失或不匹配返回 401
- [ ] 故障注入参数 `delayMs` / `failRate` 在订单与物流两个端点上可用
- [ ] 越权用例进 CI：A 店身份查 B 店订单必须返回"未在本店找到该订单"语义，不得返回 500 或空指针

## Handoff notes

（收尾时填写：关键决策 + 你需要能当场回答的三个追问）
