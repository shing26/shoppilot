# Mock 业务中台是独立进程，工具调用走真 HTTP 边界

Context: 任务书技术矩阵写"反射执行本地 JPA 业务接口"，但痛点第 4 条要求演示"外部依赖（物流、支付接口）抖动时的平滑降级"。同进程方法调用不存在网络抖动，超时/熔断/降级无处可挂，Sprint 3 的大促防护会退化成纸面描述。决定：`shoppilot-gateway`(:8082) 与 `shoppilot-biz-mock`(:8091) 从第一天就是两个独立进程，工具调用经 `RestClient` / `@HttpExchange` 走 HTTP，网关侧统一套超时 + 熔断 + 降级；biz-mock 暴露 `delayMs` / `failRate` 故障注入参数。

Considered Options: 单 JVM 内 self-HTTP loopback（省 256 M 内存但边界是假的）；同进程反射调 JPA（任务书原方案，无法演示降级）。

Consequences:
- Maven 父 pom 下三个模块：`shoppilot-gateway`、`shoppilot-biz-mock`、`shoppilot-tool-api`（工具入参/出参 DTO 与 OpenAPI schema 契约）。Function Schema 与实际调用签名同源，避免描述与实现漂移。
- 压测时 Ollama 与 ES 可按需停掉腾内存；biz-mock 自身也要能被压，它的 H2 连接池会成为瓶颈点，需要与网关的连接池一起调。
- 端到端时延里多了一次本机 HTTP 往返（亚毫秒级，可忽略），但 TP99 30ms 的缓存命中路径不经过它。
