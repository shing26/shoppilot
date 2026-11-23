# 票 228 契约：验收断言补强——activeAppId 与多优先级队列

- Round: 58
- 类型: 验收入口补强（不新增产品面，不改生产代码）
- 开票依据: 所有者 2026-11-23 指令「做 225-3 activeAppId 断言、223-1 多优先级队列活体种子」；
  票 225 登记的 P1「activeAppId 掩码对掩码、区分力弱」与票 223 登记的
  「队列只有普通优先级、排序断言未覆盖」。
- ADR: 0031（回归与现有门禁修复不受冻结限制）；ADR 0087（activeAppId 运行态语义）。
- 冻结时间: 2026-11-23
- Implementer: root（独立分支 `codex/228-verification-assertions`）
- Verifier: 独立 subagent；先语法/静态检查，再尝试活体；命令由 Verifier 亲跑

## 问题

1. `scripts/verify-channel-hot-reload.mjs` 的 activeAppId 断言是
   `applied.body.activeAppId === applied.body.appId`：同一个响应里的两个掩码值互比，
   两个 appId 前 6 位相同就会假绿，也没有证明旧连接已停止。
2. `scripts/verify-workspace.mjs` 只通过买家「转人工」造出一张普通优先级工单，
   队列里没有 high/money，排序断言只打 WARN，不构成通过。

## 目标

- **225-3**：activeAppId 与**已知候选 appId 的掩码**比较（PUT 响应 + 后续 GET 状态），
  不再拿响应里的另一个掩码字段自比；旧/新掩码不同时断言旧值不再活动；
  同时断「新连接已启动」与「旧连接已停止」两条日志计数都增加。
- **223-1**：通过真实工单服务 `POST /api/tickets` 造 high（`DEGRADE` /
  `EMOTION_ESCALATION`）与 money（`REFUND_APPROVAL`）两张单，断言返回 201 且
  `priority` 字面量正确；队列里三张种子单按 紧急 → 资金 → 普通 排序，
  全局非降序断言真正执行并通过。
- **不假绿**：种子落单失败必须是 FAIL，不得降级为 WARN；排序断言只在种子成功后判覆盖。

## Allowed paths

- `plans/228-verification-assertions.md`
- `.scratch/shoppilot-mvp/issues/228-verification-assertions.md`
- `.scratch/shoppilot-mvp/README.md`
- `docs/EVIDENCE.md`
- `scripts/verify-channel-hot-reload.mjs`
- `scripts/verify-workspace.mjs`

## Acceptance criteria

- **AC1 activeAppId 独立期望值**：PUT 响应的 `activeAppId` 与 `appId` 都等于
  `mask(候选 appId)`；后续 `GET /api/v1/support/ops/channels` 的 `activeAppId`
  也等于同一期望值。断言不再读取自身 `appId` 作为期望。
- **AC2 旧连接不再活动**：若 `mask(旧 appId) !== mask(候选 appId)`，
  断言 `activeAppId !== mask(旧 appId)`；若两者掩码相同，打印 WARN 并明确
  「无法用掩码区分，以连接停止/启动日志为准」，不得静默跳过。
- **AC3 连接生命周期日志**：热切换前后 `飞书长连接已启动` 与
  `飞书长连接已停止` 两个计数都增加；任一未增加判 FAIL（日志文件不存在时按原脚本
  的 `-1` 语义登记为环境边界，不能写 PASS）。
  - **活体修正（2026-11-23）**：热切换成功实际打印
    `飞书长连接已启动（热切换），旧连接已停止`；`飞书长连接已停止` 只在显式停用/停机
    路径出现。首轮活体因此得到「停止日志 0 -> 0」的假红。断言改为数 `旧连接已停止`，
    且仅在切换前存在活动连接时判；无旧连接打 WARN。判据强度不变：仍要求启动与停止
    两条证据都增加，日志不可读仍按环境边界登记。
- **AC4 高/资金种子真实落单**：`POST {TICKET_BASE}/api/tickets` 带
  `X-Internal-Token` 与 `X-Tenant-Id`，high 单 `source=DEGRADE`、
  `reason=EMOTION_ESCALATION`，money 单 `source=REFUND_APPROVAL`；
  两者 HTTP 201 且响应 `priority` 分别为 `high` / `money`。
- **AC5 队列排序真的覆盖**：刷新后队列同时包含 high、money、转人工 normal 三张种子单；
  三者的可见顺序为 high → money → normal；全局优先级序列非降序，且不再走
  「只有一种优先级」的 WARN 分支。
- **AC6 失败不降级**：任一种子落单失败、任一优先级不符、任一 seeded id 不在队列，
  对应 check 判 FAIL；排序断言不得在失败后仍以 WARN 收尾。
- **AC7 既有断言不回归**：买家/坐席登录、转人工落单、领取、结单对话框、
  知识飞轮、出站 SSRF/幂等/PEL 等既有 check 名称与判据不被删除或弱化。
- **AC8 语法与文档**：`node --check` 两个脚本通过；`mvnw verify` 全绿；
  `gen_status --check` / `verify_doc_truth` / `check_doc_links` / `git diff --check`
  全绿；测试数不变（本票不新增 Java 测试）。

## Verification commands

静态：

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

活体（本机栈可用时必跑；不可用则登记边界，不得声称 PASS）：

```powershell
$env:SHOPPILOT_GATEWAY_BASE = "http://127.0.0.1:8082"
$env:SHOPPILOT_WORKSPACE_BASE = "http://127.0.0.1:18082"
$env:SHOPPILOT_TICKET_BASE = "http://127.0.0.1:8092"
$env:SHOPPILOT_IDENTITY_DEMO_PASSWORD = "<演示口令>"
$env:SHOPPILOT_INTERNAL_TOKEN = "<内部令牌>"
node scripts/verify-workspace.mjs
node scripts/verify-channel-hot-reload.mjs
```

## Forbidden

- 不改任何 Java / Vue / 生产配置 / 数据库 / API / gold / 阈值 / 覆盖率门槛。
- 不新增生产依赖；不改 ADR 结论。
- 不删除、跳过或弱化既有 check；不把种子失败降级成 WARN 或 SKIP。
- 不通过直接写库、写 localStorage 或伪造 DOM 来造队列数据；种子必须走真实工单服务 API。
- 不把 activeAppId 断言改成「再读另一个掩码字段自比」；期望值必须来自脚本已知的候选 appId。

## 现场追问

1. 若旧/新 appId 掩码相同，activeAppId 无法区分连接切换；本票用「启动 + 停止日志」
   补证，若要求更强证据，需要管理面新增连接代号（生产 API 变更，另票）。
2. 高/资金种子走工单服务直连，需要 `SHOPPILOT_INTERNAL_TOKEN` 与 `SHOPPILOT_TICKET_BASE`；
   若验收环境不暴露 8092，应改走网关新增创建端点（生产 API 变更，另票）。
3. 队列排序断言依赖前端客户端排序（`filterTickets`），不是后端 SQL 排序；
   若未来后端也排序，本断言仍成立，但不区分是哪一层排的。
