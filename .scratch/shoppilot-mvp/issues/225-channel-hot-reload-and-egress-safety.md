# 票 225：渠道配置热生效与出站安全

Round: 56（所有者 2026-11-22 指令；按 224 → 225 → 226 顺序）
ADR: 0087（实现前新增并 Accepted；开票依据 = 所有者政策覆盖）
Status: done（2026-11-23 所有者书面签收）　Claimed by: implementer-225　Blocked by: 无（按所有者指定顺序排在 224 后）
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

- 首轮实现 HEAD `db8540d`，分支 `codex/225-channel-hot-reload-and-egress-safety`；
  独立 Verifier 首轮 **FAIL**（报告 `.scratch/shoppilot-mvp/issues/verifier-225-report.md`），
  整改后待复验。
- 全仓 `mvnw -B -ntp verify -Dsurefire.forkCount=1 -Dspring-boot.repackage.skip=true`：
  863 tests / 143 suites（首轮）；两轮整改后 `mvnw -B -ntp verify -Dsurefire.forkCount=1`
  **870 tests / 143 suites**（tool-api 12 + biz-mock 98 + gateway 710 + ticket 50），
  0 失败，且**不再需要** `repackage.skip`（先停网关再 verify，repackage 成功）。
- 前端 `npm run typecheck`、`npm test`（15/15）、`npm run build` 全绿；产物
  `index-DMktD0q7.js` / `index-Cuhbh1n7.css` 已入库。
- 活体 `node scripts/verify-channel-hot-reload.mjs` 整改后 **27/27**：管理面状态/掩码、
  九类 SSRF 拒绝（含 IPv4 TEST-NET 与 IPv6 `2001:db8::/32`）+ allowlist 放行 +
  307 跳私网拒绝、非法配置 409 `ROLLED_BACK`、合法配置 `APPLIED` 且旧连接停止/
  新连接启动有日志、`activeAppId` 与实际配置一致、Redis 幂等键、重启后同 eventId
  只投递一次、重试 3 次间隔递增、耗尽落 `CHANNEL_RECEIPT`。本机日志
  `logs/verify-225-live-r4.log`（不入库）。
- `python scripts/gen_status.py --check`、`verify_doc_truth.py`、`check_coverage.py`、
  `pwsh scripts/check-ps-syntax.ps1`、`git diff --check` 全绿；活体临时写入的
  `config/channels.json` 已还原删除，`.env` 临时 `OUTBOUND_ALLOWLIST/POLL_INTERVAL`
  已恢复。
- 既有 221 的 ADMIN 渠道权限未改：`ChannelAdminController` 仍要求 ADMIN，活体
  管理面请求全部走 ADMIN 令牌。

**首轮 Verifier FAIL 的整改（2026-11-23）**

- P0：旧连接与新连接同时关闭失败时，原实现返回 `FAILED`，上层按「新连接启动失败」
  回滚并标记 `ROLLED_BACK`，可能留下两条长连接且隐藏回滚失败。现在新增
  `ManagedFeishuConnection.canStopSafely()` 预检：反射成员缺失时**不启动新连接**，
  直接 `RESTART_REQUIRED`；若运行期两边仍都停不掉，返回 `UNSAFE`，上层保留新配置、
  标记 `RESTART_REQUIRED` 并明确提示「可能仍有两条长连接，立即重启」，不再谎称回滚。
  新增对称失败与预检测试。
- P1：启动失败或回环空凭据时，管理面回落 `RESTART_REQUIRED`，不再停在 `APPLIED`。
- P1：回执工单写入失败时事件**不 ack**，保留 pending 等下一轮重投，结论不再丢；
  新增 `receiptFailureKeepsEventPending` 断言 ack 从未发生。
- P1：PUT/GET 的 `activeAppId` 改为实际活动连接（`activeFeishu`），
  `RESTART_REQUIRED` 时不会把磁盘新配置冒充成正在运行。
- P1：`EgressPolicy` 补齐 RFC 5737 三个 IPv4 TEST-NET 与 RFC 3849
  `2001:db8::/32` 默认拒绝，单元与活体各加断言。
- P1（路径）：在冻结契约追加「2026-11-23 契约补记」，把 `docs/TUNING_PLAN.md`、
  `frontend-workspace/src/App.vue`、`static/workspace/**` 与 `src/test/**` 补入
  Allowed paths；AC 与阈值零改动。**这是冻结后补记，待所有者书面追认**；追认前
  只能记为已披露的流程偏差，不能声称路径合规已闭环。

