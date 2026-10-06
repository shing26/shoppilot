# ADR 0063: B3——资金动作不可抵赖：退款审核的责任人必须是已验签账号

日期：2026-10-06
状态：已接受
关联：[ADR 0058](../adr/0058-self-reported-identity-retired.md)（自报身份退役，ops token 保留）、[ADR 0047](../adr/0047-refund-review-gate.md)（退款审核闸门）、[program-a-to-b-upgrade.md](../../.scratch/shoppilot-mvp/program-a-to-b-upgrade.md)（B3 入口）

## 背景与触发

B3（审计不可抵赖）的触发条件是「B2 完成」——round29 已收口，所有者指示「进行 B3」。

B3 的目标：**退款放行的责任人不可抵赖**。退款放行是不可逆的资金动作，审计事件必须能回答「是谁放的」——
不是「哪个请求头自称的」，而是「哪个已验签的账号」。

## 现状的真实缺口（逐条对过代码）

1. **`refundReviewQueue()` 没有守卫**：`OpsController` 的 `GET /api/v1/support/ops/refunds/pending`
   直接调用 `forward`，没有 `opsAccess` 或 `staff()` 检查。任何登录用户（包括买家）都能读退款审核队列——
   信息泄露，且与 `reviewRefund` 的守卫不对称。
2. **`reviewRefund` 允许 ops token**：守卫是 `opsAccess(opsToken).allowed() || staff()`。
   ops token 路径下，`selfReported(reviewer)` 返回 `Actor.of(claimed)`（未认证）——
   审计事件的 `actor_authenticated=false`，责任人不可抵赖。
3. **biz-mock 没有检查 reviewer 是否已认证**：`RefundReviewController.review` 接受任何 `X-Actor` +
   `X-Actor-Authenticated` 组合，`BizMockService.reviewRefund` 不检查 `reviewer.authenticated()`。
   即使网关被绕过（如直连 biz-mock），未认证的审核也能落库。
4. **前端没有坐席退款审核面板**：workspace 只有工单队列，console 有退款面板但用 ops token。
   坐席没有地方审核退款。
5. **判据面脚本用买家令牌审核**：`verify-refund-approval.ps1` 用 `Get-MockToken`（BUYER）调
   `review` 端点——如果守卫有效，脚本应该失败；如果脚本是绿的，说明守卫有洞。

## 决策

### 1. 资金动作的责任人必须是已验签的账号

- **网关层**：`reviewRefund` 只允许 `staff()`，**不允许 ops token**。
  `refundReviewQueue()` 同样只允许 `staff()`。
- **biz-mock 层**：`RefundReviewController.review` 要求 `X-Actor-Authenticated: true`，
  否则 403。`BizMockService.reviewRefund` 不检查（控制器层已拦）。
- **ops token 保留**：仍然用于非资金动作（故障注入、工单队列、演示复位），
  但**不能用于退款审核**。ADR 0058 的「ops token 守运维面不守谁」仍然成立——
  只是资金动作的「谁」必须是已验签的账号。

### 2. 前端：workspace 加退款审核面板，console 改只读

- **workspace**：加退款审核面板（待审列表 + 放行/驳回 + 确认对话框），
  用坐席登录令牌调 `/api/v1/support/ops/refunds/pending` 和 `/review`。
- **console**：退款面板改只读（保留待审列表，移除放行/驳回按钮），
  加提示「退款审核请用坐席工作台」。

### 3. 判据面脚本：verify-refund-approval.ps1 切换坐席登录

- 用 `/auth/login` 换坐席令牌（`agent` 账号），调 `review` 端点。
- 新增审计断言：审核事件的 `actor_authenticated=true`。
- 如果坐席账号口令缺失 → exit 2（同 `verify-workspace.ps1` 模式）。

### 4. 测试覆盖

- **网关**：`refundReviewQueue()` 的守卫测试（BUYER → 403，staff → 200）。
- **网关**：`reviewRefund` 的 ops token 路径测试（ops token → 403，staff → 200）。
- **biz-mock**：`RefundReviewController.review` 的 `X-Actor-Authenticated` 检查测试
  （未认证 → 403，已认证 → 200）。
- **biz-mock**：`BizMockService.reviewRefund` 的审计事件 `actor_authenticated=true` 测试。

## 后果

- **ops token 不能用于退款审核**：调试台需要用坐席账号登录才能审核退款。
- **买家不能读退款审核队列**：`refundReviewQueue()` 加守卫后，买家调该端点返回 403。
- **审计事件总是 `actor_authenticated=true`**：资金动作的责任人必须是已验签的账号。
- **console 退款面板改只读**：防止买家通过 console 审核退款。
- **workspace 加退款审核面板**：坐席有地方审核退款。
- **verify-refund-approval.ps1 切换坐席登录**：判据面脚本用坐席身份审核。

## 残余风险

- **坐席账号口令**：`verify-refund-approval.ps1` 需要坐席账号口令，
  如果口令缺失 → exit 2（同 `verify-workspace.ps1` 模式）。
- **ops token 路径的审计事件**：非资金动作（如故障注入）的审计事件仍然是
  `actor_authenticated=false`，但这是可接受的——非资金动作不需要不可抵赖。
