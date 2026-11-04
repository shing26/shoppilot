# ShopPilot Code Map

这份地图回答两个问题：一个需求应该先改哪里，以及哪些“看起来可以合并”的包不应在无 ADR 的情况下移动。它描述现状，不替代 `CONTEXT.md` 术语和 `docs/adr/` 决策。

## 四个 Maven 模块

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
| `ingest` | Markdown 政策切块与离线入库；**round43 起第二个语料源**：`KnowledgeRevisionSource` 从 biz-mock 拉 PUBLISHED 知识修订（拉取失败 → 中止入库，不静默缺条款，ADR 0078） | `MarkdownChunker`、`IngestRunner`、`KnowledgeRevisionSource` |
| `llm` | local/dev/perf/demo 模型实现、超时/预算、显式输出上限与 LLM 故障注入；**round48 起加 replay 与装配层录制**（ADR 0083） | `LlmGateway`、`OllamaLlmClient`、`OpenAiCompatibleLlmClient`、`MockLlmClient`、`DemoLlmClient`、`ReplayLlmClient`、`RecordingLlmClient`、`CassetteKey`、`TokenBudget` |
| `agent` | 10 状态编排、工具派发、会话、幂等、fallback 与工单；计划步骤与上下文组成的观测出口（ADR 0044 票 48/49）；**请求级幂等回放**（模型之前，ADR 0046 票 58）与**退款受理出口**（ADR 0047 票 59）。round48 票 203 起编排面为 `TurnOrchestrator`＋四段 handler（`RetrieveHandler`/`PlanExecuteHandler`/`ComposeHandler`/`PersistHandler`），`AgentStateMachine` 只是 Spring 装配根与 `run` 门面 | `AgentStateMachine`、`TurnOrchestrator`、`TurnServices`、`AgentResult`、`ToolDispatcher`、`BizMockClient`、`FallbackService`、`IdempotencyService`、`ReplayReply` |
| `store` | 按已验签 `tenantId` 提供对话层店铺资料（店名/主营/本店承诺），拼进系统提示词（round37 / ADR 0071）；场景包覆盖优先 | `StoreProfileProperties`（配置 `shoppilot.store-profiles`）、`StoreProfileService`；读面 `web/StoreProfileController` |
| `scenario` | 场景包 v1（票 172）：加载 `config/scenarios/*.json`，提供 domain、店铺人格与可选补充提示；重复 tenant/非法 domain 拒启 | `ScenarioPackService`、`ScenarioPackService.Pack`；接入 `TenantDomainRegistry`、`StoreProfileService` |
| `web` | 同步/SSE 聊天入口、运维接口、错误信封、调试台事件出口；店铺资料只读端点与调试台入口重定向；**`/ops/**` 默认拒绝守卫**（2026-10-22） | `ChatController`、`SseEventSink`、`OpsController`、`OpsAuthorizationInterceptor`、`StoreProfileController`、`DebugEntryController`、`ApiErrorWriter` |
| `config` | 配置绑定/校验、健康组、dev 默认值、线程与 HTTP 客户端、运行时指标、MVC 拦截器注册 | `GatewayProperties`、`ValidatedServerProperties`、`DevDefaultsPolicy`、`RuntimeStateMetrics`、`WebMvcConfig` |

## Biz-Mock 与 Tool Contract

| 区域 | 责任 | 常见首改文件 |
| --- | --- | --- |
| `bizmock/domain` | Hibernate 实体和业务状态枚举 | `Order`、`Refund`、`Ticket`、`TicketStatus` |
| `bizmock/repo` | tenant-aware repository 与唯一约束 | `OrderRepository`、`RefundRepository`、`TicketRepository` |
| `bizmock/db/migration`（资源） | **模式的唯一产生源（H2，默认档）**：Flyway 版本化迁移（`ddl-auto` 已是 `validate`）。索引的真相源在这里，实体上的 `@Index` 注解在 `validate` 下不再被校验、只作文档。**换代（round28 / ADR 0061）**：`bizmock/db/migration-postgresql/`（**平级目录，不是子目录**——Flyway 对 `classpath:db/migration` 递归扫描，子目录会同版本撞车）是持久档的 PG 方言产生源，两侧版本钟由 `PostgresMigrationParityTest` 钉住；持久档配置在 `application-postgres.yml`。**2026-10-22**：`V9__refund_active_uniqueness.sql` 增加 `refunds.active_order_id` 可空唯一列（一单最多一笔在办退款）。**round46 票 194**：`V11__feedback_review_ticket.sql` 增加 `feedback.review_ticket_id`（点踩开出的复核工单号，复核时据此结单），H2 与 PG 同句 | `V1__baseline.sql`、`V2__index_feedback_review.sql`、`V9__refund_active_uniqueness.sql`、`V11__feedback_review_ticket.sql`；回滚约定在 `db/rollback/U1__baseline_down.sql`（PG 回滚演练登记给 B2） |
| `bizmock/service` | 查询、改地址、退款、**退款审核（受理/放行/驳回 + 推导回滚）**、工单、归属和状态前置校验；**2026-10-22** 起退款受理由 `refunds.active_order_id` 唯一索引守并发，审批单调用移出事务（afterCommit + `shoppilot_refund_ticket_missing_total`）；**round43 起知识修订**（附稿/发布/驳回，跨租户 404，归属显式比对） | `BizMockService`、`KnowledgeRevisionService` |
| `bizmock/web` | 内部工具接口、工单接口、**退款审核端点**（B3 起 `review` 要求 `X-Actor-Authenticated: true`，未认证 → 403）、内部 token 校验、故障注入；**round46 票 194**：`FeedbackController.reviewQueue` 接受 `?status=`（白名单 PENDING/REVIEWED/NONE，默认 PENDING，非法值 400）；**round43 起** `KnowledgeRevisionController`（`/api/feedback/{id}/revision`、`/api/knowledge/revisions/*` 租户口、`/api/admin/knowledge/revisions` ingest 拉取口）；**round44 票 189 起** `OrderQueryController` 增买家详情与轨迹（`/api/orders/mine/{orderNo}`、`/api/orders/mine/{orderNo}/logistics`，越权与不存在同为 404 空体） | `ToolController`、`TicketController`、`RefundReviewController`、`InternalAuthFilter`、`KnowledgeRevisionController`、`OrderQueryController` |
| `tool/request` / `tool/view` | 跨模块请求和响应 DTO | `QueryOrderDetailRequest`、`ToolResponse`（`ToolStatus.PENDING_APPROVAL`）、`OrderView`（`RefundReviewState`）、`OrderDetailView` / `OrderLogisticsView`（round44 买家深度卡，轨迹倒序且手机号已脱敏）、`TicketView` |
| `tool/schema` | 工具 JSON schema 生成 | `ToolSchemaGenerator` |

