# ShopPilot 生产就绪度评估（2026-09-12）

> 入仓快照说明：这是 2026-09-12 那份评估的仓库内副本，此前只存在于系统临时目录，而临时目录清掉过一次交接件。
> 文内所有计数（处、项、个、文件数）都是**那一刻那份代码上的读数**，实现轮从票 21 起会逐项改掉它们；
> 引用本文件请带日期，不要把里面的数字当现状抄。ADR 0024、0026、0028、0029 里说的「生产就绪度评估」即指本文件。

评估基线：HEAD `1dcaaad`，分支 `main`，工作树 clean。全部结论取自当前代码与本机活体端点读数，标注「实测」的可在本机复跑。
本轮不改仓内一字节，不动任何判据、阈值与业务读数。

## 总判断

不是玩具级 Demo，但也远未到可上线。准确说法是：**领域设计与容错取向已达企业级水准，运行时外壳仍是开发级**。

真正超出 Demo 的部分是「遇到故障时怎么想」：8 种降级原因可枚举、每种落可查工单、限流走同一通道而不是裸 429、
未预期异常不 completeWithError、检索挂了不翻译成「没查到」。这些是有生产经验的人才会写下的判断，且每条都带注释说明理由。

真正卡在开发形态的部分是五件事：没有 prod 配置档、密钥带可直接启动的默认值、没有日志配置与落盘轮转、
真单点不在就绪门里、REST 侧没有统一异常出口。再加一件前置事实：没有任何部署形态。

| 维度 | 状态 | 一句话依据 |
| --- | --- | --- |
| 1 容错机制 | 已具备（有明确缺口） | 熔断参数齐备含半开自动转换，分层超时全覆盖，fail-open/closed 逐点写明取向 |
| 2 日志体系 | 部分具备（最弱项） | 全仓无 logback 配置、无 MDC、无落盘轮转；log.error 仅 2 处 |
| 3 配置管理 | 部分具备 | 20 处 ENV 占位外部化，但 9 个 profile 全是实验档，没有 prod |
| 4 监控与告警 | 部分具备（指标强、告警零） | 34 个自定义指标 + Prometheus 实测可抓；健康组件不含 Qdrant/ES，无一条告警规则 |
| 5 错误处理 | 部分具备 | 流式侧业务/系统分离出色；REST 侧零 ControllerAdvice，12 处手搓错误体 |

## 1 容错机制：已具备（有明确缺口）

已具备

- 熔断：resilience4j-circuitbreaker 2.2.0（仅引 circuitbreaker 模块，未引 retry/bulkhead/ratelimiter）。
  BizMockClient.java 第 50-57 行显式配 failureRateThreshold=50 / slidingWindowSize=20 / minimumNumberOfCalls=10 /
  waitDurationInOpenState=10s / permittedNumberOfCallsInHalfOpenState=3 / automaticTransitionFromOpenToHalfOpenEnabled=true。
- 超时（分层且各下游独立）：HttpClientConfig.java connectTimeout 3s；RedissonConfig.java 第 27-28 行
  connectTimeout 3000 / timeout 2000；ChatController.java 第 47 行 SSE_TIMEOUT_MILLIS=60000 + 第 99 行 onTimeout；
  GatewayProperties.java 把 connectTimeout / readTimeout / warmupTimeout / singleflightWaitTimeout 全做成可配记录字段；
  前端 AbortSignal.timeout(90000)。
- 异常捕获：22 个主源文件出现 catch/重试/熔断/降级语义。分类清晰，例如 BizMockClient.java 第 78-82 行把
  HttpTimeoutException 单独翻译成 ToolStatus.TIMEOUT + 用户话术「业务系统响应超时」，而不是笼统 catch。
- 降级：FallbackReason.java 8 个枚举值（README 第 18 行写「7 种」是减去 USER_REQUESTED，因为那是主动转人工不是被动降级），
  每个都有 userMessage() 面向用户话术；ToolDispatcher.java 第 46 行把 TIMEOUT/UNAVAILABLE 统一判为 degraded。
