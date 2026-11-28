# 票 235 契约：退款回读结构化

- Round: 63
- 类型: 产品回读契约（U12 收窄；ADR 0047 / 0091）
- 开票依据: 所有者 2026-11-28「剩下问题按推荐执行」；ADR 0091 第一波
- 证据: `verify-refund-approval.ps1` 第 4 步仍靠「放行 / 支付渠道」自由文本；
  local 3B 回读措辞不稳定
- Implementer: 独立分支 `codex/235-refund-readback-structured`
- Verifier: 独立 subagent；命令由 Verifier 亲跑
- Blocked by: 234

## 问题

`OrderView` 已经有 `refundReview` 三态，买家回读的到账边界却只存在于中文 `message`
与模型正文里。验收脚本只能匹配「放行」「支付渠道」等措辞，模型换一种说法就红；
同时 `RELEASED` 仍容易被读成「已到账」。

## 目标

保留现有文本与既有断言，新增机器可读的到账边界字段，让验收与前端不必依赖措辞。

## Allowed paths

- `plans/235-refund-readback-structured.md`
- `.scratch/shoppilot-mvp/issues/235-refund-readback-structured.md`
- `.scratch/shoppilot-mvp/README.md`
- `docs/EVIDENCE.md`
- `docs/STATUS.md`（生成）
- `docs/TUNING_PLAN.md`（仅机械计数）
- `README.md`（仅测试数）
- `shoppilot-tool-api/src/main/java/com/shoppilot/tool/view/OrderView.java`
- `shoppilot-tool-api/src/main/java/com/shoppilot/tool/view/OrderDetailView.java`
- `shoppilot-tool-api/src/main/java/com/shoppilot/tool/view/PayoutBoundary.java`（新增）
- `shoppilot-biz-mock/src/main/java/com/shoppilot/bizmock/service/BizMockService.java`
- `shoppilot-biz-mock/src/test/java/com/shoppilot/bizmock/RefundReviewTest.java`
- `scripts/verify-refund-approval.ps1`
- `frontend-buyer/src/api.ts`
- `frontend-buyer/src/components/`（仅退款/订单详情展示所需文件）
- `frontend-buyer/src/**/*.test.ts` / `frontend-buyer/src/**/*.spec.ts`（如新增）

## Acceptance criteria

- **AC1（字段）**：`OrderView` / `OrderDetailView` 增加 `payoutBoundary`；
  无退款为 null，有退款为 `PAYMENT_CHANNEL`；`refundReview` 原字段与三态不变。
- **AC2（语义）**：`PENDING_REVIEW`、`RELEASED`、`REJECTED` 三态都返回
  `PAYMENT_CHANNEL`；不得把 `RELEASED` 表述成「已到账」。
- **AC3（文本保留）**：`refundBoundaryNote` 的中文文案保持原文；既有
  `message` 断言与 `verify-refund-approval.ps1` 的「放行 / 支付渠道」断言原样保留。
- **AC4（结构化断言）**：`verify-refund-approval.ps1` 增加通过买家订单详情读口
  取 `refundReview` 与 `payoutBoundary` 的断言；放行后为
  `RELEASED` + `PAYMENT_CHANNEL`，驳回后为 `REJECTED` + `PAYMENT_CHANNEL`。
- **AC5（前端）**：买家订单详情类型同步；展示层只把 `PAYMENT_CHANNEL` 映射为
  「到账由支付渠道处理」，不显示伪造的到账时间。
- **AC6（0 token）**：`RefundReviewTest` 覆盖三态的 `refundReview` + `payoutBoundary`；
  不依赖模型。
- **AC7（回归）**：窄测 + 全仓 `.\mvnw.cmd -B -ntp verify`、前端 typecheck/test/build
  全绿；gold / 阈值 / 分母 / `judge()` 零改动。
- **AC8（活体）**：`verify-refund-approval.ps1` 实跑；结构化断言 PASS，
  原文本断言保留。若 local 3B 文本仍红，按未达成登记，不得删除文本断言。

## Forbidden

- 不换本地模型、不追 local 27/2。
- 不删除、跳过或弱化自由文本断言；结构化字段是 add-only。
- 不新增 `REFUNDED` 终态，不把 `PROCESSING` 改名为到账。
- 不改 gold、阈值、分母、`judge()`、降级枚举或既有退款状态机。

## Verify

```powershell
.\mvnw.cmd -B -ntp -pl shoppilot-tool-api,shoppilot-biz-mock -am test
.\mvnw.cmd -B -ntp verify
cd frontend-buyer; npm run typecheck; npm test; npm run build; cd ..
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
python scripts/check_doc_links.py
git diff --check
git status --short
```

活体：

```powershell
pwsh -NoProfile -File scripts/verify-refund-approval.ps1
```