## 请求主链路

同步与 SSE 请求都从 `web/ChatController` 进入，主链路与源码落点如下：

| 阶段 | 源码落点 |
| --- | --- |
| 验签与租户上下文 | `identity/AuthFilter`、`identity/TenantContext` |
| trace 与 MDC | `identity/RequestTrace`、`config/AsyncConfig` |
| 限流 | `ratelimit/RateLimitService` |
| 意图判定与缓存准入 | `triage/TriageEngine`、`triage/T0RuleLayer`、`triage/T1CentroidLayer` |
| 跨行业隔离（round33 / ADR 0067，round32 酒店对照） | 租户行业 `config/TenantDomainRegistry`（配置 `shoppilot.tenant-domains`，未登记 = `UNKNOWN` fail-closed）、`knowledge/Domain`（`ECOMMERCE`/`HOTEL`/`UNKNOWN`）；判定 `triage/T0RuleLayer`（`classify(query, domain)`：显式转人工 → 行业门 → 实体/第一人称 → 显式办理动作 → 酒店词表 → 电商政策词表）、`ingest/MarkdownChunker`（`hotel-` → `POLICY_HOTEL`）；隔离面 `cache/CacheService`（平台桶 `PLATFORM:<DOMAIN>`）、`cache/L2SemanticCache`（payload filter 带 `domain`）、`knowledge/HybridRetriever`（可见 = 本租户 + 同行业平台，空召回不再放宽意图）、`agent/PlanExecuteHandler`（`toolsFor(triage, domain, route)`：非电商域工具列表为空）；语料 `knowledge/hotel-*.md`（挂 `tenant_id: T004`，**不挂 PLATFORM**）。接新行业 = 显式登记 domain + 语料 domain + 意图/词表/入库映射/评测集，**不是「只换知识库」** |
| 跨店隔离（round34 / ADR 0068，2026-10-17） | 同行业内部的跨店串答案：共享安全前置 `cache/CacheService.platformShareAllowed`（`knowledge/ShopRuleIndex` 按 `scope=SHOP + domain + intent + 纪元` 实测「本行业该意图是否存在店铺级条款」，读写两侧同源、探针失败 fail-closed）；空召回确定性出口 `agent/RetrieveHandler`（原 `AgentStateMachine.runModelPath`，round48 票 203 搬家；政策意图 0 条且未降级 → `INTENT_UNRESOLVED` + 工单 + 负标记，不调模型）；引用详情随条目落库 `cache/CacheEntry.sourceDocs`；活体门禁 `scripts/verify-store-isolation.py`（验收矩阵第 29 步 `isolation`）。要改「哪类答案能跨店共享」，先看 ADR 0068 的 Considered Options |
| 请求级幂等回放（模型之前、情绪门之后） | `agent/IdempotencyService.lookupByClientToken`、`agent/ReplayReply` |
| L1/L2 缓存 | `cache/CacheService`、`cache/L1Cache`、`cache/L2SemanticCache`、`cache/PolarityGuard` |
| 混合检索 | `knowledge/EmbeddingClient`、`knowledge/QdrantRestClient`、`knowledge/EsRestClient`、`knowledge/HybridRetriever` |
| 模型计划 | `llm/LlmGateway`、`llm/LlmClient`、`llm/LlmTypes`；录放门禁 `llm/ReplayLlmClient`、`llm/RecordingLlmClient`、`llm/CassetteKey` + `scripts/verify_llm_replay.py` |
| 状态机与工具循环 | `agent/TurnOrchestrator`（入口编排、Handoff、缓存与 singleflight）、`agent/PlanExecuteHandler`（工具循环与计划）、`agent/AgentStateMachine`（Spring 装配根）、`agent/AgentState`、`agent/ToolDispatcher` |
| 业务调用 | `agent/BizMockClient` -> `bizmock/web/ToolController` -> `bizmock/service/BizMockService` |
| 退款审核（人工闸门） | `bizmock/web/RefundReviewController` -> `bizmock/service/BizMockService.reviewRefund`；网关代理 `web/OpsController`（`/ops/refunds/*`，B3 起 `reviewRefund` 与 `refundReviewQueue()` 均为 `staff()`-only）；调试台面板 `static/console.html`（B3 起只读；round37 前是 `static/index.html`）；坐席工作台 `static/workspace/`（B3 起可审核） |
| 知识反向沉淀（人工闸门，round43 / ADR 0078） | `bizmock/service/KnowledgeRevisionService`（附稿前置 = 反馈 `REVIEWED`；DRAFT → PUBLISHED 显式发布，驳回终态；修订表 Flyway `V10__knowledge_revisions.sql`，两侧迁移同步）；网关代理 `web/OpsController`（`/ops/feedback/{id}/review`、`/ops/feedback/{id}/revision`、`/ops/knowledge/revisions/*`，走 `guardedBizMock`：坐席角色或运维凭证取或，非资金动作）；入库侧 `ingest/KnowledgeRevisionSource`（fetch 失败即中止）+ `ingest/IngestRunner`（修订与文件语料并列切块，`sourceDoc = rev-<id>`，纪元蓝绿照旧）；活体验收 `scripts/verify-knowledge-backflow.mjs` |
| 买家订单读面（round44 / 票 189） | 网关 `web/BuyerOrdersController`（`/api/v1/support/orders/mine`、`/{orderNo}`、`/{orderNo}/logistics`；订单号先过 `[A-Za-z0-9_-]{1,32}` 白名单再拼下游 URL，下游 404 原样传 404）→ biz-mock `web/OrderQueryController` → `BizMockService.buyerOrderDetail` / `buyerOrderLogistics`（`buyerOwned` 两个条件都必填，不复用坐席路径的整店回退；`maskPhone` 出站前脱敏）；买家页面 `frontend-buyer/src/components/OrderCard.vue` |
| 知识飞轮产品面（round44 / 票 190） | 工作台 `frontend-workspace/src/App.vue`（反馈队列/修订列表/动作状态）+ `src/components/KnowledgeView.vue`（五步条 → 复核 → 附稿 → 发布/驳回 → ingest 提示）→ `src/api.ts`（`feedbackReviewQueue` / `reviewFeedback` / `fileRevision` / `listKnowledgeRevisions` / `publishRevision` / `rejectRevision`，基址 `/api/v1/support/ops`）→ 网关 `web/OpsController`（`/ops/feedback/review-queue`、`/ops/feedback/{id}/review`、`/ops/feedback/{id}/revision`、`/ops/knowledge/revisions[/{id}/publish|reject]`，走 `guardedBizMock`）→ `bizmock/service/KnowledgeRevisionService`。**发布不立即生效**：修订要等 `scripts/ingest.ps1` 重跑才进检索语料，页面把这句写在已发布卡上。**round46 票 194**：反馈行带 `review_ticket_id`，`markReviewed` 成功后经 `WorkItemClient.resolve` 结掉复核单（跨进程失败只 warn，真源仍是 `feedback.review_status`）；工作台刷新时并行取 `status=PENDING` 与 `status=REVIEWED`，按已附稿（非 REJECTED 修订）过滤后填「可附稿」。**round45 票 191 起**买家回答下的「有用 / 没用」是这条链的真实入口：`ChatThread` → `App.rateMessage/submitFeedback` → `api.answerFeedback()` → `POST /api/v1/support/chat/feedback`（会话 ID 随消息，不随页面） |
| P2 产品面修复（round47 / ADR 0082） | 共享层 `frontend-shared/src/index.ts`：四张标签映射（`FEEDBACK_VERDICT_LABEL` / `REFUND_STATUS_LABEL` / `CONVERSATION_STATUS_LABEL` / `SERVICE_FLAG_LABEL`）+ `labelOf` + `setUnauthorizedHandler` / `notifyUnauthorized`；`tokens.css` 的 `--ink-3` 改 `#5c636e`。买家：`App.vue` 注册 401 钩子与 `.buyer-main` 滚动、`api.ts` 的 SSE 入口 401 通知、`ChatThread.vue` 的 `role="log"`、`TopBar/SideActivity/OrderCard` 的 ≤640px 触屏目标。工作台：`App.vue` 的 `selected` 从 `filtered` 取、`setFilter` 自动选中/清空、`openResolve` 领取守卫、`reviewedTotal`；`QueueView` 结单禁用与提示；`SideNav` ≤640px 三列两行。质量报表：`agent/TurnServices` 的 `AtomicLong` + Gauge `shoppilot_handoff_decision_seconds_max`（原 `AgentStateMachine`，round48 票 203 搬家），`quality/QualitySidecarService.timer()` 优先读 Gauge、缺失回退 `Timer.max()` |
| SSE 事件 | `agent/EventSink`、`web/SseEventSink` |
| 降级与工单 | `agent/FallbackService`、`agent/FallbackReason` |
| 缓存写回 | `cache/WriteBackPolicy`、`cache/WriteBackPool` |

