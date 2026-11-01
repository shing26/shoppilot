# round43 spec：知识反向沉淀咬合

**收口状态（2026-11-01）**：三票 185-187 全部 implemented。全量 verify BUILD SUCCESS（757 tests /
128 suites，含复审补钉 3 条）；两条变异对照实跑变红后还原；活体脚本照登未实跑（边界见票 187 Handoff）。
读数与证据边界：[docs/EVIDENCE.md](../../docs/EVIDENCE.md) round43 条目。

开轮依据：[ADR 0078](../../docs/adr/0078-round43-knowledge-backflow-reopen-basis.md)（所有者政策覆盖，
round19 登记第 8 项转执行；触发线未到这件事在 ADR 里显式记账，不伪装成触发已成立）。

## 0. 本轮要补的那一格

```
点踩 → 复核队列（PENDING）
         |  markReviewed（人工，REVIEWED）        ← 今天是终点，本轮起之后有路
         v
     附修订稿（人工撰写，DRAFT，intent/domain/scope/tenant 随行）
         |  publish（人工第二道门，PUBLISHED）
         v
     ingest 拉取全部 PUBLISHED 修订（与文件语料并列的第二个来源）
         |  切分 → 向量化 → 写 target 纪元 → 成功后推纪元（蓝绿语义不变）
         v
     纪元推进 ⇒ L1 键 / L2 filter / 检索 filter 全部按新纪元匹配，旧纪元答案整体作废
```

红线（ADR 0039 原样继承）：修订稿只能出自人工；系统不生成、不润色正文；
`knowledge/` 文件语料一字不动。

## 1. 票拆分

| 票 | 内容 | 依赖 |
| --- | --- | --- |
| [185](issues/185-knowledge-revision-entity.md) | biz-mock：`knowledge_revisions` 表 + 修订生命周期（附稿 / 发布 / 驳回）+ 复核完成前置 | — |
| [186](issues/186-gateway-publish-and-ingest-source.md) | gateway：复核完成 / 修订 / 发布的代理口 + ingest 语料源扩展 + 指标 | 185 |
| [187](issues/187-knowledge-backflow-evidence-and-closeout.md) | 咬合证据（JVM 用例 + 变异对照）+ 文档换代 + 收口 | 186 |

## 2. 逐票判据

**票 185**（biz-mock）：
- `V10__knowledge_revisions.sql`（H2）与 PG 侧同版本迁移，`PostgresMigrationParityTest` 继续钉两侧对齐。
- 实体 `KnowledgeRevision`：`@Lob` body（round28 实测矩阵：`@Lob`/oid 是唯一两侧全绿的映射）、
  `@TenantId` tenant、status `DRAFT|PUBLISHED|REJECTED` 单向流转。
- 附稿前置：feedback 必须存在且 `review_status = REVIEWED`（ADR 0039「人工复核后才进知识库修订」
  的机器落点）；intent 必须是政策意图（`POLICY_*`），scope 只收 `SHOP|PLATFORM`。
- 发布/驳回：DRAFT 单向到 PUBLISHED / REJECTED，审计事件留痕（谁、何时、哪条修订）。
- 跨租户 404 语义与全仓一致（不确认存在）。

**票 186**（gateway）：
- 代理口四个：`PATCH /ops/feedback/{id}/review`（复核完成，补今天缺的写口）、
  `POST /ops/feedback/{id}/revision`（附稿）、`GET /ops/knowledge/revisions?status=`、
  `POST /ops/knowledge/revisions/{id}/publish|reject`。守卫沿用工单口的「运维凭证或坐席账号」
  取或语义；修订不是资金动作，ops token 合法（ADR 0063）。
- ingest 语料源扩展：`IngestRunner` 在文件语料之外拉取全部 PUBLISHED 修订（内部令牌 + 显式
  native 查询跨租户），`MarkdownChunker` 加字符串重载（frontmatter 由修订行显式给出），失败即中止。
- 指标 `shoppilot_knowledge_revisions_total{result=filed|published|rejected}`。

**票 187**：
- JVM：修订生命周期全表 + 跨租户 + REVIEWED 前置 + ingest 拉取（JDK HttpServer 假下游，
  票 88 先例）+ 字符串切分重载 + 「拉取失败 → 入库中止」。
- 变异对照至少两条：去掉 REVIEWED 前置（应红）；ingest 拉取失败被吞成空列表（应红）。
- 文档换代：CODE_MAP（biz-mock 新区域 + ingest 语料源 + 代理口）、EVIDENCE 登记、
  CONTEXT.md 新术语「知识修订」、README 指标名计数、tracker 收口。

## 3. 本轮不改

- `knowledge/` 顶层文件语料（B7 内容级禁面）。
- gold、`judge()`、阈值、降级枚举、评测集——一条判据都不动。
- 缓存写回资格（7 道）不因点踩/修订变化（ADR 0039 登记项触发线不变）。
- ingest 的蓝绿语义与离线作业形态（不开进程内触发端点，登记为已知边界）。

## 4. 登记不执行

1. **工作台修订编辑器**（前端表单：复核时附稿、发布确认）——触发 = 修订面进入产品演示清单；
   本轮浏览器断言一律不实跑（近期先例：round25-27、36、37）。
2. **进程内一键重入库**——触发 = 演示需要"发布即生效"的活体体验时，先过 Ollama 依赖与
   全量重建耗时两笔账。
3. **踩评作废缓存写回资格**——沿用 ADR 0039 原触发线（显式评分覆盖率达到可观测水平）。
4. **修订稿的 LLM 辅助起草**——ADR 0039 已否决，重开需新 ADR。

## 5. 验证分档

默认只交 JVM 层证据（本机资源账沿用 round23 裁定 A）；`verify-knowledge-backflow` 活体脚本
随票 187 交付但**照登未实跑**。判据一字不动；`git diff --check` 与 `git status --short` 收尾必查。
