# 票 234 契约：退款审批跨进程原子性

- Round: 63
- 类型: 数据一致性修复（U3；ADR 0047 / 0053 / 0061 / 0088）
- 开票依据: 所有者 2026-11-28「剩下问题按推荐执行」；ADR 0088 第一波
- 证据: `BizMockService.applyRefund` 受理事务提交后才跨进程开单；开单失败只 warn + 计数
- Implementer: 独立分支 `codex/234-refund-approval-outbox`
- Verifier: 独立 subagent；命令由 Verifier 亲跑
- Blocked by: 无
- 契约修订 1（2026-11-28）：把 round63 同时冻结的 235/236 契约与票面加入
  Allowed paths。它们是程序级开票工装，本票不执行其范围、不以其结果作为本票
  通过条件；拆到独立分支只会让 tracker 在 234 签收前引用不存在的文件。

## 问题

退款受理（`refunds` + 订单 `REFUNDING`）已提交后，才通过 HTTP 调工单服务创建
`REFUND_APPROVAL` 工作项。调用失败时只写 warn 与
`shoppilot_refund_ticket_missing_total`，`refunds.ticket_id` 留空；进程在两步之间
崩溃时连 warn 都没有。审核队列以 `refunds.PENDING_REVIEW` 为真源，所以受理不会
卡住，但「这笔退款等第二双眼睛」的工作项会永久缺失，且没有可重放的本地意图。

## 目标

在受理事务内落一张 outbox 意图；事务外由幂等补偿器开单、重试、回填。受理与开单
之间不再有「只存在内存里」的窗口。

## Allowed paths

- `plans/234-refund-approval-outbox.md`
- `plans/235-refund-readback-structured.md`（仅开票工装）
- `plans/236-audit-stream-isolation.md`（仅开票工装）
- `.scratch/shoppilot-mvp/issues/234-refund-approval-outbox.md`
- `.scratch/shoppilot-mvp/issues/235-refund-readback-structured.md`（仅开票工装）
- `.scratch/shoppilot-mvp/issues/236-audit-stream-isolation.md`（仅开票工装）
- `.scratch/shoppilot-mvp/README.md`
- `.scratch/shoppilot-mvp/registered-debt.md`
- `docs/adr/0088-internal-trial-hardening-program.md`
- `.scratch/shoppilot-mvp/round63-spec-trial-hardening.md`
- `docs/EVIDENCE.md`
- `docs/CODE_MAP.md`
- `docs/PROJECT_PLAN.md`
- `docs/STATUS.md`（生成）
- `docs/TUNING_PLAN.md`（仅机械计数）
- `README.md`（仅测试数 / ADR badge / 本程序状态）
- `CONTEXT.md`（仅 ADR 0088 已授权的术语精确化；不改既有结论）
- `shoppilot-biz-mock/src/main/java/com/shoppilot/bizmock/BizMockApplication.java`
- `shoppilot-biz-mock/src/main/java/com/shoppilot/bizmock/service/BizMockService.java`
- `shoppilot-biz-mock/src/main/java/com/shoppilot/bizmock/refundoutbox/`（新增包）
- `shoppilot-biz-mock/src/main/resources/application.yml`
- `shoppilot-biz-mock/src/main/resources/db/migration/V13__refund_work_item_outbox.sql`
- `shoppilot-biz-mock/src/main/resources/db/migration-postgresql/V13__refund_work_item_outbox.sql`
- `shoppilot-biz-mock/src/test/java/com/shoppilot/bizmock/RefundInvariantTest.java`
- `shoppilot-biz-mock/src/test/java/com/shoppilot/bizmock/WorkItemProducerTest.java`
- `shoppilot-biz-mock/src/test/java/com/shoppilot/bizmock/refundoutbox/`（新增测试）
- `shoppilot-biz-mock/src/test/resources/application.properties`（仅 outbox 调度测试开关）
- `scripts/verify-refund-outbox.ps1`

## Acceptance criteria

- **AC1（同事务意图）**：受理事务内同时写入 `refunds`、订单 `REFUNDING` 与一条
  `refund_work_item_outbox`；任一步失败则三者都不提交。禁止在事务外「先受理再补记意图」。
- **AC2（失败不回滚受理）**：工单服务不可达时，退款仍是 `PENDING_REVIEW`、订单仍是
  `REFUNDING`、接口仍返回 `PENDING_APPROVAL`；outbox 行为 `PENDING`，
  `refunds.ticket_id` 为 null，缺失计数 +1。
- **AC3（立即补偿）**：事务提交后立即尝试一次开单；成功时回填
  `refunds.ticket_id`，outbox 行为 `SENT`，只产生一张工作项。
- **AC4（退避重试）**：失败后 outbox 记录 attempts、next_attempt_at 与 last_error；
  补偿器按有界指数退避重试，不在同一次请求里忙等。工单恢复后下一次 due 处理成功，
  回填同一退款。
- **AC5（幂等）**：同一 outbox 行被重复扫描/重复调用处理时只开一张单；
  已 `SENT`/`CANCELLED` 的行不再调用工单服务。
- **AC6（审核后不补开）**：退款已放行或已驳回后，尚未处理的 outbox 不再补开工作项；
  审核动作本身仍按既有路径工作。
- **AC7（迁移与边界）**：H2 与 PostgreSQL 双侧 V13 同版本、同表意；
  `PostgresMigrationParityTest` 通过；outbox 查询显式携带 `tenant_id`，
  补偿器处理时设置并清理租户上下文。
- **AC8（回归）**：窄测 + 全仓 `.\mvnw.cmd -B -ntp verify` BUILD SUCCESS；
  gold / 阈值 / 分母 / `judge()` / 既有断言零弱化。
- **AC9（活体）**：`scripts/verify-refund-outbox.ps1` 在活体栈上完成
  「停工单服务 → 受理 → 恢复 → 补开 → 审核」并输出 PASS/FAIL 汇总；证据绑定
  HEAD SHA + `git status --short`。

## Forbidden

- 不改退款审核状态机、`PENDING_APPROVAL` 语义、`FallbackReason` 或降级枚举。
- 不引入消息队列、多实例分片、真实支付/SMTP、外部调度器。
- 不把 outbox 补偿写成「恰好一次」；至少一次与极端崩溃窗口必须如实登记。
- 不删除、跳过或弱化任何测试；不把活体红写成 PASS。
- 不提交 `.env`、`logs/`、崩溃日志、截图或临时产物。

## Verify

```powershell
.\mvnw.cmd -B -ntp -pl shoppilot-biz-mock -am test
.\mvnw.cmd -B -ntp verify
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
python scripts/check_doc_links.py
git diff --check
git status --short
```

活体：

```powershell
pwsh -NoProfile -File scripts/verify-refund-outbox.ps1
```
