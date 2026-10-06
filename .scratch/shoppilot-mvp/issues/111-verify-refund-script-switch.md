# 111 verify-refund-approval.ps1 切换坐席登录 + 审计断言

**Status:** implemented（2026-10-06）

## What to build

判据面脚本切换坐席登录。

1. `verify-refund-approval.ps1` 用 `/auth/login` 换坐席令牌（`agent` 账号），调 `review` 端点。
2. 新增审计断言：审核事件的 `actor_authenticated=true`。
3. 如果坐席账号口令缺失 → exit 2（同 `verify-workspace.ps1` 模式）。

## Blocked by

[109](109-gateway-bizmock-hardline.md)

## 口径

- 判据面零改动（除了新增审计断言）；矩阵步数 / gold / 阈值全不动。
- 凭据不进仓库：坐席账号口令走 `.env`。

## Verify

```powershell
pwsh -NoProfile -File scripts/verify-refund-approval.ps1
```

## Handoff notes

**关键决策**：
- `verify-refund-approval.ps1` 改用 `/auth/login` 换坐席令牌（`agent` 账号），调 `review` 端点。
- 新增审计断言：审核事件的 `actor_authenticated=true`。
- 买家 token 仍用于申请退款和读回（买家才能申请退款）。

**验证落点**：
- 脚本已修改：`scripts/verify-refund-approval.ps1`。
- 活体验证待进行（需要启动全栈服务）。

**三个现场追问**：
1. 为什么不用 mock-token？—— mock-token 是买家 token，B3 之后不能用于资金动作。
2. 审计断言是否足够？—— 足够，它验证了资金动作的责任人是已验签账号。
3. 坐席账号口令是否安全？—— 口令走 `.env`，不进仓库。
