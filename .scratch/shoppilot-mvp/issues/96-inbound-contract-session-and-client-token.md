# 96 入站契约补会话 id 与 clientToken 派生

**Status:** ready-for-agent

## What to build

**平台无关的两格**（ADR 0060 决策 2）。任何 IM 平台都要它们，且今天就能在 CI 里验证。

- **`ChannelAdapter.NormalizedChat` 补两个字段**：
  - `conversationId`：由适配器按「平台 + 那个聊天」派生。**没有它就沿用请求头**，
    而没有请求头时 `AuthFilter` 会现生成随机 UUID（每条消息一个新会话）。
  - 契约上要写清：**适配器有责任派生它**，不能指望调用方每次都传 `X-Conversation-Id`。
- **`clientToken` 派生**：适配器从平台 message id 派生一个稳定 token，让 `IdempotencyService`
  走 `clientToken` 那条路（`resolveToken` 里它优先），而不是落到
  `tenantId|customerId|tool|参数` 那条**不含渠道、TTL 6 小时、靠参数猜**的派生路径。
- **`ChannelController` 把派生出来的会话 id 传下去**（`agent.run` 目前只接 query / idem / sink）。

## Blocked by

无。

## 口径

- **不改缓存键、不改身份推导**：ADR 0035 那条「渠道只是标签」一字不动。
  会话 id 进了会话键，而会话键本来就含 `conversationId`——所以这是**补上一格**，不是换一套键。
- **不给 `NormalizedChat` 加「平台」字段**：渠道已经在 `ChannelContext` 里，再加一份就是两处真相。
- 不引任何 IM SDK（这一票一个依赖都不加）。

## 验收

- JVM 用例覆盖：
  - 不传 `X-Conversation-Id` 的两次调用**仍然是两段会话**（现状），而适配器**派生了会话 id 之后**的两次调用落在**同一段会话**；
  - **同一个人、不同渠道派生出的会话 id 不同**（防「两个平台共用一个 session 键」）；
  - 派生 `clientToken` 之后，**同一个人在不同渠道发同一组参数、message id 不同 → 两次都执行**，
    而不是第二次被判 `IDEMPOTENT_REPLAY`；
  - message id 相同 → 仍然判重放（幂等没被绕过）。
- **变异对照**：把 `clientToken` 传成 null → 第一条与第三条红（回到今天的形态）；
  把会话 id 改成只用平台名不用聊天 id → 第二条红。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

（收口时补）