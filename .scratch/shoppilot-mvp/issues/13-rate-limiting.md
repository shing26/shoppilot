# 13 — 双层限流与通道语义一致

**What to build:** 店铺配额与买家/IP 防刷各自独立生效，被限流的用户看到的仍是一条正常 SSE 通道里的兜底提示，而不是裸 429。

**Blocked by:** 05 — 最细竖切

**Status:** done

**Verify:** 超租户配额连打 -> SSE 收到 `rate_limited`、同步端点收到 429，且模型调用计数不再增长。

- [x] Redisson `RRateLimiter` 双维度：`tenant:{id}` 配额（来自 `tenants.rate_limit_qps`）+ `customer:{id}` 与 `ip:{x}` 防刷
- [x] 被限流走 SSE `event: rate_limited {retryAfterMs, message}`，同步端点则返回 429 带 `Retry-After`
- [x] 限流判定发生在模型调用与检索之前，否则限流失去保护成本的意义
- [x] 指标：按维度统计拒绝数，供压测报告区分"被限流"与"失败"
- [x] 用例：超配额请求不产生模型调用（用计数器断言）

## Handoff notes

**关键决策**

1. **双维度独立生效**：`tenant:{id}` 店铺配额（保护"这家店别把平台算力吃干"）+ `customer:{tenant}:{id}` 与 `ip:{x}` 防刷（保护"单个爬虫别把网关连接占满"）。只留一个都会漏——只留店铺配额挡不住单买家脚本，只留买家维度挡不住一店多号。
2. **限流判定发生在检索与模型调用之前**。否则成本已经花出去，限流只剩排队意义。`ChatController` 里 `admit()` 在任何编排动作之前。
3. **被限流不是失败**：流式端点仍在同一条 SSE 通道里推 `meta` → `fallback(RATE_LIMITED)` → `rate_limited {retryAfterMs, message}` 后正常 `complete()`，客户端不需要第二套错误处理；同步端点才用标准 429 + `Retry-After`，给中间层和脚本理解。
4. **`tryAcquire(1, 0, SECONDS)` 等待 0 秒**：限流要的是立刻判定，让请求在桶上排队等于把延迟转嫁给用户。
5. **Redis 不可用时放行**并告警，宁可不限流也不能把全站变成 503。限流器是保护装置，不是准入名单。
6. **店铺配额来自 biz-mock 的 `tenants.rate_limit_qps`**，网关本地缓存 60 秒，取不到用 `default-tenant-qps` 兜底。配额是业务属性，不该写死在网关配置里。
7. **压测 profile 用 `override-tenant-quota: true` 绕过 biz-mock 配额**，并把三个维度调到 100000。刻意"调高"而不是"关掉"：关掉会让 Redisson 整条路径退出被测范围，那组数字就没有意义了。压测报告必须注明本组数字是在这个配额下取得的。
8. **按维度统计拒绝数** `shoppilot_rate_limited_total{dimension}`，压测报告用它区分"被限流"与"失败"，拦截率分母也用它把 429 从"有效咨询请求"里减掉。

**你需要能当场回答的三个追问**

- *Q：为什么不用 Sentinel 或网关自带的 RequestRateLimiter？* A：要的是"按店铺配额 + 按买家/IP 双维度、且配额能从业务系统读"。Spring Cloud Gateway 的限流挂在路由层，读不到业务配额；Redisson `RRateLimiter` 是现成的分布式令牌桶，几十行代码就够，不用为此引入一个组件。
- *Q：Redisson 的速率改了为什么不生效？* A：这是实测踩到的坑——`trySetRate` 只在"没设置过"时生效，是幂等首写；配额变更后必须显式 `setRate`。现在按 `appliedRates` 记录本进程已对齐过的速率，只在配额变化时写一次 Redis，顺带把每请求 3 次 Redis 配置写降掉了。
- *Q：限流和幂等锁不重复吗？* A：不重复。限流管"这个买家每秒能问几次"，幂等管"同一个动作别执行两遍"，业务锁管"同一订单的两个不同动作别并发写"。三者键空间不同、失败语义不同。

**验证记录**

`scripts/verify-ratelimit.ps1` 10 项：超配额时流式端点收到 `rate_limited`、同步端点收到 429 带 `Retry-After`，且 `shoppilot_llm_calls_total` 不再增长；Redis 停机时请求放行。
