# 03 — biz-mock 数据底座与租户感知仓储

**What to build:** 一个只监听本机、校验内部 token 的业务系统，能按订单/物流/优惠券/退款/工单回答真实数据，且任何查询都无法越出本租户。落实 ADR 0002、0004、0005。

**Blocked by:** 01 — 三模块骨架与中间件容器栈

**Status:** done

**Verify:** seed 跑两次后统计订单总数，再用 A 店身份查 B 店订单 -> 两次数量一致不翻倍；查询返回"未在本店找到该订单"而非 500。

- [x] 表：`tenants` `customers` `customer_shops` `orders` `order_addresses` `logistics` `coupons` `refunds` `tickets`；金额一律以分为单位的整型存储
- [x] 订单状态机 `CREATED -> PAID -> SHIPPED -> DELIVERED -> COMPLETED`，分支 `CANCELLED` 与 `REFUNDING -> REFUNDED`；非法转移在仓储层拒绝
- [x] 租户感知仓储：所有查询强制拼接归属条件（Hibernate `@TenantId` 或 entity filter），禁止裸 JPQL 绕过，新增实体默认受管
- [x] seed 脚本幂等可重跑：3 租户 / 200 买家 / 5 万订单 / 约 20 万物流节点，含跨店买家样本（同一买家在 A、B 两店各有订单）
- [x] 只监听 127.0.0.1，校验 `X-Internal-Token`，缺失或不匹配返回 401
- [x] 故障注入参数 `delayMs` / `failRate` 在订单与物流两个端点上可用
- [x] 越权用例进 CI：A 店身份查 B 店订单必须返回"未在本店找到该订单"语义，不得返回 500 或空指针

## Handoff notes

**关键决策**

1. **biz-mock 是独立进程（:8091），不是网关里的一个包。** ADR 0002：只有跨进程，"租户身份必须随调用传递"才无法被偷懒绕过——同进程里顺手读个字段就过去了，跨进程则必须显式带上 `X-Internal-Token` 与身份。
2. **只监听 `127.0.0.1`，所有管理端点校验 `X-Internal-Token`**，缺失或不匹配 401。内部凭证永不下发浏览器（ticket 15 的调试台经网关代理取数）。
3. **金额一律以"分"为单位的整型存储。** 浮点金额在退款与满减叠加场景会累积误差，这是业务系统的基本功，不是电商特例。
4. **订单状态机显式枚举并在仓储层拒绝非法转移**：`CREATED -> PAID -> SHIPPED -> DELIVERED -> COMPLETED`，分支 `CANCELLED` 与 `REFUNDING -> REFUNDED`。状态校验放业务系统而不是网关，因为业务系统才是状态真相的所有者（ticket 12 的前置校验也建立在这条上）。
5. **租户感知仓储用 Hibernate `@TenantId`（discriminator 列）实现行级隔离**，这是 ADR 0005 的第二道防线；第一道是 token 身份注入，第三道是查询端点强制归属条件。裸 JPQL 绕过由"新增实体默认受管 + 越权用例进 CI"压制。
6. **seed 幂等：`orders` 表非空即跳过**，所以重启不翻倍；规模 3 租户 / 200 买家 / 5 万订单 / 约 20 万物流节点，并刻意造出跨店买家样本（同一买家在 A、B 两店各有订单），否则"跨店越权"这条最重要的用例根本没有素材。
7. **故障注入参数 `delayMs` / `failRate` 内建在订单与物流端点上**，ticket 14 的降级链路靠它复现，不需要真的拔网线。

**你需要能当场回答的三个追问**

