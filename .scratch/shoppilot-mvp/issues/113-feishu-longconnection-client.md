# 113 飞书长连接客户端（SDK 引入 + 事件归一接入）

**Status:** ready-for-agent

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

（收口时补）