**第二轮 Verifier FAIL 的整改（2026-11-23）**

- P0：`EmailReceiptWriter.writeReceipt` 的失败形态是 `Optional.empty()`，不是异常；
  `landReceipt` 现在把 `null/empty` 都当失败，不 ack、不写 delivered 键。
  `OutboundDeliveryTest` 改为覆盖真实 `Optional.empty()` 路径，并保留异常路径。
- P0：`drain()` 原先只读 `ReadOffset.lastConsumed()`（`>`），上一轮未 ack 的事件会
  永久卡在 PEL。现在每轮先读 `ReadOffset.from("0")`（本消费者 PEL），再按剩余额度读
  `>`；新增 `pendingEntriesAreReadBeforeNewOnes` 断言读取顺序。
- 独立活体补证：停网关 → 用 Redis 把一条 email 事件以 consumer `gateway` 读成
  pending（`XPENDING=1`）→ 启动网关等 20s → `XPENDING=0` 且
  `shoppilot:outbound:delivered:ev-pel-1795384607849=1`，证明重启后 PEL 会重投。

**第三轮 Verifier PASS（2026-11-23，HEAD `a85496d`）**

- 窄测 58/58；全仓 **870 tests / 143 suites**（gateway 710 + biz-mock 98 +
  ticket 50 + tool-api 12）；前端 typecheck + vitest 15/15 + build；活体
  `verify-channel-hot-reload.mjs` **27/27**；文档四项与 `git diff --check` 全绿。
  Verifier 先停旧 jar 网关再 `verify`，确认 repackage 可过且活体跑新构建。
- P0-A 独立攻击：停 8092 后注入 email 事件 → 日志 ERROR「回执工单未落库」、
  `XPENDING=64`、delivered 键不存在；ticket 恢复后 `XPENDING=0`、delivered 键出现、
  结论落库。P0-B：64 条 pending 启动后清空；70 条（>BATCH 64）分两轮清空且每条
  只投一次；3 条 PEL + 3 条新消息同轮各一次，无重复无死循环。
- AC1–AC10 全 PASS；`72a4cd5..a85496d` 共 35 个路径全部落在补记后的 Allowed 内。
- **新登记 P1（不阻塞，另票处理）**：①跨轮重投无退避，ticket 长期不可用时每条约
  每 6 秒重跑一轮（2 分钟约 18 次、1088 条 ERROR），失败指标同步放大；②PEL 恰好
  占满 BATCH=64 时新消息要等下一轮，存在队头阻塞；③`activeAppId` 活体断言仍是
  掩码对掩码，区分力弱；未注入真实飞书事件观察切换窗口。触发条件：渠道出站进入
  真实流量或 ticket 服务出现分钟级不可用。
- **流程偏差（需签收时确认）**：`plans/225` 为补路径在冻结后追加补记，文件自述
  「待所有者书面追认」；AC/阈值未改，但不应把补记本身说成已追认。

**三个现场追问**

1. 飞书 SDK 升级若把 `autoReconnect`/`executor` 字段改名，反射关闭会退化为
   `RESTART_REQUIRED`。是否接受「需重启」作为长期产品状态，还是要求锁定 SDK 版本
   并在升级时补适配？
2. 出站 allowlist 目前只由环境变量维护，默认空（全拒）。生产上由谁审批、是否需要在
   管理面做带审计的增删？
3. Redis 不可用时只有进程内 LRU 兜底，跨重启幂等会退化。是否接受这一降级窗口，
   还是要求 Redis 挂掉时暂停出站并告警？

**所有者签收（2026-11-23）**

- 所有者对话内书面指令「签收」。签收范围：AC1–10、三轮独立 Verifier 复验 PASS、
  全仓 870/143、前端 15/15、活体 27/27、64/70 条 PEL 重启重投实测。
- 同时追认 `plans/225` 的冻结后路径补记（TUNING_PLAN、App.vue、static/workspace、
  src/test）；AC、阈值与证据结论未改。
- 三项 P1（跨轮无退避、PEL 满 BATCH 队头阻塞、activeAppId 活体断言区分力弱）
  按票面登记的触发条件留作后续票，不阻塞本次签收。
