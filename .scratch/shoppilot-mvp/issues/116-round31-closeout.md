# 116 round31 收口

**Status:** ready-for-agent

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

（收口时补）
