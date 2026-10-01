# 72 工单与坐席服务：独立成第四个服务

**Status:** ready-for-agent

## What to build

按 ADR 0053 拆出第四个服务。新增 Maven 模块 **`shoppilot-ticket`**（端口 **8092**，本机只监听 loopback，同 biz-mock 的纪律）。

**数据归属由所有者裁定（2026-10-01，见 spec §0 的 B）**：`tickets` 表**随数据迁到工单服务自持**，不与 biz-mock 共库——本机用的是 H2 内存库，内存库是进程内的，两个进程物理上无法共享，共库这条路在当前技术栈下不存在。

因此本票含三件比「抽服务」更重的事：

1. **迁移**：`tickets` 与 `refunds.ticket_id` 相关的建表与数据搬进工单服务自有库（Flyway 基线从 V5 起，工单服务自己的迁移目录）。
2. **改三个生产者**：
   - 网关的降级/情绪升级单改投工单服务（`FallbackService` / `EmailReceiptWriter` 的目标地址）；
   - biz-mock 的退款审批（`applyRefund` 内建审批单）与反馈复核（`openReviewWorkItem`）改为调工单服务 API 落单，不再自持 `tickets` 实体。
3. **跨进程的一致性缺口要显式处置**：退款「受理 → 开单」跳进程后**不再同事务**。处置与边界：**审批的真源是 `refunds.PENDING_REVIEW`（审核队列读它），工单是受理侧的工作项**；开单失败 → 工单缺失但资金安全不受影响，且由审计事件可查。这是登记项，不是「已解决」。

坐席 API（经网关代理后暴露给工作台，浏览器不得直连）：

- `GET /api/tickets?queue=&status=` 队列列表（按优先级排序 + SLA 剩余时间；触发 SLA 超时打戳）
- `POST /api/tickets/{id}/claim` 领取（**乐观锁**：同一工单被两人同时领取必须只有一人成功）
- `POST /api/tickets/{id}/resolve` 处理完成（写 `payload` 与状态，发 `audit` 事件——票 71 的那条）
- `POST /api/tickets/{id}/release` 释放

`up.ps1` / `down.ps1` 加这个服务（`up.ps1` 已有可重入骨架），README 的端口表同步。网关加 `/ops/tickets` 代理（tenantScoped，与退款审核面板同一套门控）。

## Blocked by

[71](71-event-backbone-streams.md)（`audit` 事件在票 71 落地；处理动作要发它）、[70](70-routing-rules-and-priority.md)（分派规则表随数据一起迁）。

## 口径

- **不得直连数据库**（0053 禁止跨域直连 DB），跨域只走 API 与事件。
- 本服务是**演示口径**：坐席不写"处理人"以外的组织信息，不做排班。
- 领取用乐观锁，禁止"最后写入获胜"。

## 验收

- 并发领取同一工单，恰好一人 200、另一人 409（用例钉死）。
- 队列列表按优先级排序、且 SLA 剩余时间递减；超时打戳在读路径上生效。
- 租户隔离：跨租户读/领/处理全部拒绝。
- **三个生产者都改完了**：网关的降级单、biz-mock 的退款审批单与复核单，都经由工单服务落库——有用例钉住「biz-mock 不再持有 tickets 表的写入路径」。
- 跨进程一致性缺口按上面的边界处置，并有登记（不是「已解决」）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
python .scratch/shoppilot-mvp/round3-closeout-audit.py
```

**活体验证本轮不做**（所有者裁定 A）：起四服务约 7 GB，本机只剩 0.5 GB。所以本票的「起栈能通」按**未达成登记**，不摘红。

## Handoff notes

（收口时补。）
