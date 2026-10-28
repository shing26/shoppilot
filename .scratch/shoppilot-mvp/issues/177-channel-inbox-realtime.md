# 177 渠道会话：实时未读与新消息提醒

**Status:** implemented（2026-10-28）

**Claimed by:** zcode session 2026-10-28

> 收件箱目前靠手动刷新。本票加 unread 计数与 SSE 推送，工作台实时更新列表/角标；SSE 断开保留刷新兜底。

## 目标

- Conversation 增加 unread；买家新消息 +1，接管/已读/坐席回复清零；
- SSE：GET /api/v1/support/ops/channel-conversations/stream，按租户推送 conversation 事件；
- 工作台：渠道会话角标显示未读总数，列表显示单会话未读，选中会话自动已读；
- 前端用 fetch 流带 Authorization，不用 query token；
- SSE 断开每 5 秒重连，同时保留轮询兜底。

## 验收

- 新入站后不刷新页面，会话列表与未读角标更新；
- 选中会话后 unread 清零；
- 坐席接管/回复后 unread 清零；
- 租户隔离：SSE 只推本租户；
- 旧 JVM 测试不回退。

## Non-goals

- WebSocket；
- 浏览器通知/声音；
- 多渠道路由。

## 已实现

- Conversation 增加 unread：买家入站 +1，接管/已读/坐席回复清零；
- ChannelInboxEventBus：按租户维护 SseEmitter，保存会话后推 conversation 事件；
- GET /channel-conversations/stream（SSE）；POST /{id}/read；
- 工作台 nav 未读角标、单会话未读 pill、选中会话自动已读；
- 前端用 fetch 流带 Authorization 头，不把 token 放 query；断开 5 秒重连。

## 验证

- 全量 verify：724 tests / 121 suites 全绿；
- 工作台 typecheck/build 通过；
- SSE 实连收到 event:ready 与 event:conversation；
- 未读截图：nav=2、C001 卡片 pill=2；点击 C001 后 nav=0、pill 消失；
- 截图 docs/workspace-conversations-unread.png。

**缺口**：SSE 事件用 takeover 触发验证，不是新手消息入站触发；两者走同一个 save/publish 路径，但“真实买家新消息 → 页面自动弹角标”没有单独录像。

## Handoff notes

**关键决策**

1. SSE 用 fetch 流而不是 EventSource，换取 Authorization 头、避免 token 进 URL；
2. 只推单条 conversation，不推全量列表；客户端替换；
3. 断开 5 秒重连，同时保留手动刷新兜底；
4. unread 在入站 +1，接管/已读/坐席回复清零，避免“已接管还红着”。

**验证落点**

- ChannelConversationStoreTest 增加 unread 断言；
- SSE Python 实连：event:ready / event:conversation；
- 截图 docs/workspace-conversations-unread.png；
- 全量 logs/verify177.log。

**三个现场追问**

1. 为什么不用 EventSource？它不能带 Authorization 头，token 放 query 会进日志；
2. 为什么断开 5 秒重连？SSE 长连接会被代理/进程重启切断，重连是基本可用性；
3. 怎么证明未读真的清零？Playwright 点击 C001 后 nav 从 2 变 0、unread pill 消失。
