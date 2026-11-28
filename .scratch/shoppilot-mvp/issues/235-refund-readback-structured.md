# 票 235：退款回读结构化（U12 收窄）

Round: 63（内部试用就绪第一波；ADR 0091）
ADR: 0047（退款审核闸门）、0091（程序范围）
Status: signed（2026-11-28 所有者签收；本地合并 `dce6909`；待 push / CI）　Claimed by: Codex　Blocked by: 无（234 已于 2026-11-28 收口）
契约：[plans/235-refund-readback-structured.md](../../plans/235-refund-readback-structured.md)（2026-11-28 冻结）

## What to build

保留现有 `refundReview` 与中文文案，新增机器可读的到账边界字段：

1. `OrderView` / `OrderDetailView` 增加 `payoutBoundary`，无退款为 null；
2. 有退款时值为 `PAYMENT_CHANNEL`，表示到账权威在支付渠道，不在本仓；
3. `verify-refund-approval.ps1` 增加结构化断言，同时保留原「放行 / 支付渠道」文本断言；
4. 前端类型与订单详情展示同步，不把 `RELEASED` 写成「已到账」。

## 边界

- 不换本地模型、不追 local 27/2、不改 gold / 阈值 / 分母 / `judge()`。
- 不删除或弱化自由文本断言；结构化字段是 add-only。
- 不新增退款终态，不把 `PROCESSING` 改名为 `REFUNDED`。

## Verify

见契约 AC1–AC8。

## Handoff notes

**开票时事实（2026-11-28）**

- `RefundReviewState` 与 `OrderView.refundReview` 已存在；映射为
  `PENDING_REVIEW→PENDING_REVIEW`、`PROCESSING→RELEASED`、`REJECTED→REJECTED`。
- `verify-refund-approval.ps1` 第 4 步仍靠自由文本「放行」+「支付渠道」；
  local 3B 回读措辞不稳定。
- 本票只做结构化字段与断言，不解决 local 3B 工具派发率。

**实现与验证（2026-11-28，HEAD `88ef2dd`）**

- `OrderView` / `OrderDetailView` 新增 `payoutBoundary`；无退款 null，三态退款均为
  `PAYMENT_CHANNEL`。`refundReview` 三态与 `refundBoundaryNote` 原文未改。
- `RefundReviewTest` 补无退款 null 与三态边界断言；窄测 10/10。
- `verify-refund-approval.ps1` 新增放行/驳回两条买家订单详情结构化断言，
  原「放行 / 支付渠道」文本断言原样保留。
- 买家端 `OrderDetail` 类型同步；`OrderCard` 显示退款进度，只把
  `PAYMENT_CHANNEL` 映射为「到账由支付渠道处理」，不显示预计到账时间。
- 独立 Verifier 亲跑：全仓 `mvnw verify` 896 tests / 146 suites，0 失败；
  typecheck、44 条前端测试、build、脚本语法、文档门禁全绿；AC1–AC8 判
  **PASS-with-registered-unmet**。
- **登记未达成**：local 3B 两次活体读回都含「支付渠道」但缺「放行」，
  原文本断言两次红、脚本退出码 1；放行/驳回两条结构化断言两次均 PASS。
  按 AC8 原文保留该红点，不删除、不弱化，不以结构化字段替它洗绿。
- 环境边界：Qdrant/ES 不可用（Docker 引擎未起）；退款链路不依赖它们，
  活体 1–8 步均实际执行。Redis 由本机临时实例提供。
- **签收与合并（2026-11-28）**：所有者书面签收；post-sign merge `dce6909` 已落本地
  `main`，合并后 `mvnw verify` 仍为 896 tests / 146 suites、0 失败，文档门禁与脚本语法全绿。
  受本机网络（GitHub 443 与代理均不可达）阻塞，push 与 post-merge `ci-subset` 尚未完成；
  CI success 前不写 `done`、不解锁 236。
