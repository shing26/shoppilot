# 13 — 双层限流与通道语义一致

**What to build:** 店铺配额与买家/IP 防刷各自独立生效，被限流的用户看到的仍是一条正常 SSE 通道里的兜底提示，而不是裸 429。

**Blocked by:** 05 — 最细竖切

**Status:** ready-for-agent

**Verify:** 超租户配额连打 -> SSE 收到 `rate_limited`、同步端点收到 429，且模型调用计数不再增长。

- [ ] Redisson `RRateLimiter` 双维度：`tenant:{id}` 配额（来自 `tenants.rate_limit_qps`）+ `customer:{id}` 与 `ip:{x}` 防刷
- [ ] 被限流走 SSE `event: rate_limited {retryAfterMs, message}`，同步端点则返回 429 带 `Retry-After`
- [ ] 限流判定发生在模型调用与检索之前，否则限流失去保护成本的意义
- [ ] 指标：按维度统计拒绝数，供压测报告区分"被限流"与"失败"
- [ ] 用例：超配额请求不产生模型调用（用计数器断言）

## Handoff notes

（收尾时填写：关键决策 + 你需要能当场回答的三个追问）
