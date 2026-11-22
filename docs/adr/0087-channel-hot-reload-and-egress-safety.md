# 渠道配置热生效与出站边界：原子切换、SSRF 逐跳复检、跨重启幂等

Status: Accepted（2026-11-22 所有者指令开票，票 225 契约冻结后裁决）
Date: 2026-11-22
Deciders: 所有者
Round: 56（spec：`.scratch/shoppilot-mvp/round56-spec-identity-channel-hygiene.md`）

## Context

票 173 把渠道配置管理做到了「能看、能测、能保存」，但保存后的语义只有一句
`restartRequired=true`：网关不重启，新配置不生效。对运营来说这不是产品，是一次
重启工单；对安全来说，当前出站 webhook 直接 `HttpRequest.newBuilder(URI.create(target))`，
既没有 scheme/地址检查，也没有重定向复检——一条被写进工单联系方式的
`http://169.254.169.254/...` 就能让网关替攻击者访问云元数据。

同时，出站的去重表是进程内 `LinkedHashMap`（容量 1000），网关一重启就清空；
重试之间没有退避。ADR 0059 的「至少一次 → 必须幂等」只在单进程、无重启的假设下成立。

约束：

- 渠道出站适配留在网关进程内（ADR 0053 / 0059），不新开第五个服务；
- 飞书长连接使用官方 SDK 的 `Client`，`disconnect()` 是 `protected`；
- 221 的 ADMIN 专属渠道权限、现有 `ops` token 语义不得回归；
- email 仍以回执工单交付，不做真实 SMTP；不接微信/多平台。

## Decision

### 1. 保存 = 探测 → 写盘 → 原子切换；失败保留 last-known-good

`PUT /api/v1/support/ops/channels/feishu` 的状态机：

1. **探测**：启用配置必须先用候选 `appId/appSecret` 调飞书 tenant token 接口；
   探测失败直接返回 `400`，不写文件、不动当前连接。
2. **写盘**：探测通过后先把候选配置写入 `config/channels.json`（或配置的路径），
   保存的是「下一次启动也要用」的 last-known-good。
3. **切换**：新连接先启动；启动成功后关闭旧连接。旧连接关闭失败时**回滚**：
   关闭新连接、恢复旧配置文件、旧连接继续服务，返回 `restartRequired=false` 的
   `ROLLED_BACK` 状态。
4. 若运行环境禁止安全关闭旧连接（反射被模块系统拒绝、SDK 内部结构变化），
   **不启动新连接**，把新配置留在磁盘并返回 `restartRequired=true` 的
   `RESTART_REQUIRED` 状态。产品文案必须写「需重启」，不能写「已生效」。

关闭旧连接的做法被收敛在一个适配器里：反射把 SDK 的 `autoReconnect` 置 `false`，
调用 `protected disconnect()`，再 `shutdownNow()` 掉 SDK 的 executor。任何一步失败
都视为「无法安全关闭」，走上面的诚实降级。反射只碰官方 `Client` 的三个成员，
不复制 SDK 的连接逻辑；SDK 升级导致成员消失时，行为是降级而不是崩溃。

### 2. 管理面状态是一个显式枚举，不是布尔

`GET/PUT` 返回 `state`：

- `APPLIED`：配置已写盘且当前进程已使用新配置；
- `RESTART_REQUIRED`：配置已写盘，但当前进程仍用旧配置；
- `ROLLED_BACK`：候选配置探测或切换失败，已恢复 last-known-good；
- `DISABLED`：当前配置关闭连接。

`restartRequired` 只是 `state == RESTART_REQUIRED` 的派生布尔，保留给旧前端。
密钥只回遮罩；「测试连接」只探测、不保存，「保存并生效」才走状态机。

### 3. 出站 SSRF：默认拒绝，逐跳复检，显式 allowlist 是运维覆盖

新增 `EgressPolicy`，所有渠道出站（webhook 投递、管理面「测试 webhook」）必须经过它：