调试台是 `gateway/src/main/resources/static/console.html`（round37 起；`/admin/debug` 重定向到它，根路径 `/` 是产品导航），它只访问 gateway 同源接口；浏览器验收在 `scripts/verify-console.mjs`（目标地址已跟到 `/console.html`）。

## 前端组件表（round44 / 票 188-190）

两个产品入口都是**根壳管状态、子组件管呈现**：所有 `ref`、API 调用、定时器与 SSE 留在
`src/App.vue`，子组件只收 props / 发 emits；表单字段用 `defineModel`（值是双向的，**所有权仍归根**）。
没有路由与状态库——这是 ADR 0079 的显式决定，不是尚未做。前端单测自 round48 票 205 起
由各工程自己的 `vitest` 承担（dev-only、只测下沉后的纯函数，不引 @vue/test-utils / jsdom）；
Playwright 三条冒烟仍只在活体环境手动跑（`scripts/run-frontend-smokes.mjs`）。
改文案或布局先找组件；改状态、请求与错误分支先找 `App.vue`；改 SSE 分帧 / 对话存档 / 队列筛选先找 `lib/`。

### 买家端 `frontend-buyer`（源码 `src/` → 产物 `static/buyer/`）

| 文件 | 职责 | 状态归属 |
| --- | --- | --- |
| `src/App.vue`（约 790 行） | 根壳：会话与登录（round45：`readStoredSession` 校验 URL 店铺码）、`ask()` 流式提问、点踩投递（`rateMessage/submitFeedback`）、订单/工单/详情加载、`now` 定时器（30 s，`onUnmounted` 清理）；**round46 票 192**：游客不再短路订单/工单加载；对话按标签页存 `sessionStorage['shoppilot.buyer.chat']`（`{tenantId, conversation, messages}`，恢复时校验租户、把 pending 标成「刷新时中断」），`signOut()` 先 `stopAnswer()` 再清状态 | 全部 ref 与请求 |
| `components/TopBar.vue` | 品牌、当前账号署名、体验/访客徽标、退出 | props `demoMode/guestMode/hasSession/who`；emit `signOut` |
| `components/StoreCard.vue` | 店铺名片（店名 / 业态 / 保障） | props only |
| `components/LoginGate.vue` | 未登录整页门：一键体验、账号登录、口令表单 | `defineModel` 三个表单字段；emit `demoEnter/submit` |
| `components/ChatThread.vue` | 对话气泡、助手 trace（阶段）/计划步骤、**依据面板**（按文档分组的 `citationDetails`）、**「有用 / 没用」与点踩原因框**（round45）、贴底跟随滚动 | props `messages/asking/feedbackDraft/feedbackInFlight`；`defineModel feedbackReason`；emit `retry/rate/submitFeedback/cancelFeedback` |
| `components/Composer.vue` | 输入框 + 发送/停止；回车在输入法组合中（`isComposing` / keyCode 229）不发送（round46） | `defineModel query`；emit `submit/stop` |
| `components/SideActivity.vue` | 侧栏「订单 / 工单」两页签、加载三态、订单深度卡与工单抽屉的入口；**round46 票 192**：订单列表不再自带 `max-height/overflow`，只由侧栏一层滚动；无「游客模式不展示」提示 | `defineModel` 页签与「看全部」；其余 props；emit 刷新/展开/提问等 |
| `components/OrderCard.vue` | 票 189 订单深度卡：状态推进条、金额/品类/服务标、脱敏地址、物流时间轴 | props `order/expanded/detail/logistics/loading/error`；emit `toggle/ask` |
| `components/ServiceCard.vue` | 服务能力小卡 | props only |
| `components/TicketDrawer.vue` | 单张工单详情抽屉（SLA、转人工上下文）；**round46 票 193**：`trapDialogFocus`（Esc / Tab 圈内 / 焦点进入与回还） | props；emit `close/refresh` |
| `src/lib/format.ts` | 纯函数：状态/渠道/工具名映射、SLA 文案、agent trace 归并、`groupCitations` | 无状态 |
| `src/lib/sse.ts`（round48 票 205） | SSE 分帧：按空行切帧、`event:`/`data:` 解析、跨 chunk 缓冲；`api.streamAnswer` 只留事件分支 | 无状态（`SseFrameReader` 只持有未完成帧的文本） |
| `src/lib/chat.ts`（round48 票 205） | 对话存档归一化（按店铺归属、pending → 中断、未完成反馈退回）与一次回答的收尾（499 / 失败 / 成功）；`lib/chat.test.ts` 覆盖 | 纯函数，不碰 storage（读写仍由 `App.vue` 做） |

