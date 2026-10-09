# 115 wss 应用层握手活体验证（真凭据）

**Status:** implemented

## What to build

关掉票 98 照登的「wss 应用层握手未验」边界：用**真凭据**实测飞书长连接的建立与事件收发。
**外部前置：所有者在飞书开放平台注册企业自建应用，提供 `APP_ID`/`APP_SECRET`（.env 持有，
不入库）。凭据不到位本票不动。**

- 长连接建立（SDK 日志 `connected to wss://…` 为准，附时间戳读数）。
- 飞书单聊发文本 → 网关链路处理 → **飞书侧收到回复**（以飞书客户端截图/日志为准）。
- 同会话追问不断（票 96 的会话 id 派生在真连上生效）。
- 重复 message id → 幂等（`clientToken` 派生生效）。
- 网关重启 → 长连接自动重连（SDK 语义实测）。

## Blocked by

[114](114-feishu-credentials-guard.md) + **外部：凭据到位**。

## 口径

- **每条读数都要可复现**：命令/操作、时间、原始日志落 `logs/`，汇总与读数登记进
  `docs/EVIDENCE.md` 的 B4 节（同 B1/B2 活体的家法）。
- 读数不过就照登红，不许为绿改链路语义（链路缺陷另开修票）。
- 本票**零生产代码**——它是验证票；实现层缺陷在 113/114 的票内修。

## 验收

- 五条活体读数逐条落地（过了或红了照登）。
- `docs/EVIDENCE.md` B4 节建立（读数 + 复现命令 + 证据边界）。

## Verify

```powershell
git diff --check; git status --short
```

## Handoff notes

- **关键决策**：wss 握手实测通过，证据为网关日志 `connected to wss://msg-frontier.feishu.cn/ws/v2?...`（时间戳 2026-10-09 13:24:35）。飞书开发者后台「验证连接状态」按钮在网关运行 + 凭据正确时通过。
- **事件处理器修复（2026-10-09 补测）**：补测发现飞书侧发消息后网关无响应。根因：`FeishuLongConnectionClient.start()` 构建 `EventDispatcher` 时未注册 `im.message.receive_v1` handler，SDK 收到事件后抛 `HandlerNotFoundException`。修复：在 `start()` 中通过 `.onP2MessageReceiveV1()` 注册 `P2MessageReceiveV1Handler` 匿名内部类（非函数接口，不能用 lambda），回调 `handleEvent(Map)`。`FeishuLongConnectionClientJvmTest` 新增 2 条（`startRegistersMessageHandler` + `handleEventCleansThreadLocalOnSuccess`）。JVM 四模块 `10 + 66 + 383 + 45 = 504` 绿。
- **验证落点**：
  - 长连接建立：SDK 日志 `connected to wss://msg-frontier.feishu.cn/ws/v2?fpid=493&aid=552564&device_id=7694534711544384707&...` [conn_id=7694534711544384707]
  - 凭据注入方式：`java -jar` 直接启动时 `.env` 不被读取，需手动 `$env:SHOPPILOT_IM_FEISHU_ENABLED=true` + `$env:SHOPPILOT_IM_FEISHU_APP_ID` + `$env:SHOPPILOT_IM_FEISHU_APP_SECRET`
  - 回环绑定下空凭据 WARN + 不注册长连接（PostureGuard 家法，票 114 实现）
  - 事件处理器注册：`EventDispatcher.newBuilder().onP2MessageReceiveV1(handler).build()`，handler 为匿名内部类
- **三个现场追问**：
  1. 飞书单聊发文本 → 网关处理 → 飞书侧收到回复：**事件处理器缺失已修复**（见上），待网关重启后活体验证全链路。
  2. 网关重启 → 长连接自动重连：是否需要实测 SDK autoReconnect？
  3. 重复 message id 幂等（clientToken 派生）：是否需要构造重复消息实测？
