# ShopPilot Interview Mastery Resources

## Knowledge

- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
  一手语言规范讨论。Use for: 解释 Java 21 虚拟线程的适用边界，以及为什么 100-200 并发无收益、400-800 并发才看到项目里的收益。
- [Spring Security: Architecture](https://docs.spring.io/spring-security/reference/servlet/architecture.html)
  Spring Security 官方过滤器链架构。Use for: 解释 `AuthFilter` 为什么运行在 DispatcherServlet 之前，以及 filter 拒绝为什么不能被 `@RestControllerAdvice` 统一接管。
- [Spring Boot 3.3.5 Reference](https://docs.spring.io/spring-boot/3.3.5/reference/)
  与项目运行时同代的 Spring Boot 一手文档。Use for: 查 Actuator health group、配置绑定、HTTP client、虚拟线程配置和优雅停机的官方语义。
- [SLF4J Manual: MDC](https://www.slf4j.org/manual.html#mdc)
  MDC 的官方说明。Use for: 解释 `traceId`、`tenantId` 在异步边界为何可能丢失，以及手工包装上下文的作用。
- [Redis Documentation: Keyspace](https://redis.io/docs/latest/develop/use/keyspace/)
  Redis 过期和键空间语义的一手文档。Use for: 解释 L1 TTL、负缓存 TTL、知识纪元失效和缓存键设计。
- [Redis Documentation: Distributed Locks](https://redis.io/docs/latest/develop/use/patterns/distributed-locks/)
  Redis 对分布式锁的官方警告与推荐条件。Use for: 解释 singleflight 为什么是“减少重复工作”，但不能被讲成强一致锁。
- [Qdrant Documentation: Filtering](https://qdrant.tech/documentation/concepts/filtering/)
  Qdrant payload filter 的一手文档。Use for: 解释 L2 为什么用 `tenant_id`、`scope`、`intent`、`kb_epoch` 过滤，而不是靠 cosine 阈值承担隔离。
- [Elasticsearch Documentation: Reciprocal Rank Fusion](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/reciprocal-rank-fusion)
  RRF 公式与参数说明。Use for: 解释 `k=60`、两路 top-20 融合 top-5 的排序意义和它不解决什么问题。
- [Resilience4j Documentation: CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker)
  Resilience4j 官方文档。Use for: 解释熔断状态、迁移事件和项目为何只保护 biz-mock 调用。
- [Micrometer Documentation](https://docs.micrometer.io/micrometer/reference/)
  Micrometer 官方参考。Use for: 解释 counter、gauge、timer 的语义，避免把累计值和瞬时值混讲。
- [GitHub Actions: Workflow syntax](https://docs.github.com/en/actions/writing-workflows/workflow-syntax-for-github-actions)
  GitHub Actions 官方语法。Use for: 解释 `.github/workflows/ci-subset.yml` 在干净 runner 上实际覆盖什么、不覆盖什么。
- [JUnit User Guide](https://docs.junit.org/5.11.0/user-guide/)
  JUnit 5 官方用户指南。Use for: 复习参数化测试、动态测试和测试生命周期，便于现场解释 224 条 JVM 测试的结构。
- [ShopPilot Evidence Map](D:/ShopPilot/docs/EVIDENCE.md)
  本仓证据到原始产物的唯一索引。Use for: 每次面试答案中的数字都必须先在这里找落点；没有落点就不说成已证明。
- [ShopPilot Code Map](D:/ShopPilot/docs/CODE_MAP.md)
  本仓模块所有权、请求链路和禁改边界。Use for: 追问“这个行为应该先读哪里”时快速定位源码，而不是按包名猜。

## Wisdom (Communities)

- 真实面试复盘：把原问题、追问链和当时卡点写入 [docs/interview-feedback.md](D:/ShopPilot/docs/interview-feedback.md)
  Use for: 获取外部信号。只有同一缺口被不同面试官问到至少两次，且一天内可补，才按 ADR 0031 考虑重开工作。
- 同事或同学做 20 分钟交叉提问：让他们只拿 [docs/portfolio-interview.md](D:/ShopPilot/docs/portfolio-interview.md) 提问
  Use for: 检验三分钟材料是否足够建立上下文，以及暴露没有证据支撑的口头结论。
- [Stack Overflow: Java](https://stackoverflow.com/questions/tagged/java)
  Use for: 在实现细节与语言语义有争议时寻找可核对的一手答案和反例；不用于替你做面试判断。

## Gaps

- 还没有来自多位真实面试官的重复缺口样本；在积累到两条同缺口信号前，不新增产品功能。
- 还没有异机冷启动证据；回答可复现性时必须保留“同机、同作者”的限制。
- 还没有生产流量验证、Prometheus/Alertmanager 实例和跨实例 singleflight 实测；不要把本机压测讲成生产结论。