### 坐席工作台 `frontend-workspace`（源码 `src/` → 产物 `static/workspace/`）

| 文件 | 职责 | 状态归属 |
| --- | --- | --- |
| `src/App.vue`（1257 行） | 根壳：登录、六个视图的加载与动作、SSE 渠道流、通知/提示音、未读计数 | 全部 ref、请求与副作用 |
| `components/TopBar.vue` | 品牌、当前坐席、刷新、退出 | props `who/hasSession`；emit `refresh/signOut` |
| `components/LoginCard.vue` | 未登录整页门：一键体验（坐席/管理员）、账号口令表单 | `defineModel` 表单字段；emit `demoLogin/submit` |
| `components/SideNav.vue` | 六个视图导航 + 三个角标（队列 / 退款 / 知识待办） | props `view/ticketCount/unread/refundCount/knowledgeCount/tenantId`；emit `select` |
| `components/QueueView.vue` | 队列列表（卡片）、筛选、详情栏、生命周期时间轴、领取/释放/结单；**round46 票 194**：`source=FEEDBACK_REVIEW` 且 `payload.feedbackId` 可解析时多一个「去复核这条反馈」 | props；emit `changeQueue/setFilter/select/refresh/claim/release/resolve/openRefunds/reviewFeedback` |
| `components/RefundsView.vue` | 退款队列：金额/原因/决策路径 + 对应工单 | props `refunds/refundError/refundNote/refundInFlight/refundTickets`；emit `open` |
| `components/ChannelsView.vue` | 渠道连接状态、凭据测试、保存 | props `channel/channelForm/...`；emit `refresh/save/test` |
| `components/ConversationsView.vue` | 渠道会话收件箱：列表/详情/接管/回复/释放、通知与提示音开关 | `defineModel reply`；emit 接管/回复等 |
| `components/QualityView.vue` | 编排质量报告（拦截原因、审计动作、交接上下文丢失） | props；emit `refresh` |
| `components/KnowledgeView.vue` | **票 190 知识飞轮**：五步条、待复核队列、附稿表单、修订卡（发布/驳回）、ingest 提示；**round46 票 194**：`reviewed` 改由服务端 `status=REVIEWED` 恢复（刷新不丢） | `defineModel draft`；props 反馈/修订/动作态；emit `refresh/review/pickFeedback/cancelDraft/file/publish/reject` |
| `components/ResolveDialog.vue` | 结单确认对话框（意见必填）；**round46 票 193**：`trapDialogFocus`，初始焦点在意见框 | `defineModel note`；emit `close/confirm` |
| `components/RefundDialog.vue` | 退款放行/驳回对话框（备注必填）；**round46 票 193**：`trapDialogFocus`，初始焦点在备注框（不落在「确认放行」） | `defineModel note`；emit `close/confirm` |
| `src/lib/format.ts` | 纯函数：SLA、超时、比例/秒数、payload 取值 | 无状态 |
| `src/lib/queue.ts`（round48 票 205） | 队列筛选（ALL/OPEN/ASSIGNED/OVERDUE）、优先级升序 + 时间倒序、三个 KPI 计数；`lib/queue.test.ts` 覆盖 | 纯函数 |
| `src/lib/ui.ts` | 视图枚举、筛选枚举、渠道表单形状、**知识视图下拉选项**（政策意图/范围/行业域）与状态标签 | 无状态；选项只是下拉候选，校验仍在 biz-mock |
| `public/sw.js` | Web Push 接收与点击跳转；原样拷进 `static/workspace/sw.js`，改动要重建并入库 | 无状态 |

