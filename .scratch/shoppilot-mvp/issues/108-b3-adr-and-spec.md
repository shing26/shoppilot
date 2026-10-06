# 108 B3 ADR + spec

**Status:** implemented（2026-10-06）

## What to build

B3「资金动作不可抵赖」的 ADR 与 spec。

- `docs/adr/0063-b3-money-action-non-repudiation.md`：资金动作的责任人必须是已验签账号；
  网关层 `reviewRefund` 只允许 staff()，`refundReviewQueue()` 加守卫；biz-mock 层 `review` 要求
  `X-Actor-Authenticated: true`；前端 workspace 加退款审核面板，console 改只读；
  判据面脚本切换坐席登录。
- `.scratch/shoppilot-mvp/round30-spec-b3-non-repudiation.md`：round30 spec。

## Blocked by

—

## 口径

- 判据面零改动；网关行为零变化（`reviewRefund` 守卫从 `opsAccess(opsToken).allowed() || staff()`
  改为 `staff()`；`refundReviewQueue()` 新增守卫 `staff()`）。
- 凭据不进仓库：坐席账号口令走 `.env`。

## Verify

```powershell
git diff --check; git status --short
```
