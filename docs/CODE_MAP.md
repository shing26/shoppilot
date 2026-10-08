# ShopPilot Code Map

这份地图回答两个问题：一个需求应该先改哪里，以及哪些“看起来可以合并”的包不应在无 ADR 的情况下移动。它描述现状，不替代 `CONTEXT.md` 术语和 `docs/adr/` 决策。

## 三个 Maven 模块

| 模块 | 职责 | 依赖方向 |
| --- | --- | --- |
| `shoppilot-tool-api` | 跨进程工具契约：意图、工具名、请求/响应 DTO、OpenAPI/Function Schema 生成。**换代（round29 / ADR 0062）**：新增 `tool/config/PostureGuard`——凭据姿势原语（`isLoopback` fail-closed / `stillDefault` / 仓库默认值常量），网关的 `DevDefaultsPolicy` 委托它，biz-mock 与 ticket 的 `PostureGuardConfiguration` 用它做非回环启动阻断 | 不依赖 gateway 或 biz-mock |
| `shoppilot-biz-mock` | 业务系统替身：订单、物流、优惠券、退款、种子数据、租户隔离和故障注入 | 依赖 `shoppilot-tool-api` |
| `shoppilot-gateway` | 买家入口：鉴权、限流、意图、缓存、检索、Agent 编排、SSE、降级、运维指标 | 依赖 `shoppilot-tool-api`，经 HTTP 调 biz-mock 与 ticket |
| `shoppilot-ticket` | 工单与坐席服务（round23 票 72 / ADR 0053）：统一工单实体、规则分流、坐席领取、SLA、规则表 | 依赖 `shoppilot-tool-api`，**工单数据随服务走**；业务侧与网关经 HTTP 调它 |

## Gateway Package Ownership

| Package | 责任 | 常见首改文件 |
| --- | --- | --- |
| `identity` | JWT 验签/发签、租户与买家上下文、trace 坐标、mock 身份绑定条件 | `AuthFilter`、`JwtService`、`TenantContext`、`RequestTrace` |
| `ratelimit` | 店铺/买家双维限流与 SSE `rate_limited` 语义 | `RateLimitService` |
| `triage` | T0 规则、T1 质心、T2 模型级联；cacheable/dynamic 判定 | `TriageEngine`、`T0RuleLayer`、`T1CentroidLayer` |
| `cache` | L1/L2 查询、语义阈值、极性守卫、写回资格与异步池 | `CacheService`、`L2SemanticCache`、`PolarityGuard`、`WriteBackPolicy`、`WriteBackPool` |
| `knowledge` | embedding、Qdrant/ES 客户端、RRF 混合检索、知识纪元 | `HybridRetriever`、`EmbeddingClient`、`QdrantRestClient`、`EsRestClient`、`KbEpoch` |
| `ingest` | Markdown 政策切块与离线入库 | `MarkdownChunker`、`IngestRunner` |
| `llm` | local/dev/perf 三种模型实现、超时/预算、显式输出上限与 LLM 故障注入 | `LlmGateway`、`OllamaLlmClient`、`OpenAiCompatibleLlmClient`、`MockLlmClient`、`TokenBudget` |
| `agent` | 10 状态编排、工具派发、会话、幂等、fallback 与工单；计划步骤与上下文组成的观测出口（ADR 0044 票 48/49）；**请求级幂等回放**（模型之前，ADR 0046 票 58）与**退款受理出口**（ADR 0047 票 59） | `AgentStateMachine`、`AgentResult`、`ToolDispatcher`、`BizMockClient`、`FallbackService`、`IdempotencyService`、`ReplayReply` |
| `web` | 同步/SSE 聊天入口、运维接口、错误信封、调试台事件出口 | `ChatController`、`SseEventSink`、`OpsController`、`ApiErrorWriter` |
| `config` | 配置绑定/校验、健康组、dev 默认值、线程与 HTTP 客户端、运行时指标 | `GatewayProperties`、`ValidatedServerProperties`、`DevDefaultsPolicy`、`RuntimeStateMetrics` |

## Biz-Mock 与 Tool Contract

