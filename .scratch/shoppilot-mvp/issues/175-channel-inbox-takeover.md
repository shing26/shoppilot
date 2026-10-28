# 175 渠道会话收件箱：飞书会话可接管/回复

**Status:** implemented（2026-10-28）

**Claimed by:** zcode session 2026-10-28

> 飞书消息目前只进 Agent，坐席看不到未转人工的会话。本票做一个单节点 Redis 收件箱：会话进工作台，可接管、回复、释放。

## 目标

- 每条飞书入站/出站都落一份渠道会话记录（最近消息、租户、买家、状态、负责人）；
- 工作台新增「渠道会话」视图：列表 + 详情 + 接管/释放 + 回复；
- 接管后 Agent 不再自动回复，消息只进收件箱，等坐席处理；
- 回复经 FeishuReplySender 发回飞书，并写入会话记录；
- 租户隔离：坐席只能看到本租户会话；
- Redis 不可用时降级为空列表，不阻塞聊天。

## 数据

Redis：

- 会话：shoppilot:channel-inbox:{tenant}:{conversationId}（JSON，TTL 7 天）
- 索引：shoppilot:channel-inbox-index:{tenant}（set，TTL 7 天）

状态：BOT / AGENT。

## API

- GET /api/v1/support/ops/channel-conversations
- GET /api/v1/support/ops/channel-conversations/{id}
- POST /api/v1/support/ops/channel-conversations/{id}/takeover
- POST /api/v1/support/ops/channel-conversations/{id}/reply
- POST /api/v1/support/ops/channel-conversations/{id}/release

均走既有 ops 鉴权（坐席/管理员令牌）。

## 验收

- 飞书入站后工作台能看到会话与最近消息；
- 接管后状态 AGENT、负责人=当前坐席；
- 接管期间新飞书消息不再触发 Agent 自动回复；
- 坐席回复能发回飞书并追加到会话；
- 释放后状态 BOT，Agent 恢复；
- 租户隔离；
- 旧 JVM 测试不回退。

## Non-goals

- MySQL 持久化/归档；
- 多渠道路由；
- 附件/图片；
- 实时 WebSocket 推送（先轮询）。

## 已实现

- ChannelConversationStore：Redis 会话 + 索引，TTL 7 天，BOT/AGENT，最近 30 条；
- FeishuLongConnectionClient 入站写会话；AGENT 状态跳过 Agent 自动回复；出站写回复；
- ChannelInboxController：列表/详情/接管/回复/释放；租户从 TenantContext 取；
- 工作台第五视图「渠道会话」：列表、详情、接管/释放、回复框；
- docs/CHANNEL_INBOX.md。

## 验证

- ChannelConversationStoreTest 4 条：入站/接管、回复/释放、租户隔离、30 条上限；
- 全量 verify：724 tests / 121 suites 全绿；
- 工作台 typecheck/build 通过；Playwright 1440×900 / 390×936 实拍；
- 用种子会话验证接管：状态变 AGENT、负责人 agent、出现回复框；
- **未验证**：向真实飞书会话发送坐席回复（避免对演示账号发未经确认的消息）。

## Handoff notes

**关键决策**

1. 用 Redis 单节点收件箱，不先上 MySQL；TTL 7 天，审计仍走工单/审计流；
2. 接管后 Agent 直接跳过，不生成“机器人已收到”之类噪音；
3. 回复先发送后落库，发送失败返回 502 且不写入假消息；
4. 租户只从 TenantContext 取，Redis 键带 tenant，防跨店读取。

**验证落点**

- ChannelConversationStoreTest；
- 全量 logs/verify175.log；
- 截图 docs/workspace-conversations*.png；
- 文档 docs/CHANNEL_INBOX.md。

**三个现场追问**

1. 为什么轮询？先证明坐席能接管/回复，实时推送是体验优化；
2. 为什么只存 30 条？收件箱不是审计归档，长历史在会话/工单里；
3. 怎么证明回复可用？代码复用已实测的 FeishuReplySender；本轮未向真实账号发消息，下一步联调再点发送。
