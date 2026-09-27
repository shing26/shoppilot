# 62 — 审批闸门活体验收（`verify-refund-approval.ps1` + 矩阵 add-only 加步）

**What to build:** 给退款审批闸门一条活体黑盒证据：申请退款 → 断言 `tool_result.status = PENDING_APPROVAL` → `GET /pending` 有该单 → `POST .../review {APPROVE}` → 断言 `PROCESSING` 且买家读回能答出「已放行」；REJECT 独立一条走回滚 + 买家读回能答出「已驳回」。

**Blocked by:** 59、60、61。

**Status:** ready-for-agent

**依据：所有者政策覆盖**（ADR 0046）。活体预算按 Q7 裁定的「全套」，但**没跑成的一律按未达成登记、不许摘红**（round20 家法）。

口径：

- 新增 `scripts/verify-refund-approval.ps1`（形状照 `verify-idempotency.ps1`）：申请退款 → 断言受理态 → 队列有该单 → 放行 → 断言 `PROCESSING` + 买家读回「已放行」；REJECT 独立一条走回滚 + 买家读回「已驳回」。
- `run-acceptance.ps1` 矩阵 **add-only** 加一步（**不改既有 22 步的任何判据**）。
- **风险**：矩阵耗时已从 512 s 涨到 805 s，加一步约 +30-60 s；本机资源三条硬限制（TIME_WAIT / 内存 / 显存）见 `docs/EVIDENCE.md`。

- [ ] `scripts/verify-refund-approval.ps1` 新增，形状照 `verify-idempotency.ps1`
- [ ] APPROVE 路径：受理态 → 队列 → 放行 → `PROCESSING` + 买家读回「已放行」
- [ ] REJECT 路径：驳回 → 回滚 → 买家再申请可成 + 买家读回「已驳回」
- [ ] `run-acceptance.ps1` 矩阵 add-only 加一步，既有 22 步判据不动
- [ ] `pwsh -NoProfile -File scripts/check-ps-syntax.ps1` 通过
- [ ] 活体实跑（起栈；未跑成按未达成登记，**不许按推断声称已绿**）

**Verify**
```bash
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
pwsh -NoProfile -File scripts/verify-refund-approval.ps1
```