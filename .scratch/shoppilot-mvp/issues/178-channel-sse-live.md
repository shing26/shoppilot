# 178 渠道会话：真实新消息触发 SSE 活体

**Status:** implemented（2026-10-28，真实新消息触发 SSE 已验证）

**Claimed by:** zcode session 2026-10-28

> 票 177 用 takeover 触发验证 SSE。本票用真实飞书买家新消息验证：工作台不刷新，未读角标自动出现。

## 步骤

1. 起栈与网关，工作台打开「渠道会话」并保持不动；
2. 真实飞书用户发一条新消息；
3. 不刷新页面，等待未读角标/会话出现；
4. 截图并记录 nav 文本、会话条数与未读 pill；
5. 清理测试会话。

## 验收

- 页面不刷新，新会话自动出现；
- nav 未读角标 >0；
- 会话卡片 unread pill 可见；
- SSE 连接无报错。

## Non-goals

- 浏览器通知/声音；
- 多渠道路由。

## 结果

- 20:22 后台浏览器进入工作台渠道会话，nav=0，之后不再刷新/点击；
- 20:51:21 真实飞书消息“实时未读测试”到达，日志 `飞书消息身份 chatId=oc_d321... tenant=T001 customer=C001`；
- Redis 会话 unread=1，消息为“实时未读测试” + Agent 回复；
- 页面未刷新，nav 自动变 1，会话卡片出现 unread=1，截图 docs/workspace-conversations-live-sse.png；
- 后台脚本从未调用刷新接口，只等待 `.unread-pill` 出现。

**说明**：19:51 那次 30 分钟监听没有收到任何入站，是消息没有发出/未到达；不是 SSE 丢事件。20:51 这次真实到达后自动更新。

## Handoff notes

**关键决策**

1. 用真实浏览器 + 真实飞书消息，不用 seed/定时器；
2. 脚本不刷新页面、不点刷新按钮，只等待 unread pill；
3. 先确认网关日志有 `飞书消息身份`，再谈页面是否收到——区分“消息没到”和“SSE 没推”。

**验证落点**

- 日志：20:51:21 飞书消息身份；
- Redis：conversation.unread=1；
- 截图：docs/workspace-conversations-live-sse.png；
- Playwright 输出 nav=1、cards 含 `FEISHU / 1 / BOT / 实时未读测试`。

**三个现场追问**

1. 怎么证明没刷新？后台脚本只做一次 goto/login/click，之后只 waitForSelector，没有刷新或轮询 API；
2. 为什么之前 30 分钟没结果？网关日志没有入站，说明消息没到，不是 SSE 问题；
3. 为什么 unread=1 而不是清零？新会话未被选中，按设计保留未读，等坐席点开才清。
