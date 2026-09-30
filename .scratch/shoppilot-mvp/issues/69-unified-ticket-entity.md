# 69 工单统一实体：三个混血体收敛为一张工单表

**Status:** ready-for-agent

## What to build

仓里「需要人工介入」是三个混血体：① 降级工单（`FallbackReason` → ticket 表）② 满意度复核队列（feedback）③ 退款审批队列（`Refund.PENDING_REVIEW`，round21 建）。本票建**一个统一工单实体**与**三种来源**，作为分流的分母（ADR 0055）。

- `Ticket` 实体：`id`、`tenantId`、`source`（`DEGRADE` / `FEEDBACK_REVIEW` / `REFUND_APPROVAL`）、`queue`、`priority`、`status`、`slaDeadline`、`assignee`、`payload`（JSON：降级原因/审核单号/反馈 id）、时间戳。
- Flyway **`V3__unified_ticket.sql`**（V1 基线、V2 复核队列索引已占）；`ddl-auto: validate` 不变 → 迁移缺表即启动红。
- 降级路径**直接写新表**（这就是渐进迁移，不做双写）；复核与退款审批切到新表，**旧表只读保留一版**。
- 写路径的租户隔离沿用既有三道防线（`@TenantId` + 仓储层 + 服务层显式比较——`findById` 不经 `@TenantId` 是本仓已登记的坑）。

## Blocked by

无（round23 第一票）。依据 ADR 0055，定位依据 ADR 0052。

## 口径（一个字都不能动）

README 公开的「降级原因 枚举 10 / 降级 9」以**降级工单数量**为口径之一。迁移后：新表 `source=DEGRADE` 的行数必须等于迁移前旧表行数；三个来源的映射关系写进 `docs/EVIDENCE.md`。**不得为了让数字好看而合并或去重工单。**

## 验收

- 一张工单表可查到三种来源，且每种来源都能从 `payload` 还原它原来的业务上下文。
- 迁移前后降级工单行数一致（迁移脚本自带对账查询，数字不符即红）。
- 旧表保留且只读；工单列表 API 按租户隔离，跨租户读取被拒。
- CONTEXT.md 补术语：工单、工单来源、队列、优先级、坐席、领取、SLA 计时（ADR 0055 Consequences）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify                    # 5 + 29 + 308 + N 全绿（gateway 覆盖率棘轮 63.23 不得掉）
pwsh -NoProfile -File scripts/check-ps-syntax.ps1   # 若动到 ps 脚本
python .scratch/shoppilot-mvp/round3-closeout-audit.py
```

新增用例至少覆盖：实体映射、租户隔离、来源枚举与 payload 往返、迁移后行数对账。

## Handoff notes

（收口时补：关键决策、验证落点、三个现场追问。）
