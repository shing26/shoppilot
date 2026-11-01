# 票 185：知识修订实体与复核前置（biz-mock）

Round: 43（spec：[round43-spec-knowledge-backflow.md](../round43-spec-knowledge-backflow.md)）
ADR: 0078　Status: implemented　Blocked by: —

## What to build

`shoppilot-biz-mock`：

1. Flyway `V10__knowledge_revisions.sql`（H2 与 `db/migration-postgresql/` 两侧同版本）：
   表 `knowledge_revisions`，列 `id`（PK, varchar(40)）、`tenant_id`（varchar(32), not null）、
   `feedback_id`（varchar(40), not null）、`title`（varchar(200), not null）、`body`（大对象，
   `@Lob`/oid 映射——round28 实测矩阵唯一两侧全绿的写法）、`intent`（varchar(32), not null）、
   `domain`（varchar(16), not null）、`scope`（varchar(16), not null）、`status`（varchar(16), not null）、
   `created_by`（varchar(64)）、`actor_authenticated`（boolean, default false）、`created_at` / `published_at` / `rejected_at`（带时区）。
2. 实体 `KnowledgeRevision` + `KnowledgeRevisionRepository`（tenant-aware 惯例与 `FeedbackRepository` 同）。
3. `KnowledgeRevisionService`：
   - `submit(feedbackId, Actor, title, body, intent, domain, scope)`：feedback 必须存在且
     `review_status = REVIEWED`（不满足 → `IllegalStateException`，端点回 409）；intent 必须
     `POLICY_*`；scope 只收 `SHOP|PLATFORM`；产出 DRAFT 行 + 审计事件。
   - `publish(id, Actor)` / `reject(id, Actor)`：DRAFT 单向流转，审计事件。
   - `listByStatus(status)`：**显式 native 查询**跨租户（供票 186 的 ingest 拉取），代码注释写清
     为什么绕过判别器多租户、边界（内部令牌守卫、只读、只吐已核准内容）。
   - 跨租户读一律 404 语义（`IllegalArgumentException` → 端点 404）。
4. `KnowledgeRevisionController`：
   - `POST /api/feedback/{feedbackId}/revision`（附稿，读 `X-Actor` / `X-Actor-Authenticated`）
   - `GET /api/knowledge/revisions?status=`
   - `POST /api/knowledge/revisions/{id}/publish`、`POST /api/knowledge/revisions/{id}/reject`

## Blocked by / Verify

无前置。Verify：

```powershell
.\mvnw.cmd -B -ntp verify -pl shoppilot-biz-mock -am
```

## 验收项

- 生命周期：附稿（REVIEWED 后）→ 发布 / 驳回，全链 JVM 用例；重复发布 / 驳回已结行的非法流转 409。
- REVIEWED 前置：PENDING 的 feedback 附稿被拒（**变异对照**：去掉前置 → 用例红）。
- intent 白名单：`ACTION_ORDER` 之类动作意图被拒。
- 跨租户：T002 的令牌读 T001 的修订 → 404。
- 审计：附稿 / 发布 / 驳回各留一行，`actor_authenticated` 随 Actor。
- 迁移：H2 启动可应用、PG 侧与 H2 版本钟对齐（既有 parity 测试自动覆盖）。
- 判据零改动：gold、`judge()`、阈值、`knowledge/` 语料一字不动。

## Handoff notes

**关键决策**

1. **body 用有界 `varchar(20000)`，不用票面写的 `@Lob`/oid**（票面偏离，已验证归因）：`listPublishedAcrossTenants` 的 native 投影读不了 LOB（H2 实测报 `Cannot project NClob to String`；PG 侧 oid 经裸 JDBC 读也很绕），且 body 在 `validateDraft` 已被限制在 20000 字，本就不需要大对象。两侧迁移同步改。未来若真要超长修订，需先解决 native 投影读 LOB 再扩列。
2. **归属显式比对**：`findById` 不自动拼租户（ARCHITECTURE.md 已知边界），`submit` 的 feedback 查找与 `ownedRevision` 都手写 `getTenantId().equals(TenantContextHolder.tenantId())`，跨租户一律 404 语义。跨租户发布实测 404（`crossTenantPublishIsNotFound`）。
3. **native 拉取口的投影**：原生查询按列别名（`as "tenantId"`）映射到 `PublishedRevisionRow`，时间戳直接声明 `OffsetDateTime`（声明 `Instant` 会 500，H2 不做隐式转换），view 层再 `toInstant()`。
4. **id 格式** `"KR" + epochMilli + "-" + seq(base36)`：与反馈 `FB*` 同族，且天然携带创建时序（ingest 拉 PUBLISHED 按 `created_at` 排序）。
5. 复核队列写口（markReviewed 代理）不在本票，在票 186（ADR 0078 Decision 5）。

**验证落点**

- `KnowledgeRevisionTest` 6 条全绿：生命周期全表、PENDING 附稿 409、残稿 400×3、跨租户 404、驳回终态 409、admin 拉取口只吐 PUBLISHED。
- 全量 `.\mvnw.cmd -B -ntp verify` BUILD SUCCESS（round43 收口读数 754 tests / 128 suites，含本票 6 条）。
- 变异对照（去 REVIEWED 前置 → `pendingFeedbackCannotCarryRevision` 红）在票 187 收口实跑。

**三个现场追问**

1. 为什么发布不走 ingest 直接触发？ADR 0078 非目标：入库是离线作业，纪元蓝绿语义不为本票改；
2. 修订改错了怎么办？驳回是终态，改稿 = 另附一稿挂同一反馈，不做历史链（非目标）；
3. LLM 能起草吗？不能，ADR 0039 红线，人工撰写是本链路的存在理由。
