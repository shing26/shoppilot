# 12 — 写操作：改地址与退款的幂等和状态前置校验

**What to build:** 同一笔退款请求重复提交一百次只产生一条退款单；对已发货订单改地址被业务规则拒绝并由模型说明原因。落实任务书"100% 幂等"与 ADR 0008。

**Blocked by:** 11 — 业务办理闭环

**Status:** done

**Verify:** 并发 50 次同幂等 token 退款 -> 仅 1 条记录；停掉 Redis 再并发一次 -> DB 唯一约束仍拦住；对 `SHIPPED` 订单改地址 -> 被状态校验拒绝并由模型说明原因。

- [x] 写操作要求客户端 `Idempotency-Token`；幂等键 `(tenantId, customerId, action, token)`，Redis SETNX + 首次结果落库
- [x] 重复提交返回首次结果并推 `event: duplicate_submit`，不重复执行业务动作
- [x] biz-mock 侧退款单加 DB 唯一约束兜底：Redis 不可用时仍不得重复退款（缓存不是正确性依赖）
- [x] 状态前置校验在 biz-mock 强制：改地址仅 `CREATED`/`PAID` 允许；退款在 `PAID`/`SHIPPED`/`DELIVERED` 且下单 7 天内可发起
- [x] 前置校验失败返回 `{code: STATE_NOT_ALLOWED, allowed: [...]}`，由网关喂给模型说明原因，而非直接吐错误码给用户
- [x] 写操作加分布式业务锁 `lock:{tenant}:{order}`，防并发改同一单
- [x] 必测用例：并发 50 次同一幂等 token 的退款只生成 1 条记录；对 `SHIPPED` 订单改地址被拒

## Handoff notes

**关键决策**

1. **幂等键是四元组 `(tenantId, customerId, action, token)`，不是单 token。** 少 `customerId`，两个买家偶然撞出同一个 token 就能互相吞掉退款；少 `action`，改地址会抑制掉退款。键做 MD5 后落 `shoppilot:idem:<hash>`，值先写 `__PENDING__` 占位，执行成功再覆盖为首次结果，TTL 6 小时。
2. **失败与业务拒绝必须 `abandon()` 清占位。** 只有 `ToolStatus.OK` 才 `complete()`。否则一次 `STATE_NOT_ALLOWED` 会被固化 6 小时，用户改好状态再试会被回放一个失败结果——幂等变成故障放大器。
3. **Redis 不可用时选择"照常执行、交给 DB 唯一约束拦"，而不是拒绝一切写操作。** 计数 `shoppilot_idempotency_redis_bypass_total`。缓存与幂等存储都不是正确性依赖，正确性依赖只有 `uk_refund_idempotency (order_id, idempotency_token)`。
4. **客户端不带 token 时按 `(tenant, customer, action, orderNo, reason)` 派生确定性 token。** 对话式入口里"用户把同一句话再说一遍"就是重试；不派生就等于把任务书的"100% 幂等"降级成"客户端配合时才幂等"。客户端显式提供时以客户端为准。
5. **`duplicate_submit` 与 `tool_executing` 互斥。** 幂等命中时业务动作根本没执行，还推"正在为您查询"是在骗用户。事件契约已同步进 PLAN.md。
6. **状态前置校验放在 biz-mock，不放网关。** 网关校验的是"能不能问"，业务系统才拥有状态机真相；校验失败返回结构化 `{status, message, allowedActions}`，由模型组织成人话，用户看不到错误码。
7. **演示固定单 90001-90004（T001/C001）**：随机造数的下单时间是 0~30 天均匀分布，"是否还在 7 天退款窗内"会随启动时刻漂移，演示脚本明天就可能被状态校验拒掉。四单钉死 PAID/SHIPPED/DELIVERED(超窗)/CREATED 四种状态，时间相对 `now` 计算；`POST /api/admin/demo/reset` 复位供彩排重复执行。

**你需要能当场回答的三个追问**

- *Q：Redis 挂了会不会重复退款？* A：不会。`IdempotencyService.begin()` 捕获 Redis 异常后放行，但 biz-mock 的 `uk_refund_idempotency` 会在插入时抛 23505，`applyRefund` 捕获后回查首次记录并返回 `IDEMPOTENT_REPLAY`。`TenantIsolationAndIdempotencyTest` 里有一条用例直接绕过网关、用同一个 token 往仓储层插两次，断言唯一约束拒绝。
- *Q：50 个并发同 token 请求，为什么不是 1 成功 49 个都插库撞约束？* A：两层。Redis `SETNX` 先让 1 个请求拿到占位，其余 49 个看到 `__PENDING__` 后 `waitForResult` 最多等 300 ms；等不到结果的再抢 `lock:{tenant}:{order}` 业务锁。实测 50 并发 = 1 个 `OK` + 49 个 `IDEMPOTENT_REPLAY`，库里恰好 1 行。
- *Q：幂等和分布式锁不是重复了吗？* A：职责不同。幂等键解决"同一请求别执行两次"，业务锁解决"同一订单的两个**不同**请求别并发改写"（比如同时改地址和申请退款）。锁带 10 秒租约，释放失败最坏是提前占用一个租约窗口，不会死锁。

**验证记录（2026-09-08，local 模式 / qwen2.5:3b）**

`scripts/verify-idempotency.ps1`：首次退款 `tool_executing -> tool_result`，refunds 0→1；同 token 重放 `duplicate_submit -> tool_result`，refunds 仍为 1；对 90002（SHIPPED）改址返回 `STATE_NOT_ALLOWED`，模型回复"订单状态为已发货（SHIPPED），此时无法修改收货地址"。`mvn test` 当时 35 项全绿（ticket 16/17 与 T1 标定后为 50 项：网关 39 + biz-mock 8 + tool-api 3）。
