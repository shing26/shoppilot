# 票 228：验收断言补强——activeAppId 与多优先级队列

Round: 58（验收入口补强；按 ADR 0031）　
ADR: 0087（activeAppId 运行态语义；不改结论）　
Status: implemented（2026-11-23；独立 Verifier PASS，待所有者签收）　Claimed by: root　Blocked by: 无

契约：[plans/228-verification-assertions.md](../../plans/228-verification-assertions.md)（2026-11-23 冻结）

## What to build

1. `verify-channel-hot-reload.mjs`：activeAppId 改为与已知候选 appId 的掩码比较，
   PUT 响应与后续 GET 状态都断；旧/新掩码不同时断旧值不再活动；启动/停止日志双计数。
2. `verify-workspace.mjs`：通过工单服务真实创建 high / money 两张种子单，
   断言落单成功、队列可见、三张种子单按 紧急 → 资金 → 普通 排序；
   全局排序断言不再走 WARN 分支。
3. 种子失败必须 FAIL，不得降级为 WARN；既有断言不删除、不弱化。

## 边界

- 只改两个验收脚本；不改 Java、Vue、生产配置、数据库、API、gold、阈值或覆盖率门槛。
- 种子走真实工单服务 `POST /api/tickets`，不直接写库、不伪造 DOM。
- activeAppId 仍是掩码；旧/新掩码相同时用连接启动/停止日志补证，并登记边界。

## Verify

```powershell
node --check scripts/verify-channel-hot-reload.mjs
node --check scripts/verify-workspace.mjs
.\mvnw.cmd -B -ntp verify
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
python scripts/check_doc_links.py
git diff --check
git status --short
```

活体（本机栈可用时）：

```powershell
$env:SHOPPILOT_WORKSPACE_BASE = "http://127.0.0.1:18082"
$env:SHOPPILOT_TICKET_BASE = "http://127.0.0.1:8092"
$env:SHOPPILOT_IDENTITY_DEMO_PASSWORD = "<演示口令>"
$env:SHOPPILOT_INTERNAL_TOKEN = "<内部令牌>"
node scripts/verify-workspace.mjs
node scripts/verify-channel-hot-reload.mjs
```

## Handoff notes

**关键决策**

- activeAppId 期望值来自脚本已知候选 appId 的 `maskAppId()`（前 6 位 + `****`）：
  PUT 响应的 `activeAppId`、PUT 响应的 `appId`、后续 GET 状态的 `activeAppId`
  三处共用同一期望，不再拿响应里的另一个掩码字段自比。
- 热切换成功的真实日志是 `飞书长连接已启动（热切换），旧连接已停止`；
  `飞书长连接已停止` 只在显式停用/停机路径出现。停止证据改数 `旧连接已停止`，
  且仅在切换前存在活动连接时判，无旧连接打 WARN。首轮活体用错片段得到 0→0 假红，
  已在契约 AC3 留下修正记录；判据强度不变（启动/停止两条证据都增加）。
- 工单种子走真实 `POST /api/tickets`（`X-Internal-Token` + `X-Tenant-Id`）：
  high 用 `DEGRADE/EMOTION_ESCALATION`，money 用 `REFUND_APPROVAL`；排序读前端
  渲染出的 `.priority` 与种子工单号，不按队列名反推优先级。
- 队列默认 `ALL` 时后端按创建时间倒序，实际呈现顺序由前端 `filterTickets`
  按 `priorityRank` 排序；断言覆盖的是产品实际呈现顺序（契约现场追问 3）。
- 热切换脚本新增本地端口预检：19091 曾被 06:05 遗留的 `r3-receiver.mjs` 占用，
  网关按 IPv4 命中残留进程，导致 307 断言假红/假绿；现在端口被占用会明确失败。

**验证落点**

- 静态：`node --check` 两脚本通过；`.\mvnw.cmd -B -ntp verify` **BUILD SUCCESS**
  （约 5:03；Java 未改，跑于脚本改动前，最终 HEAD 由 Verifier 亲跑）；`STATUS ok`、
  `verify_doc_truth` 878 tests / 144 suites、`check_doc_links` 119 文件 / 234 链接、
  `git diff --check` 退出 0。
- 活体（8082/8091/8092 + Redis 16379）：
  `verify-workspace.mjs` **30/30 PASS**，新增 high/money 落单、队列可见、
  三张种子单 `紧急=0 资金=1 普通=2` 全绿；
  `verify-channel-hot-reload.mjs` **30/30 PASS**，activeAppId 三处独立期望、
  旧连接停止日志 0→1、重启幂等、指数退避与回执工单全绿。
- 渠道套件前置：`SHOPPILOT_OUTBOUND_ALLOWLIST=localhost` 与
  `SHOPPILOT_OUTBOUND_POLL_INTERVAL=1s` 必须在网关**启动时**生效；本次临时写入
  `.env`，跑完已原样恢复并重启网关。

**三个现场追问**

1. 旧/新 appId 掩码相同（本次 `.env` 前后是同一 appId）时 activeAppId 无法区分
   连接切换，已用启动/停止日志补证；要更强的连接代号需管理面 API 变更，另票。
2. 高/资金种子走工单服务直连，需要 `SHOPPILOT_INTERNAL_TOKEN` 与可达的 8092；
   验收环境不暴露 8092 时需网关新增创建端点（API 变更，另票）。
3. **P1（票外，建议单独开票）**：`scripts/lib-launch.ps1` 经 WMI 脱离启动服务，
   子进程不继承调用方环境；而 `Get-ShoppilotDotEnv` 把「调用方已存在的同名变量」
   视为已注入并跳过。结果：从已导出 `SHOPPILOT_*` 的进程调用
   `start-gateway.ps1` 时，脱离的网关拿不到 `.env` 的内部令牌（实测登录 401、
   SSRF allowlist 不生效），日志只留默认凭证 WARN。本票绕行是只导出脚本自身
   需要的 `SHOPPILOT_IDENTITY_DEMO_PASSWORD`；根因修复应在 `lib-launch.ps1`
   或启动器，不在本票 allowed paths。

**独立 Verifier PASS（2026-11-23，HEAD `4b0ca71`）**

- 静态：两条 `node --check` 退出 0；`.\mvnw.cmd -B -ntp verify` BUILD SUCCESS
  （878 tests / 144 suites，0 skipped）；`gen_status --check`、`verify_doc_truth`、
  `check_doc_links`（119 文件 / 234 链接）、`git diff --check` 全退出 0；
  `git status --short` 为空。
- 活体 workspace：**30/30 PASS**；high/money 种子均 HTTP 201 且 priority 正确，
  三张种子单相对顺序 `紧急=0 资金=2 普通=4`（队列残留 11 张历史单，绝对下标不同于
  干净库，但 high→money→normal 与全局非降序成立）。
- 活体 channel：**30/30 PASS**（复跑）。首轮因飞书 443 瞬时 `HttpConnectTimeoutException`
  未触发热切换（27/30，AC3 未覆盖）；网络恢复后同一请求 `200 APPLIED`，
  启动日志 1→2、旧连接停止日志 0→1，307 逐跳拒绝、重启幂等、退避 579/1018ms、
  `CHANNEL_RECEIPT` 回执工单全部 PASS。首轮 FAIL 与复跑证据均保留在 Verifier 报告。
- AC1–AC8 全过；P0 无。AC2 异掩码分支与 AC6 失败路径仅静态审查（未活体触发），
  未发现缺陷。
- 票外 P1 经 Verifier 独立确认：`lib-launch.ps1` 的 WMI 环境继承缺陷成立，
  不影响本票 AC，建议另票。未合并、未签收。
