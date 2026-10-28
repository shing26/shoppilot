# 184 Web Widget 公开入口

**Status:** implemented（2026-10-29）

**Claimed by:** zcode session 2026-10-29

> 不依赖外部渠道凭证，给外部网页一个可嵌入客服 Widget：访客消息进 Agent、进收件箱，坐席可接管回复。

## 已实现

- `/widget/` 静态聊天页 + `widget.js`，可通过 iframe/链接嵌入，支持 `?key=` 覆盖；
- `POST /api/v1/widget/chat`：`X-Widget-Key` 换固定租户/买家身份，复用 AgentStateMachine；
- 消息写入 `ChannelConversationStore`（channel=web），Agent 回复也写回；
- `GET /api/v1/widget/chat/{visitorId}` 轮询历史；
- AGENT 状态跳过自动回复，只提示人工已接入；
- 坐席接管/回复 web 会话不再调用飞书，只落库；
- AuthFilter 放行 `/widget/` 与 `/api/v1/widget/**`，key 常量时间比较；
- 默认 demo key `demo-widget-key`，生产用 `SHOPPILOT_WIDGET_*` 覆盖。

## 验证

- Widget 页面真实发送“我的订单到哪了？”，Agent 回复要求订单号并落库；
- 收件箱出现 `web:<visitor>`；接管 noticeSent=false、回复 200、历史接口可读到 agent 消息；
- 全量 verify：**738 tests / 126 suites** 全绿；
- 截图 docs/widget-live.png。

## Non-goals

- 一租户一 key 的配置中心；
- WebSocket 实时推送（当前 2s 轮询）；
- 访客文件/图片。

## Handoff notes

**关键决策**

1. 公开入口用 widget key 而不是 JWT，避免把买家令牌暴露给嵌入页；
2. 复用 AgentStateMachine 与 ChannelConversationStore，不另写一套消息链；
3. web 会话的坐席回复只落库，不能调飞书 sender；
4. 默认 key 只用于演示，生产必须换 `SHOPPILOT_WIDGET_KEY`。

**三个现场追问**

1. 为什么不用现有 `/chat`？它要买家 JWT，Widget 不能把令牌发给任意嵌入页；
2. 消息会进工作台吗？会，`web:<visitor>` 写入同一个收件箱 Redis；
3. 为什么轮询？先用最小实现，SSE 复用可下一票做。