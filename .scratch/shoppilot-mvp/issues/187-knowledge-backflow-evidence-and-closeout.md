# 票 187：咬合证据与收口

Round: 43（spec：[round43-spec-knowledge-backflow.md](../round43-spec-knowledge-backflow.md)）
ADR: 0078　Status: implemented　Blocked by: 186

## What to build

1. **咬合证据**（不造新门——失效机制全部既有，本轮只钉证据链）：
   - biz-mock 生命周期全表（185 的用例在此票收口核对）。
   - gateway：`L1Cache` 键含纪元 / `CacheService` 匹配含纪元 / 检索 filter 含纪元——各找一条
     既有用例引用进本票，缺哪条补哪条（预期都不缺）。
   - 变异对照收口：①去掉 REVIEWED 前置 → 红；②ingest 拉取失败吞成空列表 → 红。
2. **活体脚本** `scripts/verify-knowledge-backflow.mjs`：点踩 → 复核完成 → 附稿 → 发布 →
   （提示重跑 ingest）→ 纪元推进后旧答案缓存不可见。**本票照登未实跑**（活体依赖起栈，
   资源账沿用 round23 裁定 A）；脚本语法过 `node --check`。
3. **文档换代**：
   - `docs/CODE_MAP.md`：biz-mock 表新增知识修订区域；请求主链路 ingest 行补第二个语料源；
     代理口落点。
   - `docs/EVIDENCE.md`：本轮读数与证据边界一行。
   - `CONTEXT.md`：新术语「知识修订 (Knowledge Revision)」（增词不改旧词）。
   - `README.md`：指标名计数换代。
4. **tracker 收口**：三票 Status + Handoff notes；tracker README 索引补 185-187 三行；
   round43 spec 标注收口状态。

## Blocked by / Verify

186。Verify：

```powershell
.\mvnw.cmd -B -ntp verify
git diff --check
git status --short
node --check scripts/verify-knowledge-backflow.mjs
```

## 验收项

- 全仓 verify 绿，读数换代（新增用例数如实记录）。
- 两条变异对照实测为真（修前红 / 还原绿）。
- `knowledge/` 语料、gold、`judge()`、阈值零改动（`git status --short` 无这些路径）。
- 活体脚本未实跑**照登**，不声称咬合已被活体验证——JVM 层证据 + 既有纪元机制的用例引用即本轮边界。

## Handoff notes

**关键决策**

1. **咬合证据链（零新代码）**：纪元三处既有——`cache/L1Cache.key`（key 含 `kbEpoch`）、`cache/L2SemanticCache`（payload filter 字段含 `kb_epoch`，`L2SemanticCacheTest:124` 钉着）、`knowledge/HybridRetriever`（检索 filter 含 `kb_epoch`）。ingest 推进纪元 ⇒ 三处同时失效，旧答案缓存与旧语料一起退役；本轮未新增任何失效逻辑。
2. **变异对照实跑为真**：① 注释 `REVIEWED` 前置 → `KnowledgeRevisionTest` 1/6 红（`pendingFeedbackCannotCarryRevision`）；② `fetchPublished` 吞成 `return List.of()` → `KnowledgeRevisionSourceTest` 4/4 红。两处跑完即还原，最终树无变异残留。
3. **活体脚本照登未实跑**：`scripts/verify-knowledge-backflow.mjs`（`node --check` 通过）覆盖点踩→REVIEWED→附稿→发布→重复发布 409→admin 拉取口→计数器，并提示 ingest 纪元推进另起离线作业。**本轮边界：咬合未被活体验证**，证据 = JVM 层 + 既有纪元机制用例引用。
4. 文档换代四处：`CODE_MAP.md`（ingest 第二语料源 / biz-mock 修订区 / 主链路行 / Test Map 行）、`EVIDENCE.md`（round43 条目含变异读数与边界）、`CONTEXT.md`（「知识修订」新增 + 「复核队列」条目从"尚未实现"改为"round43 起接通"——该条目描述的正是本轮，改它是执行不是改术语结论）、`README.md`（指标名计数 56/58 → 72/74，round36-42 未逐轮复点的漂移如实照登，不回溯补账）。
5. 读数换代：全量 JVM **757 tests / 128 suites**（surefire 四模块汇总，含 tool-api 12；复审补 3 条后），BUILD SUCCESS。

**验证落点**

- `.\mvnw.cmd -B -ntp verify`（后台跑完 exit 0）；`git diff --check` / `git status --short` 收口时执行，`knowledge/` 语料、gold、`judge()`、阈值零改动（无这些路径的 diff）。
- `node --check scripts/verify-knowledge-backflow.mjs` 通过。

**复审补记（2026-11-01，双轴 code review 后推送前）**

双轴复审（Standards / Spec）结论：无硬性违规。按其 findings 补了三处：

1. 补 `KnowledgeRevisionSourceTest.ingestAbortsBeforeAnyWriteWhenRevisionFetchFails`：钉"先拉取后写入"的顺序——拉取失败时 probe 之外零 embedding、Qdrant/ES 零写、纪元不推进（`ingest()` 放宽为包内可见作测试缝，run() 会 System.exit 不能真跑）。
2. 补 `RoleGuardAndActorTest` 复核写口两条：坐席 PATCH → 下游方法/路径/租户头齐；买家 403 且请求不出网关（票 186 验收项此前只覆盖修订三件套）。
3. 修活体脚本 4b 注释：形状校验在 biz-mock 前置，不在网关（原先注释写反）。

**登记不修（判断题，留给后续票）**：① 修订状态用裸字符串而非 `TicketStatus` 式枚举（biz-mock domain 家法）；② `IllegalArgumentException` 在 submit 链上既映射 400 又映射 404，靠 catch 位置区分；③ ops-token-only 路径经 `selfReported(null)` 得到的 actor 语义（谁按的发布门）值得下轮核对 ADR 0063 口径；④ `OpsController` 三处计数与 `guardedBizMock`/`guardedTicket` 的取或逻辑有重复，可抽 helper。

**三个现场追问**

1. 纪元什么时候真的推进一次？下一次人工跑 ingest 时（`spring-boot:run -P ingest`），修订随之进语料——本链路不提供"发布即生效"；
2. 为什么接受 56→72 的计数漂移而不是只写 57？漂移是事实，掩盖它 = 用一个假精确数字盖住六轮未复点，照登 + 复点命令可复核；
3. 谁来跑活体？下一位起栈的 agent 或所有者，脚本前置条件写在文件头（网关 :8082 + biz-mock :8091 + `SHOPPILOT_INTERNAL_TOKEN`）。
