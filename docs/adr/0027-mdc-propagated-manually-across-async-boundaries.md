# traceId 用手工包装跨线程传播，不引 context-propagation

Context: `traceId` 的现状是两头有、中间空。`ChatController` 每个流式请求生成一个 UUID，`SseEventSink` 把它回显进 `done` 帧发给客户端，而全仓只有一行 `log.error` 用到它，MDC 零命中——`SessionStore`、`ToolDispatcher`、`BizMockClient`、`CacheService` 各自拿自己的 logger，谁都能打出时间戳却认不出是谁的请求。网关的异步路径本来就跨线程：缓存写回发生在响应之后，工单升级走 `AsyncConfig` 那批 daemon 线程，而这些恰好是最需要认回请求的一段。`spring.threads.virtual.enabled` 又开着，MDC 这个 ThreadLocal 在换线程处更容易掉。

决定：`traceId`、`tenantId`、`customerId`、`conversationId` 四个键在鉴权入口一次性装进 MDC，装填点从 `ChatController` 挪到 `AuthFilter`（那里已经握有身份与会话，且已经在给缺失的会话 id 补 UUID），SSE 与 REST 共用同一来源。异步提交点用一层手工包装函数接上上下文、用完清理。不引 `io.micrometer` 的 context-propagation。

## Considered Options

- 引 context-propagation 库：否决。它本身不是分布式追踪，但会带来一个全仓都得学会统一使用的新抽象，把 ADR 0024 刚划下的「本轮不做分布式追踪」那条线搅浑。
- 不跨线程、明写边界：否决。留下的缺口正好落在「答案已发出、写回未落地」这条降级链上，而那本是这个项目最能自证的一段。

## Consequences

- 新增异步提交点会静默丢 traceId，这一条机器拦不住。本仓有 ArchUnit 的 `IdentityArchitectureTest`，但「任务必须经包装函数提交」这条谓词要先想清楚依赖形状，本轮不做，按欠账照登。
- 钉两条用例：同一请求内所有日志事件的 `traceId` 相等且非空；并发两个请求的上下文不得互相污染。
- 身份四元组进日志是本仓在合成数据上的取巧。真实投产（ADR 0024 划出去的那一步）必须换成假名标识，这句写进 README，不留到将来才发现。
