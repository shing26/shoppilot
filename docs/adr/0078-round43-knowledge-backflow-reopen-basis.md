# round43 开轮依据：知识反向沉淀咬合（登记第 8 项转执行）

Date: 2026-11-01　Status: **Accepted**（2026-11-01 所有者点名执行）
Owner basis: **所有者政策覆盖**（ADR 0033/0044/0046 先例）。round19 登记第 8 项自己的触发线
（ADR 0039 第 19 行「显式评分覆盖率达到可观测水平」）**未到**——演示口径下显式评分覆盖率仍然很低，
本 ADR 不把触发线讲成已触发；执行依据是所有者的直接指令，代价与风险由本 ADR 显式记账。

Tickets: [185](../../.scratch/shoppilot-mvp/issues/185-knowledge-revision-entity.md) /
[186](../../.scratch/shoppilot-mvp/issues/186-gateway-publish-and-ingest-source.md) /
[187](../../.scratch/shoppilot-mvp/issues/187-knowledge-backflow-evidence-and-closeout.md)。

## Context

入库→检索→缓存写回→纪元失效这条正向链完整且被录放门守着（round22 票 64）；反向缺失——
问答结论只进答案缓存不进知识库，`markReviewed` 是复核队列的终点，之后没有任何代码连线
（round19 spec 登记节第 8 项）。ADR 0039 已定死边界：**人工复核后才进知识库修订，不做自动改写**
（LLM 起草新条款被显式否决：「让上次犯错的链路自己修自己」）。所以本轮要补的不是"自动改写"，
而是**人工撰写修订稿之后的那段机械搬运**：修订稿落库 → 发布 → 成为入库语料的一个来源 →
纪元推进 → 旧纪元缓存作废。

## Decision

1. **修订稿由人工撰写，两道人工门**：复核完成（`REVIEWED`）之后才允许对那条反馈**附修订稿**
   （DRAFT）；修订稿必须再过一道显式发布动作（`PUBLISHED`）才进入语料。系统只做格式校验与
   转写，不生成、不润色、不补写正文——ADR 0039 的红线原样继承。
2. **发布稿走"语料源扩展"，不做旁路单条 upsert**。检索侧按 `kb_epoch` **精确匹配**过滤
   （`HybridRetriever` 的 Qdrant payload filter 与 ES term 都是 match/term，不是 range），
   任何纪元推进若不伴随全量重建，旧语料会整体不可见。所以发布稿的正确落点是**与文件语料并列的
   第二个来源**：ingest 在文件目录之外从 biz-mock 拉取全部 `PUBLISHED` 修订，与文件语料一起
   切分入库、写 target 纪元、成功后推纪元——既有蓝绿语义一字不改。
3. **拉取失败即中止入库**，不允许"文件语料照常入库、修订稿下次再说"：那会发布一个缺了人工
   已核准条款的知识库，且悄无声息。
4. **修订的 ruleId 由修订 id 派生**（`rev-<id>` 做 sourceDoc），与文件语料的 hash 规则同族，
   重跑幂等 upsert。
5. **复核完成的网关代理口是本链的一部分**：今天 `/ops/feedback/review-queue` 只有读口，
   `markReviewed` 只能拿内部令牌直调 biz-mock——浏览器到不了。链条要能被人走通，网关必须有
   `PATCH /ops/feedback/{id}/review`，它与修订稿附稿、发布同属本轮交付面。

## Non-goals

- 不做 LLM 起草/润色修订稿（ADR 0039 已否决，不重开）。
- 不做进程内"一键重入库"端点：ingest 仍是离线一次性作业（embedding 依赖 + 全量重建的耗时
  不适合挂在一个 HTTP 请求里）。发布后的生效动作 = 重跑既有 ingest 命令，登记为已知边界。
- 不做修订历史版本链（一稿一修订行，改稿 = 新行）。
- 不动缓存写回资格（ADR 0039 Considered Option 3 的"踩评作废缓存"仍是登记项，触发线不变）。
- 不做工作台前端的修订编辑器（本轮只交付后端面 + JVM 证据；浏览器断言按近期先例照登）。

## Consequences

- biz-mock 新表 `knowledge_revisions`（Flyway `V10`，H2 与 PG 两侧 + `PostgresMigrationParityTest` 同步）。
- biz-mock 跨租户读：ingest 拉取的是**全部租户**的 PUBLISHED 修订，而 biz-mock 的
  `@TenantId` 判别器多租户会把租户谓词拼到所有 JPQL 上——该读口用显式 native 查询，
  内部令牌守卫、只读、只吐已发布内容，绕过点写进代码注释。
- gateway 新增 `shoppilot_knowledge_revisions_total{result=filed|published|rejected}`；README 指标名计数随之换代。
- 咬合的缓存失效部分**零新代码**：L1 键含纪元、L2 payload filter 含纪元、检索 filter 含纪元，
  推纪元即作废（ADR 0003 既有机制）。票 187 只钉证据，不造新门。
- 顶层 `knowledge/` 文件语料一字不动（B7 内容级禁面）；修订稿只活在 DB 与检索索引里。