- fail-open 与 fail-closed 逐点写明取向（这是最像生产代码的地方）：
  - RateLimitService.java 第 107-110 行：Redis 挂则放行，注释「宁可放行也不能把全站变成 503」。
  - IdempotencyService.java 第 51-52 行：Redis 不可用走 bypass 计数器，交给 DB 约束兜底。
  - SessionStore.java 第 63-66 行：读会话异常则按新会话处理，退化成无上下文单轮。
  - HybridRetriever.java 第 192 行：「绝不把挂了翻译成没查到」，degraded 标志贯穿到缓存写回资格。
  - 缓存侧 WriteBackPolicy + ADR 0018 是反向的 fail-closed：稠密召回失能时新答案一律不入库。
- 背压与线程治理：AsyncConfig.java 写回线程池 core 2 / max 8 / 有界队列 2000 / CallerRunsPolicy，
  注释说明「宁可慢一点也不把答案悄悄丢掉」。

缺口

1. 重试几乎不存在。全仓只有 EmbeddingClient 暖机 3 次退避（第 42-43 行常量 3 / 2000ms，只服务离线入库路径，不服务在线请求）。
   在线侧 LLM 与工具调用零重试：一次抖动直接落工单。
2. 无优雅停机。全仓无 server.shutdown: graceful、无 spring.lifecycle.timeout-per-shutdown-phase、无 awaitTermination。
   writeBackExecutor 是 destroyMethod="shutdown" 且不等待，线程是 daemon，停机时在飞写回被静默丢弃。
3. 熔断器只保护 biz-mock 一个下游。LLM（Ollama/DashScope）、Qdrant、ES 都无熔断，靠超时兜；README 第 267 行承认
   向量服务是「真单点」，但没有对应的熔断或副本策略。
4. 无 Bulkhead/隔离：LLM 慢调用与工具慢调用共用虚拟线程，只有队列上限保护写回池。
5. 半开恢复无测试（permittedNumberOfCallsInHalfOpenState 在 test 里零命中）。

建议

- 给在线侧加有界重试：LLM 调用 1 次幂等重试 + 抖动，工具读类调用（查询型）2 次，写类保持不重试（幂等令牌已有，但重试会与
  duplicate_submit 语义打架，需先定策略）。引 resilience4j-retry，别手写循环。
- 补 server.shutdown: graceful + timeout-per-shutdown-phase: 30s，writeBackExecutor 在 @PreDestroy 里
  shutdown() + awaitTermination。
- 给 embedding 与检索各加一个熔断器（或至少一个降级计数器 + 熔断），与 ADR 0018 的写回资格打通。
- 补一条半开恢复的集成测试。

## 2 日志体系：部分具备（最弱项）

已具备

- 分级在用：log.error 2 处、warn 19、info 5、debug 9（gateway 主源）。用法有判断，不是随手打，
  例如 RateLimitService.java 第 168 行把「店铺配额读不到」放 debug（有兜底、非事件），第 109 行 Redis 挂放 warn。
- 关键链路有记录，但走的是业务事件而非日志：SseEventSink 每帧带 traceId，done 帧带引用数与 token 用量，
  aborted()（第 145-151 行）把「本轮中断」当作 status 帧推给客户端；ChatController.java 第 123 行的
  log.error("编排异常 traceId=... conversationId=...") 是唯一的系统级异常留痕。
- 日志不落 git：.gitignore 第 10 行 logs/，与 ADR 0020 的「干净克隆不带历史日志」口径一致。

缺口（成体系地缺）