### 共享层 `frontend-shared`（`file:../frontend-shared` 接入，不是 npm workspaces）

`tokens.css` 是两端唯一的视觉基线（圆角/间距/字号/阴影/动效/语义色三件套 + focus 环 +
`.chip`/`.kpi`/`.timeline` 原子）；`index.ts` 放跨端纯函数与契约（`request`/`messageOf`/
`TicketSummary`/`STATUS_LABEL`/`PRIORITY_LABEL`/`priorityRank`/`minutesLeft`/`formatStamp`/
`shortId`/`countLabel`）。**布局类不进共享层**——两个入口的产品语义不同，只统一令牌。

`dialog.ts`（round46 票 193）导出 `trapDialogFocus(container, onClose): () => void`：
记录触发控件、把焦点送进层内（`[autofocus]` → 首个可聚焦元素 → 容器）、Esc 关闭、
Tab/Shift+Tab 在可见可聚焦元素间循环、释放时还焦点。**它是 DOM 函数而不是组合式函数**：
本包没有 `vue` 依赖（两端以 `file:` 符号链接接入、自身无 node_modules），
`ref/onMounted` 会让共享层反向依赖框架；调起与释放由各 SFC 的 `onMounted/onUnmounted` 负责。


## Test Map

| 风险面 | 主要测试/脚本 |
| --- | --- |
| 配置、健康、指标、启动默认值 | `config/*Test`、`IdentityArchitectureTest`、`ServedConsoleHidesDevDefaultsTest` |
| 身份和异步 trace | `identity/AuthFilterTest`、`MockIdentityConditionTest`、`RequestTraceTest`、`web/TraceCorrelationAcrossAsyncTest` |
| 缓存资格、L2、极性、写回池 | `cache/*Test` |
| 意图 T0 | `triage/T0RuleLayerTest` |
| embedding 合并、预热、Qdrant wire | `knowledge/*Test` |
| LLM 兼容与 token 预算 | `llm/OpenAiCompatibleLlmClientTest`、`llm/OllamaLlmClientTest`（输出上限按 `options.num_predict` 下发）、`llm/TokenBudgetTest`；录放机制 `llm/ReplayLlmClientTest`（录制→回放、缺卡 fail-closed、改卡即红） |
| 向量化分段耗时与计划/上下文观测 | `knowledge/EmbeddingLatencyTimerTest`（三桶与三计数器同分法）、`web/GatewayMainPathJvmTest` 的 `planStepsAreReportedInExecutionOrder` / `contextCompositionMirrorsCitationsAndHistory`、`web/SseEventSinkTest` 的 `done` 帧字段 |
| 会话归属 | `agent/ConversationOwnershipTest` |
| fallback 与幂等 | `agent/FallbackReasonTest`、`agent/IdempotencyServiceTest` |
| 请求级幂等回放 | `agent/IdempotencyServiceRequestReplayTest`（往返性质，Map 假 Redis）、`web/GatewayMainPathJvmTest.repeatedRequestReplaysTheFirstResultWithoutCallingTheModel` |
| 退款在办唯一性 / 工单移出事务（2026-10-22） | `bizmock/RefundInvariantTest`（绕网关并发测试 + 工单不可达） |
| `/ops/**` 默认拒绝（2026-10-22） | `web/OpsAuthorizationInterceptorTest`（买家 403 / allowlist / staff / ops 关闭）、`web/RestErrorEnvelopeTest`（保留 token_mismatch 语义） |
| 退款审批闸门 | `agent/ToolDispatcherApprovalTest`（受理态落幂等）、`bizmock/RefundReviewTest`（三态迁移 / 推导回滚 / 跨租户 / 重复审核；独立 H2）、`web/GatewayMainPathJvmTest.refundApprovalStopsAtTheGateWithoutASecondModelHop`、`web/RestErrorEnvelopeTest.refundReviewProxiesWithTenantContext` |
| 知识修订链（round43 / ADR 0078） | `bizmock/KnowledgeRevisionTest`（附稿前置 / 残稿 400 / 跨租户 404 / 终态 / admin 拉取口）、`gateway ingest/KnowledgeRevisionSourceTest`（JDK HttpServer 假下游：解析 / 非 2xx / 不可达 / 坏形状，全部必须抛）、`ingest/MarkdownChunkerTest`（字符串重载：frontmatter 显式、rev- 前缀不吃猜测、ruleId 同式）、`web/RoleGuardAndActorTest`（修订代理三件套：身份头补全 / 买家 403 / 计数器）；变异对照两处（去 REVIEWED 前置、吞拉取失败）随票 187 登记并实跑变红 |
| 跨模块工具契约（审批策略与新状态值） | `shoppilot-tool-api/src/test/java/.../ToolContractApprovalTest` |
| 检索融合录放门（round22 票 64） | `knowledge/RetrievalFusionReplayTest`（JVM，重算的唯一 owner）、`ingest/MarkdownChunkerTest`、`scripts/retrieval_gate.py`（只校验不复算）+ `eval/retrieval-fixture-*.json`（append-only，录制器 `scripts/record_retrieval_fixture.py`） |
| 检索质量用例集（round48 票 206） | `eval/retrieval-cases.json`（120 条，37 文档全覆盖，`expect_doc` 自带标签）+ `scripts/verify_retrieval_cases.py`（结构校验，进 CI 的检索融合步）；录制走 `record_retrieval_fixture.py --cases`（自标注夹具），指标由 `knowledge/RetrievalQualityMetricsTest` 分流计算；决策与预登记判据在 `docs/RETRIEVAL_ROUTING_DECISION.md` |
| task 级判据（round22 票 65） | `scripts/eval_task.py`（判据 + 14 条夹具）+ `eval/cases-part8-task.jsonl`；活体入口 `run_tool_eval.py --task` |
| 答案级评测（round48 票 202 / ADR 0083） | `scripts/build_answer_cases.py`（60 条案例的唯一来源，`--check` 进 CI）+ `eval/answer-cases.jsonl`；共享层 `scripts/answer_cases.py`（生产消息形状 + 与 Java `CassetteKey` 同算法的 key）；确定性判分 `scripts/answer_facts.py`；录制 `scripts/record_answer_cassettes.py`（真实模型、会计费，不在 CI）；0-token 门禁 `scripts/verify_answer_eval.py` + `eval/answer-cassettes/`（cassette + `baseline.json`） |
| 告警规则（round22 票 67） | `ops/alerts/shoppilot.rules.yml` + `ops/alerts/shoppilot.rules.test.yml`（`promtool test rules`，CI 的 `Alert rules unit test` 步） |
| 业务租户隔离、幂等 | `shoppilot-biz-mock/src/test/java/...` |
| 工单工作流、规则分流、坐席领取、SLA、慢查询证据 | `shoppilot-ticket/src/test/java/...`（round23 票 72 随数据搬走） |
| **容器档**（干净克隆一条命令起全栈，round24） | `docker-compose.yml` 的 `full` profile + `Dockerfile`（`ARG MODULE`）+ `scripts/up.ps1 -Containerized` / `down.ps1 -Containerized`；偏移量与凭证走**每份克隆自己的 `.env`**；默认档（本机 JVM）不受影响 |
| 坐席工作台页面（登录/队列/领取/处理/释放/退款审核） | `frontend-workspace/src/`，产物落 `shoppilot-gateway/src/main/resources/static/workspace/`（round23 票 73；**登录面在 round25 票 83 换成坐席账号**；round36 票 151 补转人工上下文详情、结单意见对话框、状态/来源/队列可读名；round37 票 152 加回环一键体验登录；浏览器只经网关，不直连内部服务） |
| **共享前端库 `frontend-shared`**（round36 票 151 / ADR 0070） | 两个 Vue 工程以 `file:../frontend-shared` 接入（不是根 npm workspaces）。导出：`tokens.css`（设计令牌 + 按钮/输入/表格基线）、`request` / `ApiResult` / `messageOf`、`TicketSummary` / `STATUS_LABEL` / `PRIORITY_LABEL` / `priorityRank` / `minutesLeft`；**round47 票 195** 新增 `FEEDBACK_VERDICT_LABEL` / `REFUND_STATUS_LABEL` / `CONVERSATION_STATUS_LABEL` / `SERVICE_FLAG_LABEL` 与统一读取 `labelOf`（未知值原样透出）、`setUnauthorizedHandler` / `notifyUnauthorized`（401 会话失效出口，登录请求不带令牌不触发）。各页只保留自己的路径前缀与失败文案；产物仍由各工程 `npm run build` 落到 `static/` |
| **身份域**（账号 + 角色守卫，round25 票 80-82） | **落在 `shoppilot-biz-mock`**，不在网关、也不是第五个服务——ADR **0058** 有完整论证（0053 的资源账 + 网关没有数据源 + 账号主体是买家）。⚠️ **0053 给 biz-mock 的定义（订单/物流/退款/工具/审批）里没有身份**，按旧清单去找会找不到。入口：`bizmock/domain/UserAccount`（表 `users`，Flyway `V7`）、`bizmock/service/IdentityService`、`bizmock/web/IdentityController`（`/api/identity/*`，内部凭证之后）；登录面在网关 `identity/AccountController`（`/auth/login` `/auth/register` `/auth/me`）+ `identity/IdentityClient`；角色守卫在网关 `web/OpsController.guardedTicket`；契约在 `shoppilot-tool-api` 的 `tool/identity/` 与 `tool/audit/{Actor,ActorHeaders}` |
| 自报身份的退役面（round25 票 82） | **下游不再读自报头**：biz-mock 与 ticket 服务只认 `X-Actor` + `X-Actor-Authenticated`，两者都由网关从已验签令牌解出（`grep -rn "X-Agent\|X-Reviewer"` 在两边只剩注释与测试）。网关**仍然接受**客户端的 `X-Agent` / `X-Reviewer`——但只在**没有账号身份**时作为自称来源（ops 运维面那条路径），**盖不过令牌**，且产出的 actor 一律标成未认证 |
| **结果回流**（工单结单 → 渠道出站，round26） | 三处落点，缺哪一半都算断链：**① 工单带渠道与目标** `shoppilot-ticket` 的 Flyway `V2` + `Ticket.attachDelivery`；**② 生产端** `ticket/audit/OutboundPublisher`（由 `WorkItemService.resolve` 在结单成功后调用，web 渠道按规则不发但计数）；**③ 消费端** `gateway/channel/OutboundDeliveryService`（网关进程内，后台单线程 5 s 一跳，`eventId` 幂等，**只有 2xx 才算送达**，失败落 `CHANNEL_RECEIPT` 工单）。投递目标从入站来：`channel/WebhookAdapter` 的可选 `callbackUrl` / `EmailAdapter` 的 `from`。门禁 `scripts/verify-outbound.mjs` 起本地回声端点 |
| **入站会话与幂等派生**（两格，round31 票 96 / ADR 0065） | `channel/ChannelAdapter` 的 `NormalizedChat` 五格（新增 `conversationId` + `clientToken`）与静态派生辅助：`<渠道label>:chat:<sessionId|customerId>`、`<渠道label>:msg:<messageId>`——**渠道标签与聊天维度缺一不可**（变异 B1/B2 钉住）。派生在 `WebhookAdapter`/`EmailAdapter`（payload 可选 `messageId`/`sessionId`，上限 255；渠道标签取自 `ChannelContext`，不进 NormalizedChat——两处真相）；web 渠道不派生（`WebSseAdapter` 行为零变更）。接线在 `web/ChannelController.handle`：**显式请求头 `X-Conversation-Id` 与显式 `idempotencyToken` 恒优先**，派生只补缺——会话坐标替换只动 `conversationId`，租户与买家身份一字不动（ADR 0025）；响应带 `conversationId`。测试 `web/ChannelDerivedSessionJvmTest`（10 条）+ `identity/AuthFilterTest` 现状锚。真实 IM 渠道（票 97/113 飞书）到这两个字段的映射落在各自的适配器 |
| **飞书适配器与录放门**（round31 票 97 / ADR 0060 决策 4 + 0065） | `channel/FeishuAdapter`：吃飞书 v2 原样事件（`im.message.receive_v1`），**仅单聊（p2p）文本/post 富文本**，范围外当场拒；`content` 是字符串化 JSON 二次解析。**不读 `ChannelContext`/`TenantContext`**（长连接线程没有 HTTP 上下文）——渠道标签取 `channel()`，聊天维度取事件 `chat_id`；`contact` 恒 null（回复走出站 API）。`Channel.FEISHU` 被 `fromPath` **显式排除**（无 HTTP 入站路径，零暴露红线）。夹具 `eval/im-events/`（append-only，组合 sha256 钉在测试里）；门禁 `channel/FeishuAdapterReplayTest` 6 条（0 token、免凭据、进 CI 既有 test 步）。**长连接客户端是票 113**（SDK 引入），活体是票 115（真凭据） |
| **飞书长连接客户端**（round31 票 113+114 / ADR 0065） | `channel/FeishuLongConnectionClient`：飞书官方 SDK `oapi-sdk` 2.4.0+ 的长连接客户端 `com.lark.oapi.ws.Client`，承担心跳/重连/生命周期。`handleEvent()` 把飞书事件 JSON → `NormalizedChat` 契约（票 96 两格派生），走既有 `AgentStateMachine.run()` 链路。出站回复：`FeishuReplySender` 接口 + `FeishuApiReplySender` 实现（SDK `client.im().message().create()`）。断线语义：SDK 重连期间事件丢失照登（飞书侧无补推保证），日志可查。零暴露红线：出站 WSS，不开新端口、无入站回调。**凭据家法（票 114）**：`bindAddress` 经 `@Value("${server.address:}")` 注入，`start()` 用 `PostureGuard.isLoopback()` 判定——非回环 + 启用 + 空凭据 → 拒启（`IllegalStateException`，消息三要素：哪个凭据缺、为什么危险、怎么配）；回环 + 空凭据 → WARN + 不注册。`application.yml` 新增 `shoppilot.feishu` 配置块，三个环境变量占位符走 `.env`。测试 `channel/FeishuLongConnectionClientJvmTest` 17 条（正常编排、身份注入、空答案跳过、坏形状拒绝、ThreadLocal 清理、stop()、变异测试、PostureGuard 拒启/WARN/变异对照） |
| **渠道连接管理 v1**（票 173，2026-10-28） | `channel/ChannelConnectionStore`：`config/channels.json` 优先、缺文件回退 .env；保存写文件并标记 restartRequired，不热重启 SDK。`web/ChannelAdminController` 挂在 `/api/v1/support/ops/channels`，提供状态（遮罩 appId）、凭据换 token 测试、保存；secret 不回显。`FeishuLongConnectionClient`/`FeishuIdentityResolver` 启动时读 store；前端 `frontend-workspace` 第四视图「渠道连接」。文档 `docs/CHANNEL_CONNECTIONS.md` |
| **渠道会话收件箱**（票 175，2026-10-28） | `channel/ChannelConversationStore`（Redis key `shoppilot:channel-inbox:{tenant}:{conv}` + index，TTL 7 天，BOT/AGENT）；`web/ChannelInboxController` 提供列表/详情/接管/回复/释放/已读；`channel/ChannelInboxEventBus` 按租户经 Redis pub/sub + 本地 SSE 推送 conversation；`config/ChannelInboxRedisConfig` 订阅 Redis 频道；`FeishuLongConnectionClient` 入站/出站写入；`channel/WebPushNotifier` + `ChannelPushSubscriptionStore` 发 Web Push；`frontend-workspace/public/sw.js` 处理 push/click；工作台「渠道会话」视图含未读角标与浏览器通知。文档 `docs/CHANNEL_INBOX.md` |
| **买家端**（第三个前端入口，round27） | `frontend-buyer/src/`，产物落 `shoppilot-gateway/src/main/resources/static/buyer/`（round27 票 93；round36 票 151 换成对话流、店铺码走 `?store=`、加载三态；round37 票 152 加免密游客入口与店铺名片；`guest=0` 保留口令登录门禁。浏览器只经网关，不直连内部服务）。「我的工单」链路：`GET /api/v1/support/ops/tickets/mine`（网关，**只过买家 JWT、不要 ops 凭证**）→ `GET /api/tickets/mine`（工单服务，买家号取自 `X-Customer-Id`，**不接受任何查询参数**）→ `WorkItemService.listOwn`。**买家视角 `contact` 与 `transcript` 两格都置空**（`toBuyerView`；round36 起 `transcript` 是契约真字段，这条隔离断言不再是真空断言）。静态入口放行面与两个入口转发：`identity/AuthFilter.shouldNotFilter` + `web/StaticEntryController`，由 `identity/StaticEntryExemptionTest` 一次钉住三个入口 |
| 三个前端入口的静态放行（清场日抓到过一次同类洞） | `identity/AuthFilter.shouldNotFilter` 必须同时列 `/`、`/workspace/**`、`/buyer/**`、`/admin/debug`（以及 `.html` 静态资源）；**加一个新入口而忘了在这里放行 → 401**，且要等到活体验收才发现。`identity/StaticEntryExemptionTest` 会在下一个入口加入时先撞红 |
| 模式迁移与 schema 一致性 | `bizmock/SchemaMigrationTest`（迁移已应用、9 表齐备、`ddl-auto` 仍是 validate） |
| 慢查询计划与索引守卫 | `bizmock/SlowQueryPlanTest`（含被否决的那笔优化，见 `docs/slow-query-optimization-2026-09-21.md`） |
| 跨模块 schema | `shoppilot-tool-api/src/test/java/.../ToolSchemaGeneratorTest` |
| 活体防线 | `scripts/verify-*.ps1`、`scripts/verify_l2_filters.py`、`scripts/verify_eval_judge.py` |
| 覆盖率棘轮 | `scripts/check_coverage.py`（读各模块 `jacoco.xml`，按模块比 LINE 门槛） |
| 网关主链路 JVM 集成 | `web/GatewayMainPathJvmTest`（缓存命中、工具循环、fallback） |
| 干净 runner JVM 门禁 | `.github/workflows/ci-subset.yml` |

