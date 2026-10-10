# 116 round31 收口

**Status:** implemented

## What to build

round31（B4「飞书长连接真连」）收口登记。

- `docs/EVIDENCE.md`：B4 节（录放门读数 + 票 115 活体读数或其未验边界照登）。
- `docs/CODE_MAP.md`：飞书长连接客户端落点、适配器链路更新。
- `CONTEXT.md`：若产生新术语（如「长连接客户端」「平台事件归一」）按需补；不改既有术语。
- `README.md`：已知限制补飞书真连的覆盖面与边界（单聊文本、集群语义、断线丢失照登）。
- tracker：round31 条目 + Round 表 + 票索引 + 读数换代（以本轮实测为准）。
- `program-a-to-b-upgrade.md`：B4 换代指针。
- **对外表述**（票 99 定死的口径）：wss 应用层握手实测通过之前只能说「适配器契约可复现」；
  通过之后说「飞书真连已验证」并附 EVIDENCE 落点，**不说「已接入飞书」**。

## Blocked by

[96](96-inbound-contract-session-and-client-token.md)、[97](97-im-adapter-and-replay-gate.md)、
[113](113-feishu-longconnection-client.md)、[114](114-feishu-credentials-guard.md)、
[115](115-feishu-wss-handshake-live.md)。
**注意**：若票 115 因凭据未到位而无法执行，本票按「B4 除活体一格外全部落地」收口，
未验边界照登——**不许**把「未实跑」写成「已验证」。

## 口径

- 判据面零改动；CI 九步不变。
- 读数换代以本轮实测为准。

## Verify

```powershell
git diff --check; git status --short
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

- **关键决策**：B4「飞书长连接真连」全部落地，round31 六票（96/97/113-116）均已收口。wss 应用层握手实测通过（2026-10-09 13:24:35），证据为网关日志 `connected to wss://msg-frontier.feishu.cn/ws/v2?...`。按票 99 口径，对外只能说「飞书真连已验证」，不说「已接入飞书」。
- **验证落点**：
  - wss 握手：`logs/gateway.log` 13:24:35 行，`conn_id=7694534711544384707`
  - 事件收发全链路：`logs/gateway-restart3.log` 21:12:11 行 + 飞书客户端收到回复「已为您转接人工客服」（用户确认）
  - 重启自动重连：`logs/gateway-restart4.log`（`conn_id=7694665443777104883`）、`logs/gateway-restart5.log`（`conn_id=7694665806546111425`）——**验的是应用层重启后 `@PostConstruct` 重新建连，不是 SDK `autoReconnect` 语义**（后者未实测）
  - 凭据治理：PostureGuard 家法落地（票 114），回环空凭据 WARN + 不注册，非回环空凭据拒启
  - 凭据注入：`java -jar` 直接启动时 `.env` 不被读取，需手动 `$env:` 设置三个飞书环境变量
  - 飞书开发者后台「验证连接状态」按钮在网关运行 + 凭据正确时通过
- **收口期修掉的两个真缺陷**（票 113 续，无新票号，落点 `docs/EVIDENCE.md`）：
  1. `EventDispatcher.newBuilder().build()` 未注册任何 handler，飞书发消息进来 SDK 抛 `HandlerNotFoundException` → `start()` 里 `.onP2MessageReceiveV1()` 注册；
  2. `ObjectMapper.convertValue` 默认按驼峰 JavaBean 名序列化，而 `FeishuAdapter.normalize()` 要飞书蛇形 JSON（`message_id`）→ 换 `SNAKE_CASE` 专用 mapper。
  - 另注册 `bot_p2p_chat_entered_v1` / `message_read_v1` 空处理器消 ERROR 噪音。
- **读数**：JVM 四模块 **`10 + 67 + 383 + 45 = 505`** 全绿。
  **本票初版写的 `5+10+57+342+39=453` 是错的，两处**：① 前面多写了一个 `5`（四模块是四个数，这是 2026-10-05 收口日就记下的老毛病复发）；② 数字本身是 2026-10-09 13:24 的旧落点，没跟上票 113/114 与收口期补测。**现行读数以 505 为准**，逐模块重算依据见 `docs/EVIDENCE.md`；本轮复跑 verify 后确认 biz-mock 实为 **67**（round30 记账的 66 是记账误差，源码未动）。
- **三个现场追问**：
  1. SDK `autoReconnect` 语义（同进程内 WebSocket 断开后自动重连）本机无法模拟网络断开，是否要在有网络注入能力的环境里补测？
  2. 重复 message id 幂等（`clientToken` 派生）已由票 96 的 JVM 用例覆盖派生逻辑，但**没有真机上重复投递的活体**；要不要构造重复消息实测？
  3. 多 client 集群语义（飞书同一应用多 client 时消息随机落一个，非广播）只登记未验——多实例部署前要不要先做这一格？
