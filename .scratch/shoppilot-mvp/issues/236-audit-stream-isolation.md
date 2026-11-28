# 票 236：测试审计事件隔离（R10）

Round: 63（内部试用就绪第一波；ADR 0088）
ADR: 0054（事件与审计）、0088（程序范围）
Status: blocked　Claimed by: 未领取　Blocked by: 235
契约：[plans/236-audit-stream-isolation.md](../../plans/236-audit-stream-isolation.md)（2026-11-28 冻结）

## What to build

把 biz-mock 的审计流名与消费组变成配置项，并让 JVM 测试写独立流：

1. 生产默认仍是 `shoppilot:audit` / `shoppilot-audit`；
2. 测试配置使用独立流名与组名；
3. 新增 0-token 线格式/流名测试；
4. 受控复现：跑 `RefundReviewTest` 前后，活体 `/api/audit` 不新增测试行。

## 边界

- 不改审计事件字段、幂等键、直写兜底或查询接口。
- 不改工单服务的 `AuditPublisher` 与网关质量报表读取的生产流名。
- 不清历史审计数据、不删除既有测试断言。

## Verify

见契约 AC1–AC7。

## Handoff notes

**开票时事实（2026-11-28）**

- `RedisAuditChannel` 硬编码 `AuditTopics.AUDIT` / `AUDIT_GROUP`；
  `RefundReviewTest` 未替换审计通道，会向真实 Redis 流写测试事件；
  活体 biz-mock 惰性 drain 后把 `agent-001` / `alice` 落进 `audit_event`。
- 既有 `EventReconciliationTest` 用内存通道，覆盖不到这条泄漏。
