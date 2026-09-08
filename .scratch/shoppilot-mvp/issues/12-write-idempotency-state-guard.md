# 12 — 写操作：改地址与退款的幂等和状态前置校验

**What to build:** 同一笔退款请求重复提交一百次只产生一条退款单；对已发货订单改地址被业务规则拒绝并由模型说明原因。落实任务书"100% 幂等"与 ADR 0008。

**Blocked by:** 11 — 业务办理闭环

**Status:** ready-for-agent

**Verify:** 并发 50 次同幂等 token 退款 -> 仅 1 条记录；停掉 Redis 再并发一次 -> DB 唯一约束仍拦住；对 `SHIPPED` 订单改地址 -> 被状态校验拒绝并由模型说明原因。

- [ ] 写操作要求客户端 `Idempotency-Token`；幂等键 `(tenantId, customerId, action, token)`，Redis SETNX + 首次结果落库
- [ ] 重复提交返回首次结果并推 `event: duplicate_submit`，不重复执行业务动作
- [ ] biz-mock 侧退款单加 DB 唯一约束兜底：Redis 不可用时仍不得重复退款（缓存不是正确性依赖）
- [ ] 状态前置校验在 biz-mock 强制：改地址仅 `CREATED`/`PAID` 允许；退款在 `PAID`/`SHIPPED`/`DELIVERED` 且下单 7 天内可发起
- [ ] 前置校验失败返回 `{code: STATE_NOT_ALLOWED, allowed: [...]}`，由网关喂给模型说明原因，而非直接吐错误码给用户
- [ ] 写操作加分布式业务锁 `lock:{tenant}:{order}`，防并发改同一单
- [ ] 必测用例：并发 50 次同一幂等 token 的退款只生成 1 条记录；对 `SHIPPED` 订单改地址被拒

## Handoff notes

（收尾时填写：关键决策 + 你需要能当场回答的三个追问）
