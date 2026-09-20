# ShopPilot Code Map

这份地图回答两个问题：一个需求应该先改哪里，以及哪些“看起来可以合并”的包不应在无 ADR 的情况下移动。它描述现状，不替代 `CONTEXT.md` 术语和 `docs/adr/` 决策。

## 三个 Maven 模块

| 模块 | 职责 | 依赖方向 |
| --- | --- | --- |
| `shoppilot-tool-api` | 跨进程工具契约：意图、工具名、请求/响应 DTO、OpenAPI/Function Schema 生成 | 不依赖 gateway 或 biz-mock |
| `shoppilot-biz-mock` | 业务系统替身：订单、物流、优惠券、退款、工单、种子数据、租户隔离和故障注入 | 依赖 `shoppilot-tool-api` |
| `shoppilot-gateway` | 买家入口：鉴权、限流、意图、缓存、检索、Agent 编排、SSE、降级、运维指标 | 依赖 `shoppilot-tool-api`，经 HTTP 调 biz-mock |

## Gateway Package Ownership

| Package | 责任 | 常见首改文件 |
| --- | --- | --- |
| `identity` | JWT 验签/发签、租户与买家上下文、trace 坐标、mock 身份绑定条件 | `AuthFilter`、`JwtService`、`TenantContext`、`RequestTrace` |
| `ratelimit` | 店铺/买家双维限流与 SSE `rate_limited` 语义 | `RateLimitService` |
| `triage` | T0 规则、T1 质心、T2 模型级联；cacheable/dynamic 判定 | `TriageEngine`、`T0RuleLayer`、`T1CentroidLayer` |
| `cache` | L1/L2 查询、语义阈值、极性守卫、写回资格与异步池 | `CacheService`、`L2SemanticCache`、`PolarityGuard`、`WriteBackPolicy`、`WriteBackPool` |
| `knowledge` | embedding、Qdrant/ES 客户端、RRF 混合检索、知识纪元 | `HybridRetriever`、`EmbeddingClient`、`QdrantRestClient`、`EsRestClient`、`KbEpoch` |
| `ingest` | Markdown 政策切块与离线入库 | `MarkdownChunker`、`IngestRunner` |
| `llm` | local/dev/perf 三种模型实现、超时/预算和 LLM 故障注入 | `LlmGateway`、`OllamaLlmClient`、`OpenAiCompatibleLlmClient`、`MockLlmClient`、`TokenBudget` |
| `agent` | 10 状态编排、工具派发、会话、幂等、fallback 与工单 | `AgentStateMachine`、`ToolDispatcher`、`BizMockClient`、`FallbackService` |
| `web` | 同步/SSE 聊天入口、运维接口、错误信封、调试台事件出口 | `ChatController`、`SseEventSink`、`OpsController`、`ApiErrorWriter` |
| `config` | 配置绑定/校验、健康组、dev 默认值、线程与 HTTP 客户端、运行时指标 | `GatewayProperties`、`ValidatedServerProperties`、`DevDefaultsPolicy`、`RuntimeStateMetrics` |

## Biz-Mock 与 Tool Contract

| 区域 | 责任 | 常见首改文件 |
| --- | --- | --- |
| `bizmock/domain` | Hibernate 实体和业务状态枚举 | `Order`、`Refund`、`Ticket`、`TicketStatus` |
| `bizmock/repo` | tenant-aware repository 与唯一约束 | `OrderRepository`、`RefundRepository`、`TicketRepository` |
| `bizmock/db/migration`（资源） | **模式的唯一产生源**：Flyway 版本化迁移（`ddl-auto` 已是 `validate`）。索引的真相源在这里，实体上的 `@Index` 注解在 `validate` 下不再被校验、只作文档 | `V1__baseline.sql`、`V2__index_feedback_review.sql`；回滚约定在 `db/rollback/U1__baseline_down.sql` |
| `bizmock/service` | 查询、改地址、退款、工单、归属和状态前置校验 | `BizMockService` |
| `bizmock/web` | 内部工具接口、工单接口、内部 token 校验、故障注入 | `ToolController`、`TicketController`、`InternalAuthFilter` |
| `tool/request` / `tool/view` | 跨模块请求和响应 DTO | `QueryOrderDetailRequest`、`ToolResponse`、`TicketView` |
| `tool/schema` | 工具 JSON schema 生成 | `ToolSchemaGenerator` |

## 请求主链路

同步与 SSE 请求都从 `web/ChatController` 进入，主链路与源码落点如下：

