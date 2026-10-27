# 173 渠道连接管理 v1：飞书单连接

**Status:** implemented（2026-10-28）

**Claimed by:** zcode session 2026-10-28

> L2 已能接飞书，但接下一个渠道仍要手改 .env、重启、靠日志判断。第一版先把飞书连接变成可查看、可测试、可保存的配置。

## 目标

- 连接配置从 .env 提升为 `config/channels.json`（gitignore，含密钥）；
- 无文件时回退 .env，旧部署不变；
- 提供 ops API：
  - `GET /api/v1/support/ops/channels`：状态、遮罩 appId、租户、来源、是否需重启；
  - `POST /api/v1/support/ops/channels/feishu/test`：用给定或当前凭据换取 tenant_access_token，只返回 ok/错误；
  - `PUT /api/v1/support/ops/channels/feishu`：保存 enabled/appId/secret/tenant/customer/identityFile；
- 保存后返回 `restartRequired=true`，不假装热重连；
- 启动时优先读文件，文件坏/缺字段拒启。

## 验收

- 无文件：current 来自 .env；
- 有文件：current 来自文件，appId 遮罩，secret 不回显；
- 保存缺 appId/secret（enabled=true）拒绝；
- 测试成功/失败都有明确消息；
- 旧飞书 JVM 测试不回退。

## Non-goals

- 热重启 SDK 长连接；
- 多渠道多实例；
- 数据库/密钥管理服务；
- 前端管理页（下一票）。

## 已实现

- ChannelConnectionStore：`config/channels.json` 优先，缺文件回退 .env；保存后写文件并标记 restartRequired；
- FeishuLongConnectionClient / FeishuIdentityResolver 启动时读 store，保存后重启即生效；
- ChannelAdminController：GET 状态、POST /feishu/test、PUT /feishu；secret 不回显；
- config/channels.example.json、docs/CHANNEL_CONNECTIONS.md；
- .gitignore 忽略实际 channels.json。

## 验证

- ChannelConnectionStoreTest 5 条：env 回退、保存/重载、缺凭据拒绝、无凭据测试、坏文件拒启；
- 飞书相关测试与 ConfigValidationTest 通过；
- 全量 verify：720 tests / 120 suites 全绿；
- 未做：HTTP 端点活体、前端管理页。

## Handoff notes

**关键决策**

1. 单节点文件配置，不先上数据库/密钥服务；secret 落本机文件并 gitignore；
2. 保存后只标记 restartRequired，不热重启——SDK 关闭语义不公开，双连接会重复收事件；
3. 空 appSecret 保存时保留旧值，避免管理页只改 enabled 时把密钥清空；
4. 状态接口只回遮罩 appId，不回 secret。

**验证落点**

- ChannelConnectionStoreTest；
- 全量 logs/verify173.log；
- 配置样例 config/channels.example.json。

**三个现场追问**

1. 为什么不热重启？飞书 SDK 的 disconnect 是 protected，进程内重建可能留下两个 WebSocket；v1 选择保存 + 重启。
2. 为什么密钥放文件？L2 单节点先可用；生产要接 KMS/Secret Manager，届时只换 store 实现。
3. 怎么证明生效？保存后 GET 看到 source=file、restartRequired=true；重启后日志出现连接启动，且 tenant 为新值。
