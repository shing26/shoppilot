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

## 真实活体（2026-10-28）

- 飞书用户发消息 → 收件箱出现会话，Agent 自动回复；
- 坐席接管：status=AGENT、assignee=agent，飞书收到“人工坐席已接入”；
- 坐席回复：飞书 `im/v1/messages` 读回“您好，我是人工坐席，已收到您的测试消息。”；
- 释放：status=BOT，飞书收到“已切回智能助手”；
- 释放后用户再发一条，Agent 自动回复并带工单号。

注意：第一次回复用 PowerShell 拼 JSON，中文被编码成 `??` 发到飞书；改用 UTF-8 JSON 后正常。这是测试脚本的编码问题，不是服务端。

截图：[workspace-conversations-live.png](workspace-conversations-live.png)。

## 实时未读（票 177）

- 买家入站 unread +1；接管、已读、坐席回复清零；
- SSE：`GET /api/v1/support/ops/channel-conversations/stream`，按租户推送 `conversation` 事件；
- 前端用 fetch 流带 Authorization 头，断开 5 秒重连；
- 工作台 nav 显示未读总数，单会话显示 unread pill，选中会话自动已读。

截图：[workspace-conversations-unread.png](workspace-conversations-unread.png)。

## 真实新消息 SSE（2026-10-28）

- 工作台页面保持打开、不刷新；
- 真实飞书消息 20:51:21 到达；
- nav 自动从 0 变 1，会话卡片出现 `unread=1`；
- 脚本只做一次登录/进入视图，之后只等待 `.unread-pill`，没有刷新或轮询 API。

截图：[workspace-conversations-live-sse.png](workspace-conversations-live-sse.png)。

## 边界

- Redis TTL 7 天，不是审计归档；
- SSE 实时推送 + 5 秒重连，断线时手动刷新兜底；
- 不做浏览器通知/声音；
- 只接飞书；
- 回复发送复用 `FeishuReplySender`；真实活体已用飞书读接口核对（2026-10-28）；
- 接管期间“Agent 跳过自动回复”未做活体对照，只有代码与状态机测试覆盖。
