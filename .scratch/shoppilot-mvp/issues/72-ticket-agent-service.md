# 72 工单与坐席服务：独立成第四个服务

**Status:** ready-for-agent

## What to build

按 ADR 0053 拆出第四个服务。新增 Maven 模块 **`shoppilot-ticket`**（端口 **8092**，本机只监听 loopback，同 biz-mock 的纪律），装载统一工单实体、队列、坐席领取与 SLA。

- 坐席 API（网关代理后暴露给工作台，浏览器不得直连）：
  - `GET /api/tickets?queue=&status=` 队列列表（含 SLA 剩余时间、按优先级排序）
  - `POST /api/tickets/{id}/claim` 领取（**乐观锁**：同一工单被两人同时领取必须只有一人成功）
  - `POST /api/tickets/{id}/resolve` 处理完成（写 `payload` 与状态，发 `audit` 事件）
  - `POST /api/tickets/{id}/release` 释放
- 消费 `ticket.routing` 消费组，工单落库后触发分派（与 70 的规则表协作）。
- `up.ps1` / `down.ps1` 加这个服务（`up.ps1` 已有可重入骨架），README 的端口表同步。
- 网关加 `/ops/tickets` 代理（tenantScoped，与退款审核面板同一套门控）。

## Blocked by

[71](71-event-backbone-streams.md)。

## 口径

- **不得直连数据库**（0053 禁止跨域直连 DB），跨域只走 API 与事件。
- 本服务是**演示口径**：坐席不写"处理人"以外的组织信息，不做排班。
- 领取用乐观锁，禁止"最后写入获胜"。

## 验收

- 并发领取同一工单，恰好一人 200、另一人 409（用例钉死）。
- 队列列表按 `URGENT_EMOTION` 优先、且 SLA 剩余时间递减。
- 租户隔离：跨租户读/领/处理全部拒绝。
- `up.ps1 -Profile local` 能起四个服务（日常档子集可只起 2-3 个，分档在 74 落）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
python .scratch/shoppilot-mvp/round3-closeout-audit.py
```

## Handoff notes

（收口时补。）