1. 无任何日志配置文件。rg --files -g 'logback*' -g 'log4j*' 零命中，application.yml 里无 logging: 块。级别全靠 Spring Boot 默认 INFO。
2. 无结构化输出：MDC / JsonLayout / logstash / StructuredArguments / %X{ 全仓零命中；无 micrometer-tracing、
   无 OTel（rg -i otel|micrometer-tracing|brave|sleuth 零命中）。日志是给人读的行，不是给机器查的字段。
3. traceId 不贯穿：traceId 是 ChatController 里的局部 UUID，只进 SSE 帧与那一条 error 日志，不进 MDC（实测计数 0）。
   一次线上降级，你能从工单查到 reason，但无法把同一请求在 LLM、缓存、检索三处的日志行串起来。这是当前最实际的可运维性缺口。
4. 持久化与轮转未托管：没有 logging.file.name 与 appender。日志文件完全由 lib-launch.ps1 用
   Start-Process -RedirectStandardOutput logs\gateway.out 重定向。后果：单文件无限增长、无按天/按大小切分、
   stdout 与 stderr 分流由脚本决定、且这是 PowerShell 专属，换 Linux 部署就没有日志。
5. 无审计日志（谁在什么时候改了运维配置、注入了什么故障）。OpsController 的写操作只往 #timeline 回显，服务端不留痕。
6. 前端无错误上报（实测：页面上报的只有 console.error，无采集端）。

建议（按性价比排）

1. 先解决 traceId 贯穿：在 AuthFilter 里为每个请求生成/复用 traceId 并 MDC.putCloseable，虚拟线程下用
   ThreadLocalAccessor 或手动 copy 保证跨 streamExecutor 不丢；AsyncConfig 加 TaskDecorator 复制 MDC。
   工作量约半天到一天，收益最大。
2. 落 logback-spring.xml：按 profile 分文件/滚动/保留天数，pattern 里带 traceId tenantId conversationId；
   上线档输出 JSON（logstash-logback-encoder）。半天。
3. management.endpoints.web.exposure.include 加 loggers，让运维能运行时改级别（现在只有 health,info,prometheus,metrics）。10 分钟。
4. 运维写操作加审计留痕（谁、什么 token、改成什么）。半天。

## 3 配置管理：部分具备

已具备

- 外部化程度不低：gateway application.yml 里 20 处 ENV 占位默认值；docker-compose.yml 端口全部可偏移且只绑 127.0.0.1；
  GatewayProperties.java 用 record 做类型安全绑定（不用 @Value 散落各处），这点做得对。
- 环境区分有，但区分的是「模型来源与实验开关」：9 个 profile，dev / local / perf / nocache / nosf / no-virtual /
  no-embedding-cache / no-ollama / ingest。其中 dev/local/perf 是 ADR 0001 的三态模式，其余 6 个是归因实验档。
- perf 档明确把限流阈值调到 100000 而不是关掉（第 152-159 行），注释解释「关掉会让 Redisson 整条路径退出被测范围」，有压测纪律。
- .env / .env.local / .env.*-backup 在 .gitignore，入库的只有 .env.example（实测 git ls-files 只出 example）。

缺口

1. 没有 prod / staging 档。9 个 profile 里 6 个是实验开关，没有任何一个表达「部署环境」。所谓「生产配置」目前只能靠
   在 local/dev 之上手工设环境变量，且没有文档约束必须设哪些。
2. 默认值是危险的，且不 fail-fast。三处密钥/开关：
   - 第 45 行 jwt-secret 默认 dev-jwt-secret-change-me-please-0123456789
   - 第 97 行 internal-token 默认 dev-internal-token-change-me
   - 第 123-124 行 ops.enabled 默认 true，ops.token 默认 dev-ops-token
   唯一的守护是 JwtService.java 第 33 行「至少 32 字节」，而那个默认值恰好 48 字节，直接通过。
   结论：一个忘了配环境变量的生产实例会正常启动，用可预测密钥签 JWT，且运维端点开着。
   更要紧的是 dev-ops-token 明文写在前端页面里（index.html 第 108 行 value="dev-ops-token"），
   任何人打开调试台都能从源码读到能改熔断/注故障/清缓存/复位演示单的凭证。
3. 配置项无校验元数据：GatewayProperties 无 @Validated/@Positive 之类，非法值（负超时、0 线程）会跑到运行时才炸。
4. 6 处 http:// 字面量兜底值散在 yml（多为 127.0.0.1 与实验用黑洞端口 59999，可接受但 prod 档应显式覆盖）。
5. 无配置项清单文档：哪些 env 必填、哪些默认不安全，没有一处汇总（.env.example 覆盖度未核）。

建议

1. 加 prod profile，并在启动时断言：非 local/dev/perf 档下，若 jwt-secret/internal-token/ops.token 等于已知默认值则
   直接拒绝启动；ops.enabled 默认改 false，local 档显式打开。半天，收益最高的一条。
2. GatewayProperties 上 @Validated + 约束注解，把超时/池大小/阈值做成有界。半天。
3. 把「必填 env + 默认值风险」写成 docs/deployment.md 的表，并让审计脚本核每条自述可重跑。1 天（含脚本）。
4. 运维令牌从前端源码里移除，改成首次启动一次性打印到 stdout 或走登录态。半天。

## 4 监控与告警：部分具备（指标强、告警零）

已具备

- 健康检查：spring-boot-starter-actuator 两个模块都有；application.yml 第 23-40 行暴露 health,info,prometheus,metrics，
  show-details: always，且 probes.enabled: true 并显式配了 readiness 组（注释说明为什么要 readiness 而不是裸 health：
  StartupInitializer/IngestRunner 是 ApplicationRunner，普通 health 会在知识库还没用时报 UP）。
  实测 /actuator/health 回 status=UP、redis 7.4.9 UP、groups [liveness, readiness]。
- 指标采集：micrometer-registry-prometheus 已引；34 个自定义 shoppilot_* 指标，覆盖缓存命中/写回/负缓存/
  singleflight 合并与超时、embedding 失败与暖机重试、TTFT、LLM 时延与 token 与预算超限、降级 reason 计数、
  限流拒绝按维度、幂等 bypass、写锁忙、工具超时/不可用、triage 早退。
  实测 /actuator/prometheus 返回 200、38595 字节，且 shoppilot_cache_hit_total 这类带真实读数。
- 有 @Scheduled（第 33 行 cacheService.refreshStats()）做指标快照刷新，说明指标不是摆设。
- 压测侧另有 docs/loadtest-report.md + run_loadtest.py + TTFT 扫描脚本，属于离线观测。

缺口

1. 零告警。全仓无 alert / alertmanager / recording rule 任何配置文件。34 个指标里已经存在的那些
   「本应立即有人知道」的信号（embed_unavailable_total 非零、llm_budget_exceeded_total 非零、熔断 OPEN、
   idempotency_redis_bypass_total 上涨）没有任何人会被叫醒。这条对「能否上线」是一票否决级的。
2. readiness 门里缺三个真依赖。实测 /actuator/health 组件只有 diskSpace | livenessState | ping | readinessState | redis。
   没有自定义 HealthIndicator（rg HealthIndicator 零命中），Qdrant 与 ES 的可达性、90 个规则块是否真入库、
   意图质心是否加载完，全都不在就绪判定里。而 README 第 267 行明写向量服务是整个缓存体系的真单点。
   后果：向量库挂了，K8s 认为 pod 健康，流量继续打进来，退化成「全量慢 + 全量不入库」而不是「摘掉坏的」。
3. 无分布式追踪，跨服务（gateway 与 biz-mock）无 trace 传递，只有 X-Internal-Token。
4. Prometheus 只是「可被抓」，仓内无采集配置、无看板（ADR 0013 决定不引 Prometheus/Grafana，ADR 0015 只补了指标暴露，
   看板与告警仍未落）。README 的 22ms/1013QPS 来自离线压测报告，不是在线 SLO 观测。
5. 无日志/指标关联（traceId 不在 MDC，第 2 节）。

建议

1. 加 3 个 HealthIndicator 并挂进 readiness：knowledgeBaseHealth（规则块数 == 期望值、epoch 已推进）、
   vectorStoreHealth、lexicalStoreHealth（各自一次轻量 ping，带超时，挂了报 DOWN 而不是抛）。1 天。
2. 落最小告警集：cache_embed_unavailable_total 大于 0、llm_budget_exceeded_total 大于 0、
   idempotency_redis_bypass_total 增速大于 0、熔断 OPEN 持续 60s、P99 超阈值。用 Prometheus rule YAML，1 天。
3. management.endpoint.health.show-details 从 always 收成 when-authorized（生产会把内部版本暴露到公网端点）。10 分钟。
4. 暴露 loggers；prometheus 端点加鉴权或只走内网。半天。

## 5 错误处理：部分具备

已具备

- 业务错误与系统错误在 SSE 通道上分离得干净：FallbackReason 枚举 8 值 + 每值一句面向用户的话术
  （FallbackReason.java 第 14-24 行，内部 reason 与 userMessage() 两层），系统异常走
  ChatController.java 第 120-126 行的 catch：记 error 日志（带 traceId）+ 推 aborted 状态帧 + complete()，
  注释明确解释为什么不能 completeWithError（容器会拿 text/event-stream 渲染错误体，客户端连已收到的帧都读不到）。
- 限流不当错误处理：第 100-112 行在限流时也先发 meta 再发 fallback（保证转人工落成可查工单），
  非流式路径第 77-81 行则标准 429 + Retry-After 头。两种通道各自自洽。
- 输入校验成体系：@Valid @RequestBody ChatRequest（@NotBlank @Size(max=500)）、
  modifyOrder 缺 orderId 时返回 MISSING_ARGUMENT 而不是猜、未注册工具名返回 NOT_FOUND + 未注册的工具 X
  （AgentStateMachine.java 第 270 行）。
- 安全侧错误语义有思考：跨租户资源不存在时返回 404 而不是 403（注释：403 等于承认这单存在）；伪造 token 401；
  客户端自带 tenantId 时忽略并打告警（AuthFilter.warnOnClientSuppliedTenant）。

缺口

1. REST 侧零全局异常处理。rg 'ControllerAdvice|ExceptionHandler|ResponseStatus|ProblemDetail|ErrorAttributes'
   在 gateway 主源零命中。任何 controller 里漏出的 RuntimeException 会掉到 Spring 默认 /error，
   返回一个不带 traceId、形状与业务体完全不同的 JSON。
2. 错误体是手搓字符串：OpsController.java 里 11 处、AuthFilter.java 1 处 error 字面量拼接，
   无统一信封、无 code、无 traceId、无 i18n 边界。同一件事（运维令牌不对）在不同端点有两种不同文案
   （invalid or disabled ops token 4 处 / ops endpoint disabled 2 处，实测统计），前端与调用方无法稳定判别。
   根因是 requireOps() 把「开关关闭」与「令牌不匹配」合成一个布尔（OpsController.java 第 232-235 行）。
3. 无业务错误码。有 ToolStatus 和 FallbackReason 两套枚举，但没有对外的 code 字段与码表；HTTP 状态码只用了
   401/403/409/429/502 五档，400 走 Spring 默认体、500 走 Spring 默认体，两者形状与自研体不一致。
4. 校验失败无定制出口：@Valid 失败由 Spring 默认处理，返回体形状与业务体不同构
   （实测调试台只显示「请求被拒绝：400」，细节丢失，用户不知道是 500 字上限）。
5. 没有把「系统级不可用」与「这单办不了」在 HTTP 语义上区分：前端只能靠文本匹配。

建议

1. 加 RestControllerAdvice：统一错误信封 code/message/traceId/detail，映射 ToolStatus 与 FallbackReason 到码表，
   MethodArgumentNotValidException 返回 400 且带字段错误，兜底 Exception 返回 500 且只回 traceId 不回堆栈。1 天。
2. 把 12 处字符串错误体收敛到该出口，端点返回类型从 ResponseEntity 字符串换成 DTO 或 ProblemDetail。1 天。
3. 定一份 docs/error-codes.md 码表，并让审计脚本核「文档里每个 code 在代码里存在」。半天。
4. OpsController 的 403 文案统一，并把 disabled 与 invalid token 分开，便于运维判断是没开还是没对。10 分钟。

## 差距清单：从跑通到接近落地

工作量按「一个熟悉本仓的人」估，含测试与文档自证，不含审批等待。顺序按依赖与止损排。

高优先级（上线阻断，合计 4-5 人日）

| 序 | 差距 | 现状证据 | 工作量 |
| --- | --- | --- | --- |
| 1 | 密钥与运维端点缺 fail-fast | application.yml 45/97/123-124 行默认值可启动；JwtService 只查长度；dev-ops-token 明文在 index.html 108 行 | 0.5 天 |
| 2 | readiness 不含向量/检索/知识库 | 实测组件仅 diskSpace,livenessState,ping,readinessState,redis；HealthIndicator 零命中 | 1 天 |
| 3 | 零告警 | 全仓无 alert 规则；34 个指标里至少 5 个是「该立刻有人知道」的信号 | 1 天 |
| 4 | 日志无配置、traceId 不贯穿、无轮转 | 无 logback 文件、MDC 零命中、日志靠 PS 重定向，换 Linux 就没日志 | 1.5 天 |
| 5 | REST 侧无全局异常出口 | RestControllerAdvice 零命中，12 处手搓错误体 | 1 天 |

顺序理由：1 与 2 是「带病上线会立刻出事」类，先做；3 依赖 2 的指标语义清楚；4 与 5 独立可并行；
且第 4 项的 traceId 是第 5 项错误体里 traceId 字段的前置。

中优先级（可靠性与可运维性，合计 5-6 人日）

| 序 | 差距 | 现状证据 | 工作量 |
| --- | --- | --- | --- |
| 6 | 在线侧零重试 | 只有 EmbeddingClient 暖机重试；LLM/工具一次抖动即落工单；未引 resilience4j-retry | 1 天 |
| 7 | 无优雅停机，在飞写回会丢 | 无 server.shutdown: graceful；AsyncConfig daemon 线程 + 不 await | 0.5 天 |
| 8 | LLM/embedding/检索无熔断 | 熔断只包 biz-mock；README 267 行自称向量是真单点 | 1 天 |
| 9 | 无分布式追踪 | 无 micrometer-tracing/OTel 依赖 | 1.5 天 |
| 10 | 配置项无校验、无必填清单 | GatewayProperties 无 @Validated | 0.5 天 |
| 11 | 半开恢复与 Redis 故障无测试 | gateway 14 个测试文件（全仓 18：biz-mock 3、tool-api 1），无 SpringBootTest，多为单元与 ArchUnit | 1 天 |

低优先级（工程底座与体验，合计 3-5 人日，可并行推）

| 序 | 差距 | 现状证据 | 工作量 |
| --- | --- | --- | --- |
| 12 | 无部署形态：零 Dockerfile、零 CI、网关不容器化 | Test-Path .github 为 False；全仓唯一 compose 只起中间件 | 2-3 天 |
| 13 | 业务层是 mock：H2 内存库 + 演示签 token + 合成语料 | shoppilot-biz-mock 第 10 行 jdbc:h2:mem；README 257-260 行自登 | 换真库/真 IdP 属新项目量级 |
| 14 | 跨实例行为未验 | README 258 行「singleflight 只在单实例验证过」 | 1-2 天（要起两实例） |
| 15 | 调试台 12 项交互缺陷 | 本轮 UI 走查（假健康灯、11.6s 无反馈、窄屏藏时间线等） | 1-2 天 |
| 16 | 异机未验 | README 166 行自述「同一台物理机、同一位作者」 | 0.5 天（找人跑一次 clean_clone_check.ps1） |

## 结论

架构层面的判断力和降级设计已经过了「玩具」这条线，没过的是运维线：没有生产配置档、没有日志体系、没有告警、
没有统一错误出口、没有部署形态。补齐上面 5 项高优先级（4-5 人日）之后，它可以从「能演示、能自证的验证件」
变成「敢挂出去跑的灰度服务」；中优先级再 5-6 人日，才谈得上企业级。
