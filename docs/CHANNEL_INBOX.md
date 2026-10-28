# 渠道会话收件箱（票 175）

**状态：** 已实现。飞书会话进入坐席工作台，可接管、回复、释放。

## 数据

Redis 单节点，TTL 7 天：

- 会话：`shoppilot:channel-inbox:{tenant}:{conversationId}`
- 索引：`shoppilot:channel-inbox-index:{tenant}`

状态：

- `BOT`：智能助手自动回复；
- `AGENT`：坐席接管，Agent 不再自动回复。

每个会话保留最近 30 条消息，含买家/助手/坐席角色。

## 工作台

登录 `/workspace/` → 左侧「渠道会话」：

- 列表：渠道、状态、最近一句、买家、时间、负责人；
- 详情：完整最近消息时间线；
- `BOT`：显示「接管会话」；
- `AGENT`：显示「释放回助手」和回复框；
- 回复经飞书 API 发回，并追加为坐席消息。

截图：

- [workspace-conversations.png](workspace-conversations.png)
- [workspace-conversations-takeover.png](workspace-conversations-takeover.png)
- [workspace-conversations-mobile.png](workspace-conversations-mobile.png)

## API

均在 `/api/v1/support/ops/channel-conversations`：

- `GET /`：本租户会话；
- `GET /{id}`：详情；
- `POST /{id}/takeover`：接管，负责人取当前登录账号；
- `POST /{id}/reply`：仅 `AGENT` 可回复，正文不能为空；
- `POST /{id}/release`：释放回 BOT。

租户只从 `TenantContext` 取，不接受查询参数。

## 接管语义

- 接管时给飞书发一句“人工坐席 X 已接入”；
- 接管期间新飞书消息只进收件箱，不触发 Agent；
- 释放时发“已切回智能助手”，之后恢复自动回复。

## 验证

- `ChannelConversationStoreTest` 4 条：入站/接管、回复/释放、租户隔离、30 条上限；
- 全量 JVM：724 tests / 121 suites；
- 工作台 typecheck/build 通过；
- Playwright 1440×900 / 390×936 实拍；种子会话接管后状态变 AGENT 并出现回复框。

## 边界

- Redis TTL 7 天，不是审计归档；
- 轮询刷新，没有实时推送；
- 只接飞书；
- 回复发送复用 `FeishuReplySender`；本轮用种子会话验证了接管 UI，**没有真的向真实飞书会话发送坐席回复**（避免对演示账号发未经确认的消息）。