| 区域 | 责任 | 常见首改文件 |
| --- | --- | --- |
| `bizmock/domain` | Hibernate 实体和业务状态枚举 | `Order`、`Refund`、`Ticket`、`TicketStatus` |
| `bizmock/repo` | tenant-aware repository 与唯一约束 | `OrderRepository`、`RefundRepository`、`TicketRepository` |
| `bizmock/db/migration`（资源） | **模式的唯一产生源（H2，默认档）**：Flyway 版本化迁移（`ddl-auto` 已是 `validate`）。索引的真相源在这里，实体上的 `@Index` 注解在 `validate` 下不再被校验、只作文档。**换代（round28 / ADR 0061）**：`bizmock/db/migration-postgresql/`（**平级目录，不是子目录**——Flyway 对 `classpath:db/migration` 递归扫描，子目录会同版本撞车）是持久档的 PG 方言产生源，两侧版本钟由 `PostgresMigrationParityTest` 钉住；持久档配置在 `application-postgres.yml` | `V1__baseline.sql`、`V2__index_feedback_review.sql`；回滚约定在 `db/rollback/U1__baseline_down.sql`（PG 回滚演练登记给 B2） |
| `bizmock/service` | 查询、改地址、退款、**退款审核（受理/放行/驳回 + 推导回滚）**、工单、归属和状态前置校验 | `BizMockService` |
| `bizmock/web` | 内部工具接口、工单接口、**退款审核端点**（B3 起 `review` 要求 `X-Actor-Authenticated: true`，未认证 → 403）、内部 token 校验、故障注入 | `ToolController`、`TicketController`、`RefundReviewController`、`InternalAuthFilter` |
| `tool/request` / `tool/view` | 跨模块请求和响应 DTO | `QueryOrderDetailRequest`、`ToolResponse`（`ToolStatus.PENDING_APPROVAL`）、`OrderView`（`RefundReviewState`）、`TicketView` |
| `tool/schema` | 工具 JSON schema 生成 | `ToolSchemaGenerator` |

## 请求主链路

同步与 SSE 请求都从 `web/ChatController` 进入，主链路与源码落点如下：

