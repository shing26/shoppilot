# round30 spec —— B3「资金动作不可抵赖：退款审核的责任人必须是已验签账号」

> 状态：执行中（2026-10-06 开轮）。
> 依据：[program-a-to-b-upgrade.md](program-a-to-b-upgrade.md) B3 入口（触发 = B2 完成，round29 已收口）；
> [ADR 0063](../../docs/adr/0063-b3-money-action-non-repudiation.md)。
> 性质：**所有者政策覆盖**（B 段 program，所有者 2026-10-06 指示「进行 B3」）。

## 0. 本轮一句话

B2 让凭据有了生产态；B3 回答「**谁放了这笔款，凭什么说他放的**」：退款审核的责任人必须是已验签的账号，
ops token 不能用于资金动作，买家不能读退款审核队列，审计事件总是 `actor_authenticated=true`。

## 1. 范围

- **做**：
  1. 网关层：`refundReviewQueue()` 加守卫（只允许 staff()）；`reviewRefund` 改为只允许 staff()，
     不允许 ops token；
  2. biz-mock 层：`RefundReviewController.review` 要求 `X-Actor-Authenticated: true`，否则 403；
  3. 前端：workspace 加退款审核面板（待审列表 + 放行/驳回 + 确认对话框）；
     console 退款面板改只读（保留待审列表，移除放行/驳回按钮）；
  4. 判据面：`verify-refund-approval.ps1` 切换坐席登录（`/auth/login` 换坐席令牌），
     新增审计断言（审核事件的 `actor_authenticated=true`）。
- **不做**：
  - 非资金动作的 ops token 路径（故障注入、工单队列、演示复位）保持不变；
  - 审计事件的历史数据不迁移（B3 之后的审核事件总是 `actor_authenticated=true`）。

## 2. 硬约束

- 判据面零改动：verify-\*.ps1 的断言逻辑不变（除了 `verify-refund-approval.ps1` 新增审计断言）；
  矩阵步数 / gold / 阈值全不动。
- 网关行为零变化：`reviewRefund` 的守卫从 `opsAccess(opsToken).allowed() || staff()` 改为 `staff()`；
  `refundReviewQueue()` 新增守卫 `staff()`。
- 凭据不进仓库：坐席账号口令走 `.env`（同 `verify-workspace.ps1` 模式）。
- CI 九步不变；新增 JVM 用例全部 0 token。

## 3. 验证形态

| 层 | 内容 |
| --- | --- |
| JVM | 网关：`refundReviewQueue()` 的守卫测试（BUYER → 403，staff → 200）；`reviewRefund` 的 ops token 路径测试（ops token → 403，staff → 200）；biz-mock：`RefundReviewController.review` 的 `X-Actor-Authenticated` 检查测试（未认证 → 403，已认证 → 200）；`BizMockService.reviewRefund` 的审计事件 `actor_authenticated=true` 测试 |
| 活体 | ① 买家令牌调 `refundReviewQueue()` → 403；② ops token 调 `reviewRefund` → 403；③ 坐席令牌调 `reviewRefund` → 200，审计事件 `actor_authenticated=true`；④ workspace 退款审核面板可用；⑤ console 退款面板只读 |

## 4. 票

| # | 票 | 依赖 |
| --- | --- | --- |
| 108 | ADR 0063 + spec | — |
| 109 | 网关 + biz-mock 硬线 | 108 |
| 110 | 前端（workspace 面板 + console 只读） | 109 |
| 111 | verify-refund-approval.ps1 切换坐席登录 + 审计断言 | 109 |
| 112 | 收口 | 109-111 |

## 5. 登记节

- **ops token 路径的审计事件**：非资金动作（如故障注入）的审计事件仍然是
  `actor_authenticated=false`，但这是可接受的——非资金动作不需要不可抵赖。
- **坐席账号口令**：`verify-refund-approval.ps1` 需要坐席账号口令，
  如果口令缺失 → exit 2（同 `verify-workspace.ps1` 模式）。
- **历史审计事件**：B3 之前的审核事件可能 `actor_authenticated=false`，
  不迁移——B3 之后的审核事件总是 `actor_authenticated=true`。
