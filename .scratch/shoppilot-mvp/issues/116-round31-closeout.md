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

- **关键决策**：B4「飞书长连接真连」全部落地。wss 应用层握手实测通过（2026-10-09 13:24:35），证据为网关日志 `connected to wss://msg-frontier.feishu.cn/ws/v2?...`。按票 99 口径，对外只能说「飞书真连已验证」，不说「已接入飞书」。
- **验证落点**：
  - wss 握手：`logs/gateway.log` 13:24:35 行，`conn_id=7694534711544384707`
  - 凭据治理：PostureGuard 家法落地（票 114），回环空凭据 WARN + 不注册，非回环空凭据拒启
  - 凭据注入：`java -jar` 直接启动时 `.env` 不被读取，需手动 `$env:` 设置三个飞书环境变量
  - 飞书开发者后台「验证连接状态」按钮在网关运行 + 凭据正确时通过
- **读数**：JVM 5+10+57+342+39=453（票 114 新增 7 个 PostureGuard 测试，票 113 新增 10 个，共 17 个新测试）
- **三个现场追问**：
  1. 事件收发全链路（飞书单聊发文本 → 网关处理 → 飞书侧收到回复）是否需要在真机上完整走通？
  2. 网关重启 → 长连接自动重连（SDK autoReconnect）是否需要实测？
  3. 重复 message id 幂等（clientToken 派生）是否需要构造重复消息实测？