## 已知代码债候选

这些是整理时确认的候选，不表示应该立刻重构：

- **已还（round48 票 203，2026-11-04）**：`agent/AgentStateMachine.java` 由 1,768 行拆成 73 行装配根 + `TurnOrchestrator`（481 行）+ 四段 handler（`RetrieveHandler` 148 / `PlanExecuteHandler` 456 / `ComposeHandler` 321 / `PersistHandler` 92）+ 共享 `TurnServices`（271）/`SlotSupport`（289）/`TokenUsage`/`TurnContext`，行为零变化，780 tests / 131 suites 与两道 0 token 门禁读数不变（见 `docs/EVIDENCE.md`）。首改位置按段找：入口面看 `TurnOrchestrator`，工具循环与反思看 `PlanExecuteHandler`，答案与引用看 `ComposeHandler`，会话/缓存写回看 `PersistHandler`。
- `agent/SlotSupport` 与 `agent/ToolDispatcher` 各自持有部分槽位策略。若继续出现参数校验漂移，可评估 `ToolInputPolicy`，不要为了“少一个类”先合并。
- `web/ChatController` 的同步和流式路径重复限流、工单和 fallback 决策。若新增准入规则，优先抽 `ChatAdmission` 一类深模块，避免两条路径再次漏改。
- `agent/ConversationOwnershipTest` 位于 agent 包，但实际读取并断言 `static/console.html`（round37 前是 `index.html`）；若继续扩调试台断言，应迁到 web/console seam，而不是继续在 agent 测试里堆前端细节。
- ~~`knowledge/HybridRetriever` 与 `ingest/MarkdownChunker` 缺少聚焦单测~~ —— **已还（round22 票 64 / ADR 0049）**：`RetrievalFusionReplayTest`（由录制的两路序重算融合，四个常数从生产 `application.yml` 绑定、语料 sha 现算）与 `MarkdownChunkerTest`（切块不重叠、ruleId 稳定 = ES `_id`/Qdrant point id 稳定）随该票一起交付，并接进 CI 的 `Retrieval fusion gate` 步。
- `identity` 与 `web` 之间存在通过 `ApiErrorWriter` 形成的包环。ADR 0028 已限定错误信封只管网关自产错误；不重开错误信封协议时，不为消环做大搬迁。

## 明确不要动

- 不把 `cache`、`triage`、`knowledge`、`llm`、`identity`、`ratelimit` 合并成一个大 `service` 包；当前能力边界比包数量更有价值。
- 不重命名 `.scratch/shoppilot-mvp`，不移动旧 ticket、审计、`eval/results` 或 `loadtest/results` 的历史文件。
- 不把本机 `logs/`、根目录 `hs_err_pid*.log` 或 `replay_pid*.log` 当作可在干净克隆复现的代码资产。
- 不在普通功能票里顺带调整 package、版本、构建链或公共接口；那类改动需要独立 ticket 和迁移证据。
