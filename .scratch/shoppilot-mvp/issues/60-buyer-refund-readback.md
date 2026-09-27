# 60 — 买家读回：`OrderView` 承载退款审核态，并把「到账」写成明确边界

**What to build:** 买家被告知「已受理，等待审核」之后，对话里要有出口。他下一轮问「我那退款到哪了」，今天只能拿到 `OrderStatus.REFUNDING` —— 它把「待审」与「已放行」**混成同一个值**，等于没答。本票把退款审核态带进 `OrderView`，让买家读回时能分辨三种情况（待审核 / 已放行 / 已驳回），并把「到账不在承诺范围」写进话术。

**Blocked by:** 59（退款审批闸门后端）。

**Status:** implemented（2026-09-28）。

**依据：所有者政策覆盖**（ADR 0046 / 0047）。

口径（ADR 0047 已定，本票只执行）：

- **走既有 `queryOrderDetail`，不新增 intent、不新增工具、不动 `ToolSchemaGenerator`。** gold 的断言形状是 `{"tool","args","slotAsk"}`（`eval/cases-part2-action.jsonl` 逐行如此），**没有任何一条断言答案文本**，所以在 `OrderView` 上加退款审核态**构造上碰不到判据**；`CONTEXT.md:102` 明列「新增意图需同步四处」，不走那条路。
- **落点**：`tool-api` 的 `OrderView` 加退款审核态字段（待审核 / 已放行 / 已驳回，来自该订单当前在办退款单的状态）；biz-mock 映射；受理话术与 `queryOrderDetail` 途径的答案都要**明确写出到账边界**（「已放行，到账由支付渠道处理」）。
- **Q12 裁定：`REFUNDED` 终态不推进**，只在话术里点明边界 —— 把 `REFUNDED` 那格从「沉默的缺口」变成「写下来的边界」。
- **间接风险**：工具结果变了 → dev 模型答案文本可能变 → 需活体确认 `verify-action-loop.ps1` 那几步的文本断言（gold 不判文本，故 gold 不受影响）。

- [x] `OrderView` 新增退款审核态字段（三态可分辨）；biz-mock 映射正确
- [x] 受理话术与 `queryOrderDetail` 答案明确写出到账边界（「已放行，到账由支付渠道处理」）
- [x] JUnit 用例：待审核 / 已放行 / 已驳回三态读回可分辨
- [x] 全量 `verify` 绿；`verify_eval_judge.py` 40/40
- [x] 活体 `verify-action-loop.ps1` **11/11 PASS、exit 0**（2026-09-28 补跑）

**Verify**
```bash
./mvnw.cmd -B -ntp verify
python scripts/verify_eval_judge.py
pwsh -NoProfile -File scripts/verify-action-loop.ps1
```

## Handoff notes

**关键决策**

- **审核态进 `OrderView`，用一个新枚举而不是裸状态串。** 新增 `tool/view/RefundReviewState{PENDING_REVIEW, RELEASED, REJECTED}`，映射自退款单状态（`PENDING_REVIEW→PENDING_REVIEW`、`PROCESSING→RELEASED`、`REJECTED→REJECTED`，未知/无退款 → null）。这样买家读回能分辨三件事，而 `OrderStatus.REFUNDING` 那格"待审与已放行同形"的缺口被补上。
- **到账边界写进 `ToolResponse.message`，不塞进 `OrderView` 数据字段。** `message` 是本仓既有的**面向模型**的说明通道（`failure` 的 message/allowedActions 就是给模型解释原因用的），把散文放这里比往数据 record 里加一个 `note` 字段干净。三态各一句，都写明「到账由支付渠道处理」或「订单已恢复」，与票 59 的受理话术同口径。
- **`REFUNDED` 终态仍不推进**（Q12）：本票只把「到账不在承诺范围」写下来，不造一个模拟支付通道的 actor。
- **不新增 intent、不新增工具、不动 `ToolSchemaGenerator`**：gold 的断言形状是 `{"tool","args","slotAsk"}`，不判答案文本，所以加字段构造上碰不到判据。

**验证落点**

- `RefundReviewTest` 8 条（新增 `buyerReadbackDistinguishesTheThreeRefundStates`）：待审/已放行/已驳回三态经 `queryOrderDetail` 读回可分辨，且 `message` 写明到账边界。
- 全量 `.\mvnw.cmd -B -ntp verify` → **`5 + 29 + 289 = 323` 绿**（biz-mock 28→29）。
- 覆盖率棘轮 exit 0：biz-mock LINE 78.64%→**79.30%**、gateway 59.65%、tool-api 47.95%（新增枚举与 `OrderView` 字段使 tool-api 略降，仍远高于门槛 40.0）。
- `verify_eval_judge.py` **40/40**；`git diff --check` 干净。

**未覆盖（按未达成登记，不摘红）**

- **活体 `verify-action-loop.ps1` 已于 2026-09-28 补跑：11/11 PASS、exit 0** —— 本票原先「工具结果变了 → 模型答案文本可能变，需活体复核」的挂账随之关闭（答案文本断言未退化）。
- **「退款进度」措辞的读回缺口**见票 62 的「未达成」：本地 3B 对「我那退款到哪了」判 `ACTION_REFUND` 后不调 `queryOrderDetail`，那一措辞下买家读回落空。本票的 `OrderView` 机制本身经「订单状态」措辞活体证成。

**你需要能当场回答的三个追问**

1. *Q：为什么加一个新枚举而不是直接回传退款单的原始状态串？* A：原始串（`PENDING_REVIEW`/`PROCESSING`/`REJECTED`）是**业务内部**的状态名，直接把内部状态名喂给模型会让它有机会复述出内部术语；买家侧的三种处境（待审/已放行/已驳回）是一个稳定的对外契约，用独立的枚举把内部状态与对外表达解耦，`PROCESSING→RELEASED` 这层映射就是那道缝。
2. *Q：到账边界为什么放 `message` 而不是 `OrderView` 的字段？* A：`message` 在本仓就是"给模型组织人话"的通道（`failure` 的 message 即此用途），散文放数据 record 会污染 DTO 的形状；而 `OrderView` 只承载可结构化的事实（审核态枚举）。
3. *Q：这条碰不碰 gold？* A：不碰。gold 逐行断言 `{"tool","args","slotAsk"}`，**没有任何一条断言答案文本**，加字段构造上就碰不到判据；间接风险是 dev 模型答案文本可能变，那由活体复核（票 62）覆盖。