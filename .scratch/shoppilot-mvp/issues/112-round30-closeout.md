# 112 round30 收口

**Status:** implemented（2026-10-06）

## What to build

round30 收口：B3「资金动作不可抵赖」全部落地后的登记与换代。

- `docs/EVIDENCE.md` B3 行（守卫测试 / 活体验证 / 审计断言）。
- `docs/CODE_MAP.md`：OpsController 行补 `refundReviewQueue()` 守卫；RefundReviewController 行补
  `X-Actor-Authenticated` 检查。
- `CONTEXT.md`：新增术语「不可抵赖 (non-repudiation)」。
- `README.md`：已知限制补资金动作守卫覆盖面一条。
- tracker：round30 条目 + Round 表 + 票索引 + 读数换代。
- `program-a-to-b-upgrade.md`：B3 换代指针。

## Blocked by

[109](109-gateway-bizmock-hardline.md)、[110](110-frontend-refund-panel.md)、[111](111-verify-refund-script-switch.md)。

## 口径

- 判据面零改动；网关行为零变化。
- 读数换代以本次实测为准。

## Verify

```powershell
git diff --check; git status --short
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

**关键决策**：
- 网关 `reviewRefund` 从 `opsAccess(opsToken).allowed() || staff()` 改为 `staff()` only——ops token 不再能用于资金动作。
- `refundReviewQueue()` 补上 `staff()` 守卫（此前无任何守卫，任何登录用户可读——信息泄露）。
- biz-mock `RefundReviewController.review` 要求 `X-Actor-Authenticated: true`，否则 403 空响应体（ToolStatus 无 FORBIDDEN 枚举）。
- ops token 保留用于非资金动作（故障注入、工单队列、演示重置）。
- 前端 workspace 加退款审核面板，console 退款面板改只读。
- `verify-refund-approval.ps1` 切换坐席登录 + 审计事件 `actor_authenticated=true` 断言。

**验证落点**：
- JVM `10 + 66 + 345 + 45 = 466` 全绿（biz-mock +2 新测试）。
- CI 九步未动。
- 判据面零改动。

**三个现场追问**：
1. ops token 路径经 `selfReported(reviewer)` 产生 `Actor.of(claimed)`（未验签），B3 后资金动作不再接受此路径——非资金动作的自报身份是否也需要收紧？
2. 退款审核队列守卫补上后，买家角色是否还有其他路径能读到退款审核队列？
3. 审计事件 `actor_authenticated=true` 断言是否覆盖了所有资金动作路径（放行/驳回/撤销）？
