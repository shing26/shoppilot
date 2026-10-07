# 96 入站契约补会话 id 与 clientToken 派生

**Status:** implemented（2026-10-07）

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

**关键决策**：
- `NormalizedChat` 加两格：`conversationId`（适配器派生的会话 id）+ `clientToken`（从平台
  message id 派生的幂等 token）；旧三参形态保留为静态工厂 `of()`（web 渠道用，行为零变更）。
- **派生规则**：`conversationId = <渠道label>:chat:<chatKey>`、`clientToken = <渠道label>:msg:<messageId>`。
  渠道标签取自 `ChannelContext.current()`（controller 在 normalize 前已按路径设好——不给
  `NormalizedChat` 加平台字段，避免两处真相）；聊天维度 `chatKey` = payload 可选 `sessionId`
  优先，缺省取 JWT 买家 id（一人一渠道一段会话）。**渠道标签与聊天维度缺一不可**：只留
  渠道名 → 一个渠道所有人共用一段会话；只留聊天维度 → 两个平台共用一个 session 键。
- **显式 > 派生**（与幂等 token 同一哲学）：请求头 `X-Conversation-Id` 显式给出时不动
  TenantContext；显式 `idempotencyToken` 优先于派生 clientToken。
- **webhook/email 的 payload 契约新增两个可选字段**：`messageId`（平台消息 id，派生
  clientToken 用）与 `sessionId`（平台聊天标识，派生会话 id 用）；两者上限 255（与 contact
  同一家法），超长 400。
- `ChannelController` 只在「头缺失 + 派生非空」时替换 TenantContext 的会话坐标——
  tenantId/customerId 原样保留（ADR 0025 渠道不参与身份一字未动）。
- `ChannelResponse` 加 `conversationId` 字段：调用方能看见本条消息落进哪段会话
  （点断言不影响既有 jsonPath 用例）。
- **email 适配器同规则派生**（渠道 label 不同即会话/token 不同）；web 适配器不派生。

**验证落点**：
- JVM 全量 `10 + 66 + 358 + 45 = 479` 绿（gateway 345 → 358，+13 全为本票：
  `ChannelDerivedSessionJvmTest` 10 条 + `AuthFilterTest` 补「无头 → 现生成、两次两段会话」
  现状锚 1 条 + 适配器测试上下文）。
- 新测试类 10 条覆盖验收四条 + 显式优先两条 + 长度上限 + web 零变更 + 派生辅助直测。
- **变异对照三次演练各红一次后还原**（单行 Edit 形态，Hook 要求不走 sed）：
  A. idem 选择去掉 clientToken 兜底 → `differentMessageIdsGiveDifferentIdempotencyTokens`
  红（两次 idem 都是 null，回到参数猜形态）；`sameMessageIdGivesSameIdempotencyToken`
  同红（值断言失败）。
  B1. `deriveConversationId` 只返回渠道名 → `sameChannelDifferentSessionsDiffer` 红
  （一个渠道所有人共用一段会话）。
  B2. `deriveConversationId` 只返回 chatKey → `derivedSessionIdDiffersAcrossChannelsForSameBuyer`
  红（两个平台共用 session 键——票面「第二条」的红面）。
- `WebhookCallbackUrlTest` 补身份上下文（normalize 现在依赖已验签身份派生会话——真实
  链路 AuthFilter 恒先于此，单元测试补同一形态）。

**三个现场追问**：
1. 真实 IM 渠道（票 113 飞书）接入时，`chatKey` 应取平台聊天标识（飞书单聊 `open_id`）而非
   本仓 customerId——映射表是 R3 的账，round31 的派生键里平台标识与 customerId 的取舍要在
   113 的口径里定死。
2. `sessionId`/`messageId` 是本票定义的 webhook/email payload 契约字段——真实平台的入站
   事件（飞书 `message_id`、`chat_id`）到这两个字段的映射落在适配器（97/113），夹具要钉。
3. 会话 id 现在含渠道标签（如 `app:chat:C155`）——下游有没有对会话 id 格式做假设的消费方
   （正则/前缀解析）？grep 过主链路无（SessionStore/反馈/工单都当不透明串），SSE meta 与
   日志原样透传；若未来有按会话 id 解析的消费方，格式变更要过 ADR。