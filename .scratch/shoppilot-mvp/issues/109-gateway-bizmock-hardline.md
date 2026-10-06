# 109 网关 + biz-mock 硬线

**Status:** implemented（2026-10-06）

## What to build

网关层与 biz-mock 层的资金动作硬线。

### 网关层（shoppilot-gateway）

1. `OpsController.refundReviewQueue()` 加守卫：只允许 `staff()`，否则 403。
2. `OpsController.reviewRefund()` 守卫改为只允许 `staff()`，不允许 ops token。
3. 新增测试：
   - `refundReviewQueue()` 的守卫测试（BUYER → 403，staff → 200）；
   - `reviewRefund` 的 ops token 路径测试（ops token → 403，staff → 200）。

### biz-mock 层（shoppilot-biz-mock）

1. `RefundReviewController.review` 要求 `X-Actor-Authenticated: true`，否则 403。
2. 新增测试：
   - `RefundReviewController.review` 的 `X-Actor-Authenticated` 检查测试（未认证 → 403，已认证 → 200）；
   - `BizMockService.reviewRefund` 的审计事件 `actor_authenticated=true` 测试。

## Blocked by

[108](108-b3-adr-and-spec.md)

## 口径

- 判据面零改动；网关行为零变化（`reviewRefund` 守卫从 `opsAccess(opsToken).allowed() || staff()`
  改为 `staff()`；`refundReviewQueue()` 新增守卫 `staff()`）。
- ops token 保留：仍然用于非资金动作（故障注入、工单队列、演示复位）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify -pl shoppilot-gateway,shoppilot-biz-mock
```

## Handoff notes

**关键决策**：
- 网关 `reviewRefund` 守卫从 `opsAccess(opsToken).allowed() || staff()` 改为 `staff()`，ops token 不再能用于资金动作。
- 网关 `refundReviewQueue()` 新增 `staff()` 守卫（此前无任何守卫，买家可读取退款审核队列）。
- biz-mock `RefundReviewController.review` 新增 `X-Actor-Authenticated: true` 硬线，未认证的审核返回 403 空体。
- ops token 保留用于非资金动作（故障注入、工单队列、演示复位）。

**验证落点**：
- JVM 测试全绿（`10 + 64 + 345 + 45 = 464`）。
- 新增测试：`refundReviewRejectsOpsToken`、`refundReviewQueueRejectsBuyer`、`refundReviewQueueAllowsStaff`、`reviewRejectsUnauthenticatedActor`、`reviewAllowsAuthenticatedActor`。
- 更新测试：`refundReviewProxiesWithTenantContext` 改为 AGENT 角色；`AuditEventFlowTest.review` 改为 `authenticated=true`；`auditDistinguishesAuthenticatedActorsFromSelfReportedOnes` 只验证 authenticated=true；`retiredSelfReportedHeaderIsIgnored` 加 `X-Actor-Authenticated: true`。

**三个现场追问**：
1. ops token 是否应该完全退役？—— 不，它仍然用于非资金动作（故障注入、工单队列、演示复位），只是不能用于资金动作。
2. biz-mock 的硬线是否多余？—— 不多余，即使网关被绕过（如直连 biz-mock），未认证的审核也不能落库。
3. 审计事件的 `actor_authenticated` 字段是否足够？—— 足够，它明确区分了「认证过的操作人」与「自报的操作人」。