- 只允许 `http` / `https`；拒绝带 userinfo 的 URL；
- 每次请求前解析主机名，**所有**解析结果都必须是公网单播地址；
- 默认拒绝：loopback、RFC1918、link-local、IPv6 ULA、CGNAT（100.64/10）、
  `0.0.0.0/8`、组播、保留/基准测试网段与云元数据 `169.254.169.254`；
- `HttpClient` 关闭自动重定向；3xx 只接受 `307/308`，每次跳转重新解析并复检，
  最多 3 跳；`301/302/303` 直接拒绝（避免 POST 被改成 GET）；
- 错误对外只说「目标地址不允许」，不回显解析出的内网 IP 或 DNS 细节。

配置 `shoppilot.outbound.allowlist`（逗号分隔 host 或 host:port）是显式的运维覆盖：
命中 allowlist 的 host 可以跳过私网/回环判断，用于本机联调与内网自建 webhook；
`169.254.169.254`、组播与非法 scheme 始终拒绝，allowlist 不是绕过元数据保护的开关。

### 4. 跨重启幂等 + 指数退避

- 投递成功后写 Redis 键 `shoppilot:outbound:delivered:<eventId>`，TTL 默认 7 天；
  消费前先查该键，命中即 ack 跳过。进程内 `LinkedHashMap` 保留为 Redis 不可用时的
  第二层缓存，但**不再承担跨重启承诺**。
- 失败重试默认 3 次，退避 `base * factor^(n-1)`，带上限与 ±20% 抖动；
  base / factor / 上限 / 抖动 / TTL 全部可配置，默认 500ms / 2 / 5s / 0.2 / 7d。
- 重试耗尽后仍落 `CHANNEL_RECEIPT` 工单——这条 ADR 0059 的口径不变。
- 幂等键在「目标返回 2xx」之后写：崩溃窗口内可能重复投递一次，这是 at-least-once
  的显式取舍；接收方仍需按 `eventId` 幂等（ADR 0059 已声明）。

## Considered Options

- **保存后直接重启网关进程**：否决。网关承载聊天、鉴权与出站，重启会中断在线会话；
  且容器编排下「自己重启自己」没有可靠语义。
- **不做热切换，永远 restartRequired=true**：不满足票 225 AC1；在 SDK 可安全关闭时
  这是把可实现的能力说成不可实现。
- **复制 SDK 的连接实现或 fork SDK**：否决。维护成本与升级风险远大于三个成员的反射适配；
  失败时已有诚实的降级路径。
- **出站只做一次 DNS 检查、信任 HttpClient 自动重定向**：否决。DNS 重绑定与
  302 到私网都能绕过；必须逐跳复检。
- **幂等只靠进程内 Map**：否决。重启即失效，正是本票要修的缺口。
- **重试固定间隔**：否决。下游恢复需要时间，固定间隔会把恢复窗口打成雪崩；
  退避 + 抖动是低成本的最小防线。

## Consequences

- 渠道配置保存从「写文件 + 提示重启」变成一次有状态的操作；运营能看到
  `APPLIED / RESTART_REQUIRED / ROLLED_BACK / DISABLED`，不再靠猜。
- 网关出站多一次 DNS 解析与逐跳校验；这是出站路径，不在聊天热路径上。
- Redis 成为跨重启幂等的必要条件；Redis 不可用时降级为进程内去重并在日志/指标中
  可见，不阻塞工单回流。
- 反射适配依赖飞书 SDK 2.4.0 的三个成员；升级 SDK 时若成员改名，行为降级为
  `RESTART_REQUIRED`，不会静默双连接。
- allowlist 是运维权限，能扩大出站面；默认空，配置变更需与 `config/channels.json`
  同级的运维审计。
- 仍不做真实 SMTP、微信/多平台接入；不改退款/工单/知识规则与 221 的权限语义。
