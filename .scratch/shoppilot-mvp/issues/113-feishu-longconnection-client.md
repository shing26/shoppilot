# 113 飞书长连接客户端（SDK 引入 + 事件归一接入）

**Status:** implemented

## What to build

B4 的承重票：让飞书的真事件流进既有的入站链路。

- **引入飞书官方 SDK**（Java `oapi-sdk` 2.4.0+，长连接客户端内置）：心跳、重连、连接
  生命周期由 SDK 承担，不自实现（ADR 0065 决策 4；票 97 口径预留过 B 段引 SDK 这一格）。
- **事件归一接入**：飞书单聊文本事件 → 归一进 `ChannelAdapter.NormalizedChat` 契约
  （含票 96 的两格：`conversationId` 由「平台 + open_id/chat 级标识」派生，`clientToken`
  由平台 message id 派生）→ 走既有 chat 链路。
- **出站回复**：agent 产出 → 飞书 API 回复到同一会话（SDK 的回复接口）。
- **断线语义**：SDK 重连期间的事件丢失要有日志可查（登记语义，不承诺不丢——飞书侧
  没有补推保证，这一格照登）。

## Blocked by

[96](96-inbound-contract-session-and-client-token.md)、[97](97-im-adapter-and-replay-gate.md)。

## 口径

- **零暴露红线**（ADR 0065 决策 2）：长连接是出站 WSS，不开新端口、无入站回调。
- **只做单聊文本问答链路**：群聊、卡片、富媒体登记不做（round31 spec §5）。
- SDK 只进本票；A 段录放门（97）不吃 SDK。
- 长连接客户端的配置读取走票 114 的凭据家法（本票先用占位配置打通解析层）。

## 验收

- JVM：飞书事件 JSON（夹具）→ 归一结果逐字断言（含两格派生）；事件解析的坏形状
  （缺字段/非文本）不进链路且有日志；断线/重连的状态语义单测。
- `.\mvnw.cmd -B -ntp verify` 绿。
- **变异对照**：把 `clientToken` 派生改回 null → 幂等靠参数猜的红（同票 96 的变异）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

### 关键决策

- **SDK 引入**：飞书官方 `oapi-sdk` 2.4.0+（`com.lark:oapi-sdk:2.4.0`），长连接客户端
  `com.lark.oapi.ws.Client` 承担心跳/重连/生命周期，不自实现（ADR 0065 决策 4）。
- **事件归一**：`FeishuLongConnectionClient.handleEvent()` 把飞书事件 JSON → `NormalizedChat`
  契约（票 96 两格派生：`conversationId = feishu:chat:<chat_id>`，`clientToken = feishu:msg:<message_id>`），
  走既有 `AgentStateMachine.run()` 链路。
- **出站回复**：`FeishuReplySender` 接口 + `FeishuApiReplySender` 实现（SDK `client.im().message().create()`），
  回复到同一会话。
- **断线语义**：SDK 重连期间事件丢失照登（飞书侧无补推保证），日志可查，不承诺不丢。
- **零暴露红线**：长连接是出站 WSS，不开新端口、无入站回调（ADR 0065 决策 2）。

### 验证落点

- **JVM 测试**：`FeishuLongConnectionClientJvmTest` 10 个测试全绿，覆盖：
  - 正常编排（事件 → 归一 → agent → 回复）
  - 身份注入（TenantContext/ChannelContext 正确设置）
  - 空答案跳过（agent 返回空 answer 时不发回复）
  - 坏形状拒绝（非文本消息、非飞书格式）
  - ThreadLocal 清理（finally 块保证不泄漏）
  - stop() 清理 wsClient 引用
  - 变异测试（clientToken 派生依赖 messageId、conversationId 派生依赖 chat_id）
  - EventSink.NOOP 传递
- **全量 verify**：`.\mvnw.cmd -B -ntp verify` BUILD SUCCESS（374 tests, 0 failures）。
- **修复**：FallbackReasonTest 大小写 bug（`c001` → `C001`），与本票改动无关但阻塞全量绿。

### 现场追问

1. **飞书 SDK 的 `Client.start()` 是阻塞调用吗？** — 是，`start()` 内部起线程跑事件循环，
   调用方线程不阻塞；`stop()` 调 `disconnect()`（protected，通过反射或子类暴露）。
2. **事件归一后 `NormalizedChat.contact` 填什么？** — 填 `open_id`（发送者标识），
   用于 agent 侧的「谁发的」上下文；`conversationId` 填 `feishu:chat:<chat_id>`。
3. **如果飞书事件里 `chat_id` 为空怎么办？** — `FeishuAdapter.normalize()` 已加空值检查，
   抛 `IllegalArgumentException("飞书事件缺少 chat_id")`，不进链路（变异测试覆盖）。
