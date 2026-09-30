# 74 验证分档：日常档 / 全栈档 + 事件对账门禁

**Status:** ready-for-agent

## What to build

ADR 0053 的 Consequences 直接派生：全栈约 7 GB，日常档子集约 3.5 GB，而本机同时跑着另外四套项目（实测可用内存曾只剩 0.5 GB）。**验证不分档 = 验证不可复跑**。

- `run-acceptance.ps1` 加 `-Tier daily|full`：
  - **daily**：中间件 + 网关 + 工单服务（+ 可选业务服务），够跑 JVM 门禁、定向验收、console/workspace 断言；
  - **full**：四服务 + local 模型，**仅清场日**。
- 分档写进 `docs/EVIDENCE.md` 与 `README` 的验收段；**全栈档的红不得冒充日常档信号**（round17 起的既有纪律，措辞照抄那条）。
- 收口审计新增**事件对账**门禁（0054 承诺的第三类证据）：发布数 / 消费数 / pending 是否归零。审计常数随票换代。
- 记录一条**资源读数**：日常档与全栈档各跑一次后记下 `Available MBytes` 与提交余量，供下一轮排期。

## Blocked by

[71](71-event-backbone-streams.md)、[72](72-ticket-agent-service.md)。

## 口径

- **不得为跑全栈档去停别的项目容器**（AGENTS.md 与 program §7）。清场日由所有者决定。
- 分档不是降标准：两档的**判据完全相同**，差别只在能不能起全栈。
- 没跑成的档按**未达成登记**，不摘红（round20 家法）。

## 验收

- `-Tier daily` 与 `-Tier full` 都能跑起来（未跑成的按未达成登记）。
- 事件对账门禁在 CI 与本机都能判红（变异对照：故意让消费端不 ACK → 门禁转红）。
- EVIDENCE 里两档的读数与资源数字都在。

## Verify

```powershell
pwsh -NoProfile -File scripts/run-acceptance.ps1 -Tier daily
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
python .scratch/shoppilot-mvp/round3-closeout-audit.py
```

## Handoff notes

（收口时补。）