| 阶段 | 源码落点 |
| --- | --- |
| 验签与租户上下文 | `identity/AuthFilter`、`identity/TenantContext` |
| trace 与 MDC | `identity/RequestTrace`、`config/AsyncConfig` |
| 限流 | `ratelimit/RateLimitService` |
| 意图判定与缓存准入 | `triage/TriageEngine`、`triage/T0RuleLayer`、`triage/T1CentroidLayer` |
| L1/L2 缓存 | `cache/CacheService`、`cache/L1Cache`、`cache/L2SemanticCache`、`cache/PolarityGuard` |
| 混合检索 | `knowledge/EmbeddingClient`、`knowledge/QdrantRestClient`、`knowledge/EsRestClient`、`knowledge/HybridRetriever` |
| 模型计划 | `llm/LlmGateway`、`llm/LlmClient`、`llm/LlmTypes` |
| 状态机与工具循环 | `agent/AgentStateMachine`、`agent/AgentState`、`agent/ToolDispatcher` |
| 业务调用 | `agent/BizMockClient` -> `bizmock/web/ToolController` -> `bizmock/service/BizMockService` |
| SSE 事件 | `agent/EventSink`、`web/SseEventSink` |
| 降级与工单 | `agent/FallbackService`、`agent/FallbackReason` |
| 缓存写回 | `cache/WriteBackPolicy`、`cache/WriteBackPool` |

调试台是 `gateway/src/main/resources/static/index.html`，它只访问 gateway 同源接口；浏览器验收在 `scripts/verify-console.mjs`。

## Test Map

| 风险面 | 主要测试/脚本 |
| --- | --- |
| 配置、健康、指标、启动默认值 | `config/*Test`、`IdentityArchitectureTest`、`ServedConsoleHidesDevDefaultsTest` |
| 身份和异步 trace | `identity/AuthFilterTest`、`MockIdentityConditionTest`、`RequestTraceTest`、`web/TraceCorrelationAcrossAsyncTest` |
| 缓存资格、L2、极性、写回池 | `cache/*Test` |
| 意图 T0 | `triage/T0RuleLayerTest` |
| embedding 合并、预热、Qdrant wire | `knowledge/*Test` |
| LLM 兼容与 token 预算 | `llm/OpenAiCompatibleLlmClientTest`、`llm/TokenBudgetTest` |
| 会话归属 | `agent/ConversationOwnershipTest` |
| fallback 与幂等 | `agent/FallbackReasonTest`、`agent/IdempotencyServiceTest` |
| 业务租户隔离、幂等、工单工作流 | `shoppilot-biz-mock/src/test/java/...` |
| 模式迁移与 schema 一致性 | `bizmock/SchemaMigrationTest`（迁移已应用、9 表齐备、`ddl-auto` 仍是 validate） |
| 慢查询计划与索引守卫 | `bizmock/SlowQueryPlanTest`（含被否决的那笔优化，见 `docs/slow-query-optimization-2026-09-21.md`） |
| 跨模块 schema | `shoppilot-tool-api/src/test/java/.../ToolSchemaGeneratorTest` |
| 活体防线 | `scripts/verify-*.ps1`、`scripts/verify_l2_filters.py`、`scripts/verify_eval_judge.py` |
| 覆盖率棘轮 | `scripts/check_coverage.py`（读各模块 `jacoco.xml`，按模块比 LINE 门槛） |
| 网关主链路 JVM 集成 | `web/GatewayMainPathJvmTest`（缓存命中、工具循环、fallback） |
| 干净 runner JVM 门禁 | `.github/workflows/ci-subset.yml` |

## 已知代码债候选

这些是整理时确认的候选，不表示应该立刻重构：

- `agent/AgentStateMachine.java` 约 665 行，同时承担状态循环、Prompt、检索、缓存写回、fallback 和槽位抽取。未来按行为边界拆时，先用 ticket 固定现有测试和 SSE 契约。
- `agent/AgentStateMachine` 与 `agent/ToolDispatcher` 各自持有部分槽位策略。若继续出现参数校验漂移，可评估 `ToolInputPolicy`，不要为了“少一个类”先合并。
- `web/ChatController` 的同步和流式路径重复限流、工单和 fallback 决策。若新增准入规则，优先抽 `ChatAdmission` 一类深模块，避免两条路径再次漏改。
- `agent/ConversationOwnershipTest` 位于 agent 包，但实际读取并断言 `static/index.html`；若继续扩调试台断言，应迁到 web/console seam，而不是继续在 agent 测试里堆前端细节。
- `knowledge/HybridRetriever` 与 `ingest/MarkdownChunker` 缺少聚焦单测；只有在相关行为变更时补，不做全仓补测运动。
- `identity` 与 `web` 之间存在通过 `ApiErrorWriter` 形成的包环。ADR 0028 已限定错误信封只管网关自产错误；不重开错误信封协议时，不为消环做大搬迁。

## 明确不要动

- 不把 `cache`、`triage`、`knowledge`、`llm`、`identity`、`ratelimit` 合并成一个大 `service` 包；当前能力边界比包数量更有价值。
- 不重命名 `.scratch/shoppilot-mvp`，不移动旧 ticket、审计、`eval/results` 或 `loadtest/results` 的历史文件。
- 不把本机 `logs/`、根目录 `hs_err_pid*.log` 或 `replay_pid*.log` 当作可在干净克隆复现的代码资产。
- 不在普通功能票里顺带调整 package、版本、构建链或公共接口；那类改动需要独立 ticket 和迁移证据。
