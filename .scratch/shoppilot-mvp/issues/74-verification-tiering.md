# 74 验证分档：日常档 / 全栈档 + 事件对账门禁

**Status:** ready-for-agent

## What to build

ADR 0053 的 Consequences 直接派生：全栈约 7 GB，日常档子集约 3.5 GB，而本机同时跑着另外四套项目（实测可用内存曾只剩 0.5 GB）。**验证不分档 = 验证不可复跑**。

**范围被裁定 A 收窄（见 spec §0）**：本轮**不跑活体验证**，所以本票交付的是**机制**而不是读数。

- `run-acceptance.ps1` 加 `-Tier daily|full`：
  - **daily**：中间件 + 网关 + 工单服务（+ 可选业务服务），够跑 JVM 门禁、定向验收；
  - **full**：四服务 + local 模型，**仅清场日**。
- 分档写进 `docs/EVIDENCE.md` 与 `README` 的验收段；**全栈档的红不得冒充日常档信号**（round17 起的既有纪律，措辞照抄那条）。
- 收口审计新增**事件对账**门禁（0054 承诺的第三类证据）：发布数 / 消费数 / pending 是否归零。审计常数随票换代。**注意**：门禁只在 JVM 层（裁定 D 的内存实现）成立；真实 Redis 上的 pending 读数照登未达成。
- **资源读数本轮取不到**（不起栈）——记一条**待补**而不是编一个数字。

## Blocked by

[71](71-event-backbone-streams.md)、[72](72-ticket-agent-service.md)。

## 口径

- **不得为跑全栈档去停别的项目容器**（AGENTS.md 与 program §7）。清场日由所有者决定。
- 分档不是降标准：两档的**判据完全相同**，差别只在能不能起全栈。
- 没跑成的档按**未达成登记**，不摘红（round20 家法）。**本票按裁定 A 预期全部未达成**——这是选择的结果，不摘。

## 验收

- `-Tier daily` 与 `-Tier full` 的**参数解析与步骤裁剪**都有用例（不依赖真起栈）。
- 事件对账门禁在 JVM 层能判红（变异对照：故意让消费端不 ACK → 门禁转红）。
- EVIDENCE 里两档的**定义**都在，**读数栏写「本轮按裁定 A 未取」**而不是留空或编数字。

## Verify

```powershell
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
python .scratch/shoppilot-mvp/round3-closeout-audit.py
```

（`run-acceptance.ps1 -Tier daily` 本轮**按裁定 A 不执行**，按未达成登记。）

## Handoff notes

（收口时补。）
