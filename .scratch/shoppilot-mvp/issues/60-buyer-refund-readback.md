# 60 — 买家读回：`OrderView` 承载退款审核态，并把「到账」写成明确边界

**What to build:** 买家被告知「已受理，等待审核」之后，对话里要有出口。他下一轮问「我那退款到哪了」，今天只能拿到 `OrderStatus.REFUNDING` —— 它把「待审」与「已放行」**混成同一个值**，等于没答。本票把退款审核态带进 `OrderView`，让买家读回时能分辨三种情况（待审核 / 已放行 / 已驳回），并把「到账不在承诺范围」写进话术。

**Blocked by:** 59（退款审批闸门后端）。

**Status:** ready-for-agent

**依据：所有者政策覆盖**（ADR 0046 / 0047）。

口径（ADR 0047 已定，本票只执行）：

- **走既有 `queryOrderDetail`，不新增 intent、不新增工具、不动 `ToolSchemaGenerator`。** gold 的断言形状是 `{"tool","args","slotAsk"}`（`eval/cases-part2-action.jsonl` 逐行如此），**没有任何一条断言答案文本**，所以在 `OrderView` 上加退款审核态**构造上碰不到判据**；`CONTEXT.md:102` 明列「新增意图需同步四处」，不走那条路。
- **落点**：`tool-api` 的 `OrderView` 加退款审核态字段（待审核 / 已放行 / 已驳回，来自该订单当前在办退款单的状态）；biz-mock 映射；受理话术与 `queryOrderDetail` 途径的答案都要**明确写出到账边界**（「已放行，到账由支付渠道处理」）。
- **Q12 裁定：`REFUNDED` 终态不推进**，只在话术里点明边界 —— 把 `REFUNDED` 那格从「沉默的缺口」变成「写下来的边界」。
- **间接风险**：工具结果变了 → dev 模型答案文本可能变 → 需活体确认 `verify-action-loop.ps1` 那几步的文本断言（gold 不判文本，故 gold 不受影响）。

- [ ] `OrderView` 新增退款审核态字段（三态可分辨）；biz-mock 映射正确
- [ ] 受理话术与 `queryOrderDetail` 答案明确写出到账边界（「已放行，到账由支付渠道处理」）
- [ ] JUnit 用例：待审核 / 已放行 / 已驳回三态读回可分辨
- [ ] 全量 `verify` 绿；`verify_eval_judge.py` 40/40
- [ ] 活体 `verify-action-loop.ps1` 文本断言复核（起栈；未跑成按未达成登记）

**Verify**
```bash
./mvnw.cmd -B -ntp verify
python scripts/verify_eval_judge.py
pwsh -NoProfile -File scripts/verify-action-loop.ps1
```