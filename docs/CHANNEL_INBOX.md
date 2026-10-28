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

## 多实例 SSE（票 179）

- 会话变更发布到 Redis 频道 `shoppilot:channel-inbox-events`；
- 每个网关副本的 `RedisMessageListenerContainer` 订阅该频道，收到后 fan-out 给本机 SSE 客户端；
- 发布成功不再本地推，避免同一事件两次；发布失败/无订阅者才本地 fallback；
- 消息内仍按 tenant 过滤。

活体验证：本机 SSE 连接后，用 `redis-cli PUBLISH` 模拟另一实例发布，收到 `event:conversation` 与完整会话 JSON。

## 浏览器通知（票 180）

- 渠道页「开启浏览器通知」申请 Notification 权限；
- 仅当 SSE 事件 unread>0 且最后一条是买家消息时弹；
- 当前选中会话且页面可见时不弹；
- 点击通知回渠道会话、选中并已读；
- 权限/开关存 localStorage。

截图：[workspace-conversations-notification.png](workspace-conversations-notification.png)。

## Service Worker / Web Push（票 181）

- 工作台注册 `/workspace/sw.js`；
- 有 Push 能力时订阅并 POST 到 `/api/v1/support/ops/channel-notifications/subscribe`；
- 后端按租户存 Redis hash `shoppilot:channel-push:{tenant}`，发 Web Push；
- SW `push` 展示通知，`notificationclick` 聚焦/打开工作台并回会话；
- 没有 Push 能力时回退页面内通知。

**边界**：VAPID 未配置时用启动生成的临时密钥，重启后旧订阅失效；真实 Web Push 发送未在 headless 验收（无可用 push service）。

截图：[workspace-conversations-service-worker.png](workspace-conversations-service-worker.png)。

## 固定 VAPID / 推送重试指标（票 182）

- 本机 `.env` 固定 VAPID 公钥/私钥/subject，公钥接口与 `.env` 一致；
- `.env.example` 只放占位，私钥不进仓库；
- 发送失败：404/410 删订阅；429/5xx/异常退避 100ms、200ms 重试；
- 指标：`shoppilot_push_send_total{result=...}`、`shoppilot_push_latency_seconds`；
- 订阅保存失败返回 503，前端回退页面通知；
- 日志只打印 endpoint host。

**真实 FCM 边界**：持久化 Chrome 已能拿到真实 FCM endpoint；本机 Java/Node 发送均被 DevSidecar MITM 代理阻断（Java PKIX / 代理 500），未完成送达。

## 提示音（票 183）

- 渠道页「开启提示音」创建/恢复 AudioContext；
- 新买家消息（未选中/页面不可见）播放 880Hz 短音；
- 开关存 localStorage，与通知/Push 独立。

## 边界

- Redis TTL 7 天，不是审计归档；
- SSE 实时推送 + 5 秒重连，断线时手动刷新兜底；
- 不做浏览器通知/声音；
- 只接飞书；
- 回复发送复用 `FeishuReplySender`；真实活体已用飞书读接口核对（2026-10-28）；
- 接管期间“Agent 跳过自动回复”未做活体对照，只有代码与状态机测试覆盖。
