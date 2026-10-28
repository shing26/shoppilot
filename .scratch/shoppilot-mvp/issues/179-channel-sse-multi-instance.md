# 179 多实例 SSE：渠道会话事件走 Redis pub/sub

**Status:** implemented（2026-10-28，Redis pub/sub 跨实例 fan-out 已验证）

**Claimed by:** zcode session 2026-10-28

> 票 177 的 SSE 事件总线只在进程内。网关扩到多个副本时，坐席连到 A、消息落到 B，A 收不到。本票改成 Redis pub/sub。

## 目标

- publish 时发到 Redis 频道 shoppilot:channel-inbox-events；
- 每个实例的 RedisMessageListenerContainer 订阅该频道，收到后 fan-out 给本地 SSE 客户端；
- Redis 发布失败/无订阅者时回退本地 fan-out；
- 消息仍是 conversation JSON，按 tenant 过滤；
- 不改变前端协议。

## 验收

- 单实例：新消息仍能推；
- 跨实例：实例 B 保存会话，实例 A 的 SSE 客户端收到；
- 无 Redis 时回退本地，不影响单实例；
- 旧 JVM 测试不回退。

## Non-goals

- Kafka/NATS；
- 事件重放/持久化；
- 多租户频道隔离（消息内 tenant 过滤）。

## 已实现

- ChannelInboxEventBus 发布到 `shoppilot:channel-inbox-events`；Redis 无订阅者/发布失败回退本地 fan-out；
- ChannelInboxRedisConfig 注册 RedisMessageListenerContainer，收到消息只做本地 fan-out，避免回环；
- 消息仍是 conversation JSON，fan-out 按 tenant 过滤；
- 前端协议不变。

## 验证

- ChannelInboxEventBusTest：publish 发到 Redis 频道、坏 JSON 不抛；
- 全量 verify：726 tests / 122 suites 全绿；
- 活体：本机 SSE 连接后，用 `redis-cli PUBLISH` 模拟另一实例发布，收到 `event:conversation` + 完整 conversation JSON（含 unread=1）。

## Handoff notes

**关键决策**

1. Redis 成功发布时不再本地 fan-out，统一由 listener 回灌，避免同一事件推两次；
2. 发布失败/无订阅者才本地 fallback，单实例/Redis 抖动仍可用；
3. listener 只 fan-out、不再 publish，避免回环。

**验证落点**

- ChannelInboxEventBusTest；
- 活体 SSE：event:ready + event:conversation；
- 全量 logs/verify179.log。

**三个现场追问**

1. 怎么证明跨实例？用 Redis PUBLISH 模拟 B 实例发布，A 实例的 SSE 收到完整事件；
2. 会不会推两次？成功发布后直接 return，只有 listener 回灌，不再本地推；
3. Redis 挂了怎么办？convertAndSend 抛异常时本地 fan-out，单实例功能不丢。
