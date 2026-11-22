# 票 225：渠道配置热生效与出站安全

Round: 56（所有者 2026-11-22 指令；按 224 → 225 → 226 顺序）
ADR: 0087（实现前新增并 Accepted；开票依据 = 所有者政策覆盖）
Status: implemented（待独立 Verifier）　Claimed by: implementer-225　Blocked by: 无（按所有者指定顺序排在 224 后）
契约：[plans/225-channel-hot-reload-and-egress-safety.md](../../../plans/225-channel-hot-reload-and-egress-safety.md)（2026-11-22 冻结）

## What to build

把「保存渠道配置后重启网关」升级为可安全生效、失败可回滚，并补齐出站边界：

1. 配置保存 → 探测 → 原子切换；成功时旧飞书长连接停止、新连接接管，不重复消费事件；
   失败时保留 last-known-good 并返回明确错误。
2. `restartRequired` 只在确实无法热切换时为 true；管理面区分
   「已保存 / 已生效 / 需重启 / 失败回滚」。
3. 出站 SSRF 防护：只允许 http/https；拒绝 loopback、私网、link-local、云元数据地址；
   DNS 重解析与重定向逐跳复检；支持显式 allowlist。
4. 投递幂等与重试：去重键落 Redis/DB，跨网关重启仍生效；指数退避 + 抖动；
   超过上限落 `CHANNEL_RECEIPT` 工单。
5. 管理面：密钥只显示掩码；「测试连接」与「保存并生效」是两个可验证动作。

## 边界

- 不做真实 SMTP 发信；email 继续以回执工单交付。
- 不做微信/多平台接入；只处理飞书与 webhook 出站。
- 不改退款、工单、知识业务规则；不改 221 的 ADMIN 专属渠道权限。
- 不假装热生效：SDK 无法安全关闭旧连接时，必须降级为「需重启」并留证据。

## Verify

```powershell
.\mvnw.cmd -B -ntp -pl shoppilot-gateway -am test
.\mvnw.cmd -B -ntp verify
cd frontend-workspace; npm run typecheck; npm test; npm run build
cd ..
node scripts/verify-channel-hot-reload.mjs
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
git diff --check
git status --short
```

活体脚本至少覆盖：保存合法配置 → 不重启生效 → 新连接收到事件/旧连接不再收；
保存非法配置 → 回滚且旧连接仍可用；私网/元数据/重定向 SSRF 被拒；
同一事件在网关重启后只投递一次；重试间隔递增。

## 验收项

- 合法配置无需重启即可生效；旧连接停止，新连接接管，事件不重复。
- 非法配置返回明确错误并回滚；last-known-good 继续工作；`restartRequired` 与实际一致。
- SSRF：loopback、RFC1918、link-local、`169.254.169.254`、IPv6 loopback 与重定向到私网均被拒；
  allowlist 内公网地址可投递。
- 去重跨进程重启仍有效；重试间隔递增且有上限；耗尽后落回执工单。
- 管理面不泄露密钥；状态文案与实际生效状态一致。
- JVM、坐席前端 typecheck/vitest/build、全仓 verify 与活体脚本全绿。

## Handoff notes

**关键决策**

- ADR 0087（Accepted）冻结三件事：连接生命周期、SSRF 判定、重试上限。保存配置走
  「探测 → 写盘 → 切换」；探测或启动失败回滚 last-known-good 并返回 `ROLLED_BACK`，
  停不掉旧连接才返回 `RESTART_REQUIRED`，不把「试试看」包装成热生效。
- `EgressPolicy` 每次请求（含 307/308 每一跳，最多 3 跳）重做 scheme/userinfo/DNS
  全地址判定；loopback、RFC1918、link-local、云元数据、IPv6 loopback 默认拒绝；
  `allowlist` 只覆盖回环/私网，元数据地址始终拒绝。301/302/303 直接拒绝，避免
  方法被改写后绕过判定。
- 幂等键落 Redis `shoppilot:outbound:delivered:<eventId>`（TTL 默认 7d），进程内 LRU
  只作 Redis 短暂不可用时的兜底；重试默认 3 次、500ms 起、×2、上限 5s、±20% 抖动，
  参数全部走 `shoppilot.outbound.*`。
- 飞书 SDK 没有公开的安全关闭语义，用反射依次 `autoReconnect=false` →
  `disconnect()` → `executor.shutdownNow()`；任一失败即 `NOT_SUPPORTED`，由上层
  标记「需重启」，不假装切换成功。
- 出站事件新增 `customerId`：消费线程没有 `TenantContext`，活体实测回执工单曾因
  「缺少已验签身份」落不下去；身份随事件携带后，重试耗尽仍能落 `CHANNEL_RECEIPT`。

**验证落点**

- HEAD `db8540d`（本票实现提交），分支 `codex/225-channel-hot-reload-and-egress-safety`。
- 全仓 `mvnw -B -ntp verify -Dsurefire.forkCount=1 -Dspring-boot.repackage.skip=true`：
  863 tests / 143 suites（tool-api 12 + biz-mock 98 + gateway 703 + ticket 50），0 失败。
  `repackage.skip` 仅因本机运行中的网关占用 fat jar；`mvnw -pl shoppilot-gateway -am
  package -DskipTests` 随后单独重打包成功。
- 前端 `npm run typecheck`、`npm test`（15/15）、`npm run build` 全绿；产物
  `index-DMktD0q7.js` / `index-Cuhbh1n7.css` 已入库。
- 活体 `node scripts/verify-channel-hot-reload.mjs` **22/22**：管理面状态/掩码、
  五类 SSRF 拒绝 + allowlist 放行 + 307 跳私网拒绝、非法配置 409 `ROLLED_BACK`、
  合法配置 `APPLIED` 且旧连接停止/新连接启动有日志、Redis 幂等键、重启后同 eventId
  只投递一次、重试 3 次间隔递增、耗尽落 `CHANNEL_RECEIPT`。本机日志
  `logs/verify-225-live-final.log`（不入库）。
- `python scripts/gen_status.py --check`、`verify_doc_truth.py`、`check_coverage.py`、
  `pwsh scripts/check-ps-syntax.ps1`、`git diff --check` 全绿；活体临时写入的
  `config/channels.json` 已还原删除，`.env` 临时 `OUTBOUND_ALLOWLIST/POLL_INTERVAL`
  已恢复。
- 既有 221 的 ADMIN 渠道权限未改：`ChannelAdminController` 仍要求 ADMIN，活体
  管理面请求全部走 ADMIN 令牌。

**三个现场追问**

1. 飞书 SDK 升级若把 `autoReconnect`/`executor` 字段改名，反射关闭会退化为
   `RESTART_REQUIRED`。是否接受「需重启」作为长期产品状态，还是要求锁定 SDK 版本
   并在升级时补适配？
2. 出站 allowlist 目前只由环境变量维护，默认空（全拒）。生产上由谁审批、是否需要在
   管理面做带审计的增删？
3. Redis 不可用时只有进程内 LRU 兜底，跨重启幂等会退化。是否接受这一降级窗口，
   还是要求 Redis 挂掉时暂停出站并告警？