- *Q：A 店查 B 店订单，为什么返回"未在本店找到该订单"而不是 403？* A：403 会确认"这单存在"，本身就是信息泄露。`@TenantId` 让 B 店订单在 A 店的会话里根本不可见，语义上等价于不存在，这也是 CI 用例的断言内容。
- *Q：5 万订单在 H2 上不会慢吗？* A：会，而且慢是设计的一部分——H2 写入是本项目已知的吞吐瓶颈（README 已知限制里写明）。压测报告里的 TP99 含这段真实成本，不是内存玩具。
- *Q：seed 数据是随机的，演示怎么保证可复现？* A：随机造数之外另钉四张演示固定单 90001-90004（ticket 12），`POST /api/admin/demo/reset` 复位。

**验证记录（2026-09-05）**

seed 连跑两次订单总数不变；A 店身份查 B 店订单返回"未在本店找到该订单"，无 500、无空指针；`TenantIsolationAndIdempotencyTest` 覆盖越权与唯一约束。


**追加决策（2026-09-10，dev 评测反推）**

7. **改地址支持部分更新：留空 = 不改这一项。** `ModifyDeliveryAddressRequest` 的必填收到 `orderNo` + `receiverName` + `receiverPhone` 三项，地址四段改为可选，biz-mock 用 `keepIfBlank` 与原值合并之后再写 `address_history` 与回包，避免半条地址落库。理由是实测：`90004 收件人换成李某，电话 13800001234` 是最高频的真实诉求，旧写法要么逼用户重述整条地址（模型就改去反问），要么把 null 写进非空列。
8. **退款的 `reason` 与 `amountFen` 都是可选项，默认值落在 biz-mock 而不是 Prompt 里**：留空 = 实付全额 + `买家主观原因`。以前 `reason` 标必填，模型照实反问"请问退款原因"，而"报了订单号就该办"才是这条链路的正确行为；`requiredParams` 与 schema 同源（record component），改一处两边同时变。
9. **`queryLogistics` 不许对存在的订单回 NOT_FOUND**：订单查到了却没有轨迹节点（退款中、刚出库）以前走 `notFound`，于是助手对顾客说"可能不是本店下单"——对自己的订单撒谎。现在回 `STATE_NOT_ALLOWED` 并带上订单号与当前状态，下一步指向 `queryOrderDetail`。这条是 dev 评测 ACT-ORD-17 抓到的。
10. 以上三条由 `TenantIsolationAndIdempotencyTest.logisticsNeverClaimsAnExistingOrderIsMissing` 钉住（用例数 97 -> 98）：只报订单号的退款要落库成默认值，退款之后再查物流不得是 NOT_FOUND。
11. **（同日稍后，门禁 eval 冒烟反推）决策 7 的“留空 = 不改这一项”有副作用，副作用由契约与复述兜，语义本身不撤销。** 地址四段可选之后，模型把用户明明说过的段留空就等于静默沿用旧值：门禁冒烟三条改地址实测两次漏 `city`、一次连 `district` 一起漏（同一句里省与详址都填对了，是 3B 的行为不是编排的锅）。两道防线：schema 描述与 SYSTEM_PROMPT 都写死“用户提到了就必须照抄，只有完全没提到才留空”（修后三条里两条全对），以及办理成功后必须按工具返回的**合并后完整地址**复述给用户——复述不阻止漏填，但让用户当场看见哪一段没变，而不是以为改好了。部分更新语义保留：“只换个收件人”是真实高频诉求，代价不该由它付。云端 `qwen-plus` 那一轮无此形态（ADDRESS 14/15 参数全对，无一条把提到过的字段留空）。

**追加追问**

- *Q：地址四段都可选，会不会把一条空地址写进去？* A：不会。`receiverName` 与 `receiverPhone` 仍是必填，缺任一项在网关侧就 `SLOT_ASK`；四段留空在 biz-mock 侧是"沿用原值"，写历史与回包用的都是合并后的完整地址。
- *Q：`reason` 默认为什么填在 biz-mock 而不是网关？* A：默认值是业务规则（哪种退款理由算主观），归业务系统所有；网关只负责把"模型没抽到这个字段"如实传下去。放在网关会让 Mock 中台的语义随编排层漂移。
