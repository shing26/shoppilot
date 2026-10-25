# round38 开轮依据：坐席工作台控制台化（把编排质量数据变成产品面）

Date: 2026-10-25　Status: **Accepted**（2026-10-25 所有者指令「要的是产品成果感，前端没怎么打磨」）
Owner basis: 所有者评审 H1-H4 后指出：后端持续加固已足够，当前产品感短板在展示面；
坐席工作台仍像单页 demo，H4 的质量数据只有接口没有界面。
Tickets: 实现票 [157](../../.scratch/shoppilot-mvp/issues/157-workspace-console-product-surface.md)（票面即本轮 spec）。

## Context

ADR 0070 已把 `/workspace/` 定为坐席产品入口，并完成队列、领取、结单、退款审核与基础视觉令牌。
ADR 0072 H4 又交付了只读 quality sidecar，但它只有 `GET /api/v1/support/ops/quality/report`，
产品面看不到。结果是：

1. 坐席台功能都在，但信息架构仍是“一张长页”，缺少控制台的工作区分区；
2. 编排质量、blocked handoff、上下文丢失这些真实能力没有可见出口，评审时只能看 JSON；
3. 前端继续停留在“能用”，与后端可验证能力不匹配，形成 demo 感。

## Decision

1. 本轮只改坐席工作台：从单页升级为**三视图控制台**：
   - `工单队列`：既有队列 + 详情 + 领取/释放/结单；
   - `退款审核`：既有资金动作面；
   - `编排质量`：H4 报表的产品化视图。
2. 质量视图直接消费既有 `/quality/report`，不新增后端聚合逻辑、不进请求链路、不加 LLM 调用。
3. `/quality/report` 授权与 `OpsAuthorizationInterceptor` 对齐：
   - 运维令牌可用；或
   - 已验签的 `AGENT` / `ADMIN` 可用。
   坐席台因此能用登录令牌读报表，不需要在页面里另填运维令牌。
4. 视觉不引组件库、不加运行时依赖；继续用 `frontend-shared` 令牌和自建布局。
5. 前端源码与入库产物必须同次提交，CI 逐字节校验继续生效。

## Non-goals

- 不改买家端（下一轮单独评估）。
- 不做图表库、实时推送或多租户趋势存储。
- 不把质量视图做成干预面：它只读，不改路由、不处理工单。
- 不改 180 条 gold、阈值、判据或后端请求链路行为。

## Consequences

- `QualitySidecarController` 增加坐席/管理员角色放行；权限语义与既有 ops 面一致。
- 工作台新增 quality API 封装、类型与视图；构建产物 `static/workspace/` 重建入库。
- 若本轮未做活体浏览器验收，必须在票面照登，不得把 typecheck/build 绿写成页面可验收。
