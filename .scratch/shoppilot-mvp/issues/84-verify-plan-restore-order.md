# 84 验收脚本 `plan` 步的恢复顺序（票 75 登记的脚本脆弱性）

**Status:** ready-for-agent

## What to build

票 75 的收口登记了一条**验收脚本自身**的缺陷，不属于被测系统，本轮单开一张处理。

**登记原文**：`-WithRestarts` 的 ticket 14 分支把真 `.env` 挪走、只留一个 `SHOPPILOT_OLLAMA_URL` 占位，
结束时恢复并重启网关；跑完之后「后续 ops 调用全被 biz-mock 拒（`missing or invalid internal token`）」。

## 第一件事：复现，再改

登记里写的根因是「**重启前没有先停掉用占位配置起的那一个**，端口被占，新进程起不来，旧网关带着空
internal token 继续服务」。**这条根因登记在本轮开始时尚未复核**，所以本票的第一格是复现，不是修复。

读代码时看到的事实（供复现时对照，不当结论）：

- `try` 块（`scripts/verify-plan-actions.ps1:308-325`）用占位 `.env` 重起网关；
- `finally` 块（`:326-334`）的顺序是**恢复 `.env` → `stop.ps1 -Ports '8082'` → `start-gateway.ps1`**，
  表面上「先停后起」是对的；
- 真正可疑的是 `Wait-ServiceUp` **分不出新旧进程**：只要端口上有东西在应答 readiness 就返回 true。
  如果 `stop.ps1` 没杀干净（或新进程尚未抢到端口、旧进程还在应答），这一步会**判绿**——
  而此时服务的是旧配置。这与 round23 清场日抓到的「队列空也判过」是同一种假绿。

## 若复现不出来

按仓内纪律**更正登记**，不为了「修一个东西」而制造一个修复：把真实根因写进本票的 Handoff，
并在 tracker 与 `docs/EVIDENCE.md` 里把票 75 那条登记改成更正后的口径。

## Blocked by

无。

## 口径

- **不动被测代码**：这是事实性修正，验收脚本的缺陷不授权去改产品行为。
- **不改判据**：ticket 14 的判据（模型端点不通时以 `fallback` 收尾、原因是 `LLM_CIRCUIT_OPEN`、工单能查回）一字不动。
- 若根因确实是「网关与 biz-mock 的 internal-token 默认值不一致」，那属于**配置面缺陷**，
  要么在本票里一并对齐（并记 ADR），要么单开一张——**不在本票顺手改**。

## 验收

- 修复前红 / 修复后绿**两组读数**（round20 起对验收脚本的硬要求：门禁变绿不等于系统变好）；
- `pwsh -NoProfile -File scripts/check-ps-syntax.ps1` 绿；
- 修复后跑一次 `run-acceptance.ps1 -Only plan -WithRestarts`，其**后续步骤的 ops 调用不再被拒**。

## Verify

```powershell
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
pwsh -NoProfile -File scripts/run-acceptance.ps1 -SkipBuild -SkipStack -Only plan
```

## Handoff notes

（收口时补）