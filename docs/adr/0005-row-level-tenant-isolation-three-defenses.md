# 租户隔离采用行级逻辑隔离，配三道防线

Context: 需要在行级逻辑隔离（共享表 + 强制归属谓词 + 共享 Qdrant collection 与 ES 索引 + payload filter）、schema 级（每租户一 schema，需 `AbstractRoutingDataSource` 动态路由）、库/collection 级（真物理隔离）之间选定一级。B 与 C 会让 seed 脚本、JPA 配置、压测数据准备全部复杂化，per-tenant collection 在租户数增长后爆炸，而 payload filter 本就是为此设计。决定：采用行级逻辑隔离，文档与话术统一称"租户隔离"，不得称"物理隔离"。

Consequences:
- 防线一（最关键）：`tenantId` 与 `customerId` 只能由网关从鉴权结果注入服务端上下文；请求体、查询参数、Header 中客户端传来的任何 `tenantId` 一律忽略并打告警日志。此条缺失则隔离形同虚设。
- 防线二：业务查询一律走租户感知仓储（Hibernate `@TenantId` 或 entity filter 自动拼接归属条件），禁止裸 JPQL 绕过，新增实体默认受管。
- 防线三：跨租户越权用例进 CI——A 店会话查询、改址、退款作用于 B 店订单必须返回"未在本店找到该订单"。该用例同时作为现场演示素材。