| 阶段 | 源码落点 |
| --- | --- |
| 验签与租户上下文 | `identity/AuthFilter`、`identity/TenantContext` |
| trace 与 MDC | `identity/RequestTrace`、`config/AsyncConfig` |
| 限流 | `ratelimit/RateLimitService` |
| 意图判定与缓存准入 | `triage/TriageEngine`、`triage/T0RuleLayer`、`triage/T1CentroidLayer` |
| 请求级幂等回放（模型之前、情绪门之后） | `agent/IdempotencyService.lookupByClientToken`、`agent/ReplayReply` |
| L1/L2 缓存 | `cache/CacheService`、`cache/L1Cache`、`cache/L2SemanticCache`、`cache/PolarityGuard` |
| 混合检索 | `knowledge/EmbeddingClient`、`knowledge/QdrantRestClient`、`knowledge/EsRestClient`、`knowledge/HybridRetriever` |
| 模型计划 | `llm/LlmGateway`、`llm/LlmClient`、`llm/LlmTypes` |
| 状态机与工具循环 | `agent/AgentStateMachine`、`agent/AgentState`、`agent/ToolDispatcher` |
| 业务调用 | `agent/BizMockClient` -> `bizmock/web/ToolController` -> `bizmock/service/BizMockService` |
| 退款审核（人工闸门） | `bizmock/web/RefundReviewController` -> `bizmock/service/BizMockService.reviewRefund`；网关代理 `web/OpsController`（`/ops/refunds/*`，B3 起 `reviewRefund` 与 `refundReviewQueue()` 均为 `staff()`-only）；调试台面板 `static/index.html`（B3 起只读）；坐席工作台 `static/workspace/`（B3 起可审核） |
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
| LLM 兼容与 token 预算 | `llm/OpenAiCompatibleLlmClientTest`、`llm/OllamaLlmClientTest`（输出上限按 `options.num_predict` 下发）、`llm/TokenBudgetTest` |
| 向量化分段耗时与计划/上下文观测 | `knowledge/EmbeddingLatencyTimerTest`（三桶与三计数器同分法）、`web/GatewayMainPathJvmTest` 的 `planStepsAreReportedInExecutionOrder` / `contextCompositionMirrorsCitationsAndHistory`、`web/SseEventSinkTest` 的 `done` 帧字段 |
| 会话归属 | `agent/ConversationOwnershipTest` |
| fallback 与幂等 | `agent/FallbackReasonTest`、`agent/IdempotencyServiceTest` |
| 请求级幂等回放 | `agent/IdempotencyServiceRequestReplayTest`（往返性质，Map 假 Redis）、`web/GatewayMainPathJvmTest.repeatedRequestReplaysTheFirstResultWithoutCallingTheModel` |
| 退款审批闸门 | `agent/ToolDispatcherApprovalTest`（受理态落幂等）、`bizmock/RefundReviewTest`（三态迁移 / 推导回滚 / 跨租户 / 重复审核；独立 H2）、`web/GatewayMainPathJvmTest.refundApprovalStopsAtTheGateWithoutASecondModelHop`、`web/RestErrorEnvelopeTest.refundReviewProxiesWithTenantContext` |
| 跨模块工具契约（审批策略与新状态值） | `shoppilot-tool-api/src/test/java/.../ToolContractApprovalTest` |
| 检索融合录放门（round22 票 64） | `knowledge/RetrievalFusionReplayTest`（JVM，重算的唯一 owner）、`ingest/MarkdownChunkerTest`、`scripts/retrieval_gate.py`（只校验不复算）+ `eval/retrieval-fixture-*.json`（append-only，录制器 `scripts/record_retrieval_fixture.py`） |
| task 级判据（round22 票 65） | `scripts/eval_task.py`（判据 + 14 条夹具）+ `eval/cases-part8-task.jsonl`；活体入口 `run_tool_eval.py --task` |
| 告警规则（round22 票 67） | `ops/alerts/shoppilot.rules.yml` + `ops/alerts/shoppilot.rules.test.yml`（`promtool test rules`，CI 的 `Alert rules unit test` 步） |
| 业务租户隔离、幂等 | `shoppilot-biz-mock/src/test/java/...` |
| 工单工作流、规则分流、坐席领取、SLA、慢查询证据 | `shoppilot-ticket/src/test/java/...`（round23 票 72 随数据搬走） |
| **容器档**（干净克隆一条命令起全栈，round24） | `docker-compose.yml` 的 `full` profile + `Dockerfile`（`ARG MODULE`）+ `scripts/up.ps1 -Containerized` / `down.ps1 -Containerized`；偏移量与凭证走**每份克隆自己的 `.env`**；默认档（本机 JVM）不受影响 |
| 坐席工作台页面（登录/队列/领取/处理/释放） | `frontend-workspace/src/`，产物落 `shoppilot-gateway/src/main/resources/static/workspace/`（round23 票 73；**登录面在 round25 票 83 换成坐席账号**；浏览器只经网关，不直连内部服务） |
| **身份域**（账号 + 角色守卫，round25 票 80-82） | **落在 `shoppilot-biz-mock`**，不在网关、也不是第五个服务——ADR **0058** 有完整论证（0053 的资源账 + 网关没有数据源 + 账号主体是买家）。⚠️ **0053 给 biz-mock 的定义（订单/物流/退款/工具/审批）里没有身份**，按旧清单去找会找不到。入口：`bizmock/domain/UserAccount`（表 `users`，Flyway `V7`）、`bizmock/service/IdentityService`、`bizmock/web/IdentityController`（`/api/identity/*`，内部凭证之后）；登录面在网关 `identity/AccountController`（`/auth/login` `/auth/register` `/auth/me`）+ `identity/IdentityClient`；角色守卫在网关 `web/OpsController.guardedTicket`；契约在 `shoppilot-tool-api` 的 `tool/identity/` 与 `tool/audit/{Actor,ActorHeaders}` |
| 自报身份的退役面（round25 票 82） | **下游不再读自报头**：biz-mock 与 ticket 服务只认 `X-Actor` + `X-Actor-Authenticated`，两者都由网关从已验签令牌解出（`grep -rn "X-Agent\|X-Reviewer"` 在两边只剩注释与测试）。网关**仍然接受**客户端的 `X-Agent` / `X-Reviewer`——但只在**没有账号身份**时作为自称来源（ops 运维面那条路径），**盖不过令牌**，且产出的 actor 一律标成未认证 |
| **结果回流**（工单结单 → 渠道出站，round26） | 三处落点，缺哪一半都算断链：**① 工单带渠道与目标** `shoppilot-ticket` 的 Flyway `V2` + `Ticket.attachDelivery`；**② 生产端** `ticket/audit/OutboundPublisher`（由 `WorkItemService.resolve` 在结单成功后调用，web 渠道按规则不发但计数）；**③ 消费端** `gateway/channel/OutboundDeliveryService`（网关进程内，后台单线程 5 s 一跳，`eventId` 幂等，**只有 2xx 才算送达**，失败落 `CHANNEL_RECEIPT` 工单）。投递目标从入站来：`channel/WebhookAdapter` 的可选 `callbackUrl` / `EmailAdapter` 的 `from`。门禁 `scripts/verify-outbound.mjs` 起本地回声端点 |
| **入站会话与幂等派生**（两格，round31 票 96 / ADR 0065） | `channel/ChannelAdapter` 的 `NormalizedChat` 五格（新增 `conversationId` + `clientToken`）与静态派生辅助：`<渠道label>:chat:<sessionId|customerId>`、`<渠道label>:msg:<messageId>`——**渠道标签与聊天维度缺一不可**（变异 B1/B2 钉住）。派生在 `WebhookAdapter`/`EmailAdapter`（payload 可选 `messageId`/`sessionId`，上限 255；渠道标签取自 `ChannelContext`，不进 NormalizedChat——两处真相）；web 渠道不派生（`WebSseAdapter` 行为零变更）。接线在 `web/ChannelController.handle`：**显式请求头 `X-Conversation-Id` 与显式 `idempotencyToken` 恒优先**，派生只补缺——会话坐标替换只动 `conversationId`，租户与买家身份一字不动（ADR 0025）；响应带 `conversationId`。测试 `web/ChannelDerivedSessionJvmTest`（10 条）+ `identity/AuthFilterTest` 现状锚。真实 IM 渠道（票 97/113 飞书）到这两个字段的映射落在各自的适配器 |
| **飞书适配器与录放门**（round31 票 97 / ADR 0060 决策 4 + 0065） | `channel/FeishuAdapter`：吃飞书 v2 原样事件（`im.message.receive_v1`），**仅单聊（p2p）文本**，范围外当场拒；`content` 是字符串化 JSON 二次解析。**不读 `ChannelContext`/`TenantContext`**（长连接线程没有 HTTP 上下文）——渠道标签取 `channel()`，聊天维度取事件 `chat_id`；`contact` 恒 null（回复走出站 API）。`Channel.FEISHU` 被 `fromPath` **显式排除**（无 HTTP 入站路径，零暴露红线）。夹具 `eval/im-events/`（append-only，组合 sha256 钉在测试里）；门禁 `channel/FeishuAdapterReplayTest` 6 条（0 token、免凭据、进 CI 既有 test 步）。**长连接客户端是票 113**（SDK 引入），活体是票 115（真凭据） |
| **飞书长连接客户端**（round31 票 113+114 / ADR 0065） | `channel/FeishuLongConnectionClient`：飞书官方 SDK `oapi-sdk` 2.4.0+ 的长连接客户端 `com.lark.oapi.ws.Client`，承担心跳/重连/生命周期。`handleEvent()` 把飞书事件 JSON → `NormalizedChat` 契约（票 96 两格派生），走既有 `AgentStateMachine.run()` 链路。出站回复：`FeishuReplySender` 接口 + `FeishuApiReplySender` 实现（SDK `client.im().message().create()`）。断线语义：SDK 重连期间事件丢失照登（飞书侧无补推保证），日志可查。零暴露红线：出站 WSS，不开新端口、无入站回调。**凭据家法（票 114）**：`bindAddress` 经 `@Value("${server.address:}")` 注入，`start()` 用 `PostureGuard.isLoopback()` 判定——非回环 + 启用 + 空凭据 → 拒启（`IllegalStateException`，消息三要素：哪个凭据缺、为什么危险、怎么配）；回环 + 空凭据 → WARN + 不注册。`application.yml` 新增 `shoppilot.feishu` 配置块，三个环境变量占位符走 `.env`。测试 `channel/FeishuLongConnectionClientJvmTest` 17 条（正常编排、身份注入、空答案跳过、坏形状拒绝、ThreadLocal 清理、stop()、变异测试、PostureGuard 拒启/WARN/变异对照） |
| **买家端**（第三个前端入口，round27） | `frontend-buyer/src/`，产物落 `shoppilot-gateway/src/main/resources/static/buyer/`（round27 票 93；浏览器只经网关，不直连内部服务）。「我的工单」链路：`GET /api/v1/support/ops/tickets/mine`（网关，**只过买家 JWT、不要 ops 凭证**）→ `GET /api/tickets/mine`（工单服务，买家号取自 `X-Customer-Id`，**不接受任何查询参数**）→ `WorkItemService.listOwn`。**买家视角少一格 `transcript`**（`toBuyerView`）。静态入口放行面与两个入口转发：`identity/AuthFilter.shouldNotFilter` + `web/StaticEntryController`，由 `identity/StaticEntryExemptionTest` 一次钉住三个入口 |
| 三个前端入口的静态放行（清场日抓到过一次同类洞） | `identity/AuthFilter.shouldNotFilter` 必须同时列 `/`、`/workspace/**`、`/buyer/**`；**加一个新入口而忘了在这里放行 → 401**，且要等到活体验收才发现。`identity/StaticEntryExemptionTest` 会在下一个入口加入时先撞红 |
| 模式迁移与 schema 一致性 | `bizmock/SchemaMigrationTest`（迁移已应用、9 表齐备、`ddl-auto` 仍是 validate） |
| 慢查询计划与索引守卫 | `bizmock/SlowQueryPlanTest`（含被否决的那笔优化，见 `docs/slow-query-optimization-2026-09-21.md`） |
| 跨模块 schema | `shoppilot-tool-api/src/test/java/.../ToolSchemaGeneratorTest` |
| 活体防线 | `scripts/verify-*.ps1`、`scripts/verify_l2_filters.py`、`scripts/verify_eval_judge.py` |
| 覆盖率棘轮 | `scripts/check_coverage.py`（读各模块 `jacoco.xml`，按模块比 LINE 门槛） |
| 网关主链路 JVM 集成 | `web/GatewayMainPathJvmTest`（缓存命中、工具循环、fallback） |
| 干净 runner JVM 门禁 | `.github/workflows/ci-subset.yml` |

