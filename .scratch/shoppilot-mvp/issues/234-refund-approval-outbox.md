# 票 234：退款审批跨进程原子性（U3）

Round: 63（内部试用就绪第一波；ADR 0091）
ADR: 0047（退款审核闸门）、0053（工单跨服务）、0061（持久档）、0091（程序范围）
Status: done（2026-11-28 所有者签收；合并 `fbaccd2`；`ci-subset` `46099646530` success）　Claimed by: Codex　Blocked by: 无
契约：[plans/234-refund-approval-outbox.md](../../plans/234-refund-approval-outbox.md)（2026-11-28 冻结）

## What to build

把「退款受理已提交、审批工单没开成」从 warn + 计数改成本地 outbox 补偿：

1. 受理事务内写退款行、订单 `REFUNDING` 与 outbox 意图；三者同成同败。
2. 事务提交后立即尝试开单；失败不回滚受理，outbox 保持待处理。
3. 补偿器按退避重试，成功后回填 `refunds.ticket_id`，并把 outbox 标为已发送。
4. 重复执行补偿不得为同一退款重复开单；已审核/已驳回的退款不再补开。
5. 新增 H2/PostgreSQL 双侧 V13 迁移与可复跑活体脚本。

## 边界

- 不改退款审核状态机、`PENDING_APPROVAL` 语义、工单服务契约或降级枚举。
- 不引入消息队列、多实例分片、真实支付或 SMTP。
- 不删除既有「开单失败仍受理」的断言；只把它升级为 outbox 待补偿断言。
- 不把「至少一次开单」写成「恰好一次」；极端崩溃窗口按实登记。

## Verify

见契约 AC1–AC9。

## Handoff notes

**收口记录（2026-11-28）**：所有者书面签收后合入 `main`（merge `fbaccd2`），
post-merge `ci-subset` run [`46099646530`](https://github.com/shing26/shoppilot/actions/runs/46099646530)
success（Build and JVM tests 2m54s、Handoff golden set 33s）；合并前独立 Verifier
亲跑全仓 `mvnw verify` 896 tests / 146 suites、0 失败，活体 `verify-refund-outbox.ps1` 全 PASS。
实现分支 `codex/234-refund-approval-outbox` 已删除。235 解锁，票面状态同步为 ready-for-agent。

**实现（2026-11-28，分支 `codex/234-refund-approval-outbox`，基线 `f143288`）**

- **关键决策**
  - outbox 用 `JdbcTemplate` 而不是 JPA：补偿器在没有请求租户上下文的调度线程里跨租户扫描，
    `@TenantId` 会把查询收窄到 PLATFORM；所有 SQL 显式带 `tenant_id`，行里的租户只用于
    设置补偿调用上下文。
  - 受理事务只写本地事实（`refunds` + 订单 `REFUNDING` + outbox），开单在提交后做；
    开单失败不回滚受理，也不在请求里忙等。
  - 补偿语义是**至少一次**：`SENDING` 租约 2 分钟，进程崩溃后的回收重试可能重复开单；
    未产生「恰好一次」的承诺。
    AC5 的「同一行只开一张单」指**未崩溃**的重复扫描/重复调用（由 `PENDING → SENDING`
    条件更新认领保证）；`SENDING` 租约过期后的崩溃重试是契约明确登记的例外，不计作
    AC5 通过，也不据此宣称恰好一次。
  - 审核 `APPROVE` / `REJECT` 取消尚未处理的 `PENDING` 行；`SENDING` 行等租约回收后
    由补偿器读退款状态自行取消。窗口内仍可能补开一张已审核退款的工单，已如实登记。
  - **Verifier 首轮 P1-1 已整改**：原先 `SENDING` 行被回收后，`cancelByRefundId`
    只匹配 `PENDING`，会形成「回收 → 认领 → 取消 0 行」的无限循环。新增
    `cancelClaimed(id, tenantId, now)`，由补偿器取消**自己已认领**的 `SENDING` 行；
    AC6 新增「退款已放行 + 行已 SENDING + 租约过期」回归用例。
  - V13 只建表，不回填升级前已存在、`ticket_id` 为空的历史退款；审核队列仍以
    `refunds.PENDING_REVIEW` 为真源，所以历史单不会被卡住，但也不会被自动补开。
  - 保留 `shoppilot_refund_ticket_missing_total`，新增
    `shoppilot_refund_ticket_outbox_compensated_total` 与 `shoppilot_refund_ticket_outbox_pending`；
    README 唯一指标名计数从 77 同步到 79（四模块 81）。
    初版 `..._created_total` 被 Verifier 抓到抓取端会剥掉保留后缀 `_created`
    （实际导出成 `..._outbox_total`），已改名为 `..._compensated_total` 并补抓取名回归用例。
- **验证落点**
  - `RefundWorkItemOutboxTest`（AC1–AC6，10 条）：同事务回滚、失败不回滚受理、立即补偿、
    退避后补开、重复扫描不重复开单、审核后不补开（放行/驳回各一条）、
    补偿器已认领 `SENDING` 行后退款已放行时必须取消该行（Verifier P1-1 回归）、
    请求线程内立即补偿后恢复调用方租户上下文、补偿计数在 Prometheus 抓取端的导出名。
  - `RefundInvariantTest` / `WorkItemProducerTest` 原断言未弱化，继续覆盖并发唯一性与
    「开单失败仍受理」。
  - 窄测：`.\mvnw.cmd -B -ntp -pl shoppilot-biz-mock -am test` → 108 + 12 tests，
    0 failures / 0 errors。
  - H2 / PG 双侧 `V13__refund_work_item_outbox.sql` 同版本同表意，版本钟由
    `PostgresMigrationParityTest` 钉住。
  - 活体脚本：`scripts/verify-refund-outbox.ps1`（停工单服务 → 直连 biz-mock 受理 →
    恢复 → 轮询补开 → 坐席放行）；`finally` 保证工单服务恢复。
- **三个现场追问**
  1. 退款受理后工单服务恢复，补偿器是否在预期时间内只补开**一张**工单，且
     `refunds.ticket_id` 与工单号一致？
  2. 审核发生在 `SENDING` 租约内时，工单服务里是否出现一张「已放行退款」的待审单？
     这是已知窗口，不是通过项；发现后按「至少一次」记录，不改成恰好一次。
  3. `shoppilot_refund_ticket_outbox_pending` 长时间不归零时，先看 `last_error` 与
     `attempts` 再决定是否重启工单服务；不要用清表代替排障。

**开票时事实（2026-11-28）**

- `BizMockService.applyRefund` 在 `transactionTemplate` 内插退款并冻结订单，
  **提交后**才调 `openRefundApprovalWorkItem`；失败只 warn + `shoppilot_refund_ticket_missing_total`，
  `refunds.ticket_id` 留空。
- 代码注释已明确把 outbox 补偿登记为下一轮 debt；本票即该 debt。
