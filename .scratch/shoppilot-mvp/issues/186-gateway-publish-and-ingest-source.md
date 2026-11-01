# 票 186：网关发布面 + ingest 语料源扩展（gateway）

Round: 43（spec：[round43-spec-knowledge-backflow.md](../round43-spec-knowledge-backflow.md)）
ADR: 0078　Status: implemented　Blocked by: 185

## What to build

`shoppilot-gateway`：

1. `web/OpsController` 四个代理口（沿用 `forward(...)` 与工单口的「运维凭证或坐席账号」取或守卫；
   修订不是资金动作，ops token 合法——ADR 0063）：
   - `PATCH /api/v1/support/ops/feedback/{id}/review` —— 复核完成写口（今天只有队列读口，
     `markReviewed` 拿内部令牌都够不着，链条在网关断头，ADR 0078 Decision 5）。
   - `POST /api/v1/support/ops/feedback/{id}/revision` —— 附稿（body 透传，身份头由网关补）。
   - `GET /api/v1/support/ops/knowledge/revisions?status=` —— 列表（发布面前置）。
   - `POST /api/v1/support/ops/knowledge/revisions/{id}/publish|reject`。
2. `ingest/KnowledgeRevisionSource`：java.net.http + `X-Internal-Token`（BizMockClient 同款配置
   `shoppilot.bizmock.*`，零新增占位符——`ConfigValidationTest` 钉住清单不动）。失败抛
   `IllegalStateException`，**ingest 必须中止**，不允许静默降级成"只有文件语料"。
3. `MarkdownChunker` 字符串重载 `chunk(String sourceDoc, String markdown, Map<String,String> frontMatter, long kbEpoch)`：
   现有 `chunk(Path)` 委托它（行为零变化），修订稿复用同一套切分；frontmatter 由修订行显式给出
   （intent/domain/scope/tenant_id/title），不靠文件名前缀猜。
4. `IngestRunner`：文件语料之后追加修订块（sourceDoc = `rev-<id>`，ruleId 仍是
   hash(sourceDoc|headingPath)，幂等 upsert），统计行带上修订数。
5. 指标 `shoppilot_knowledge_revisions_total{result=filed|published|rejected}`（代理层计数）。

## Blocked by / Verify

185。Verify：

```powershell
.\mvnw.cmd -B -ntp verify -pl shoppilot-gateway -am
```

## 验收项

- 代理口 JVM 用例：身份头补全（`X-Actor` / `X-Actor-Authenticated` 只在真账号时为 true）、
  守卫拒绝路径、下游体逐字节透传（ADR 0028 两分）。
- `KnowledgeRevisionSourceTest`（JDK HttpServer 假下游，票 88 先例）：正常拉取解析、
  非 2xx → 异常、**失败被吞成空列表的变异对照红**。
- 字符串切分重载：frontmatter 显式覆盖 intent/scope/tenant_id；`chunk(Path)` 既有用例零漂移。
- IngestRunner 统计行含修订数；拉取失败 → 进程 exit 非 0（用例断言抛错路径即可，不起真栈）。
- 零新增配置占位符；README 指标名计数换代（+1）。
- 判据零改动。

## Handoff notes

**关键决策**

1. **拉取失败 = 入库中止**：`IngestRunner.ingest()` 在任何写入之前先 `revisionSource.fetchPublished()`，异常与 `DependencyMissing` 同类（exit 1）。"文件语料照常入库、修订下次再说"被显式拒绝——那是发布一个缺了人工核准条款的知识库且无声（ADR 0078）。实测变异：吞成空列表 → `KnowledgeRevisionSourceTest` 4/4 红（票 187 收口）。
2. **修订进语料 = 并行来源，不是绕过单条 upsert**：检索按 `kb_epoch` 精确匹配，任何纪元推进都是全量重建；把修订塞进文件目录之外的"单条直写"会破坏蓝绿语义。修订与文件语料并列切块，`sourceDoc = rev-<id>`，ruleId 仍是 `hash(sourceDoc|headingPath)`，重跑幂等。
3. **`chunk(Path)` 改为委托字符串重载并自行解析文件内 frontmatter**（`parseFrontMatter(raw)`）：修本轮编译期发现的重载缺失时顺手钉了回归用例（title 进 headingPath、promo- 前缀两条入口意图一致），既有用例零漂移（10 条全绿）。
4. **守卫复用 `guardedBizMock`**（两条准入线取或：ops token 或坐席角色）；修订非资金动作，ops token 合法（ADR 0063）。买家的修订请求 403 且 `verify(http, never()).send()` 钉死"守卫在转发之前"。
5. 计数器埋代理层 2xx 分支：`shoppilot_knowledge_revisions_total{result=filed|published|rejected}`，`RoleGuardAndActorTest` 用 `SimpleMeterRegistry` 断言增量。
6. 拉取口响应必须为数组形状（`root.isArray()`），对象/坏 JSON 一律抛——测试先行抓到的洞：`{"revisions":[]}` 原来会被静默遍历成零条。
7. 零新增配置占位符（复用 `shoppilot.bizmock.*`），`ConfigValidationTest` 未动。

**验证落点**

- `KnowledgeRevisionSourceTest` 5 条（JDK HttpServer 假下游）：解析、非 2xx、不可达、坏形状（全部断言抛 `IllegalStateException`）+ IngestRunner 中止路径（拉取失败先于任何写入，复审补记见票 187）。
- `MarkdownChunkerTest` 7→10 条；`RoleGuardAndActorTest` 6→11 条（修订代理三件套 + 复核写口两条，复审补）。
- 全量 verify BUILD SUCCESS；README 指标名计数换代在本票 187 顺手完成（72/74，含复点漂移说明）。

**三个现场追问**

1. 为什么 ingest 日志统计行重要？它是"修订真的进库了"的唯一机器可读现场（`文档 N 篇，知识修订 M 条`），活体验收对着它核；
2. 纪元推进后旧缓存为什么不用改代码？L1 键、L2 payload filter、检索 filter 都含纪元，推进即作废，零新增代码（票 187 引用既有用例）；
3. 代理口为什么不做网关侧形状校验之外的改写？body 逐字节透传是 ADR 0028 两分原则，形状校验归 biz-mock 前置（附稿口 400 文案在下游）。