## 已知代码债候选

这些是整理时确认的候选，不表示应该立刻重构：

- `agent/AgentStateMachine.java` 约 938 行（round21 收口实测），同时承担状态循环、Prompt、检索、缓存写回、fallback 和槽位抽取。未来按行为边界拆时，先用 ticket 固定现有测试和 SSE 契约。round21 的两处新增（票 58 的 `replayAnswer`、票 59 的 `pendingApprovalAnswer`）都抽成 helper 并把话术渲染外置到 `agent/ReplayReply`，是为了**不加重**这笔债。
- `agent/AgentStateMachine` 与 `agent/ToolDispatcher` 各自持有部分槽位策略。若继续出现参数校验漂移，可评估 `ToolInputPolicy`，不要为了“少一个类”先合并。
- `web/ChatController` 的同步和流式路径重复限流、工单和 fallback 决策。若新增准入规则，优先抽 `ChatAdmission` 一类深模块，避免两条路径再次漏改。
- `agent/ConversationOwnershipTest` 位于 agent 包，但实际读取并断言 `static/index.html`；若继续扩调试台断言，应迁到 web/console seam，而不是继续在 agent 测试里堆前端细节。
- ~~`knowledge/HybridRetriever` 与 `ingest/MarkdownChunker` 缺少聚焦单测~~ —— **已还（round22 票 64 / ADR 0049）**：`RetrievalFusionReplayTest`（由录制的两路序重算融合，四个常数从生产 `application.yml` 绑定、语料 sha 现算）与 `MarkdownChunkerTest`（切块不重叠、ruleId 稳定 = ES `_id`/Qdrant point id 稳定）随该票一起交付，并接进 CI 的 `Retrieval fusion gate` 步。
- `identity` 与 `web` 之间存在通过 `ApiErrorWriter` 形成的包环。ADR 0028 已限定错误信封只管网关自产错误；不重开错误信封协议时，不为消环做大搬迁。

## 明确不要动

- 不把 `cache`、`triage`、`knowledge`、`llm`、`identity`、`ratelimit` 合并成一个大 `service` 包；当前能力边界比包数量更有价值。
- 不重命名 `.scratch/shoppilot-mvp`，不移动旧 ticket、审计、`eval/results` 或 `loadtest/results` 的历史文件。
- 不把本机 `logs/`、根目录 `hs_err_pid*.log` 或 `replay_pid*.log` 当作可在干净克隆复现的代码资产。
- 不在普通功能票里顺带调整 package、版本、构建链或公共接口；那类改动需要独立 ticket 和迁移证据。
