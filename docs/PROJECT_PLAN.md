# ShopPilot v1.0 Freeze and Portfolio Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task by task. Steps use checkbox syntax for tracking.

**Goal:** 把 ShopPilot 从持续扩张的面试作品收口为 `v1.0.0`，补齐 3 分钟可读材料与面试掌握资产，然后用面试反馈触发后续变更。

**Architecture:** 不改核心业务架构，不新增功能面。先锁定当前已通过 CI 的代码基线，再补发布说明、作品集入口、ticket 交接与面试题库，最后打 tag 并冻结。

**Tech Stack:** Java 21、Spring Boot 3.3.5、Maven Wrapper、GitHub Actions、Markdown。发布阶段不新增依赖。

**Spec:** `D:\WorkBuddyData\ShopPilot-定位与方向决策-20260916.md`。该文件出具时的代码基线 `cf887f1` 已过期；本计划以当前仓库基线为准重新校准。

**Plan baseline:** `HEAD 11cd26c`，`origin/main` 同步；CI run `35092864900` 成功，49 秒；收口审计 `PASS 93 / FAIL 0 / SKIP 2`；票 01-32 已收口。

**Execution status (2026-09-17):** Task 1-7 已落地；本地 JVM verify 为 `3 + 12 + 206 = 221` 全绿，`verify_eval_judge` 为 `40/40`，收口审计 `PASS 93 / FAIL 0 / SKIP 2`（共 95 项）。CI run `35104751284` 成功后，tag `v1.0.0` 已指向 release commit `7f4334c` 并完成冻结公告。独立 cold-read 已判 `PASS as a three-minute introduction`；Task 6 八站路线、站点课程与证据速查卡已落地，Task 8 的 portfolio / interview 门禁已核对。

## Global Constraints

- `v1.0.0` 之后不再新增功能。只允许修事实性错误、崩溃/回归、以及 ADR 0031 明确触发的变更。
- 不修改 `CONTEXT.md` 术语、既有 ADR 结论、验收判据、阈值、gold、指标分母或 Mock 参数。
- 对外数字只从 `README.md` 与 `docs/EVIDENCE.md` 引用，不制作第二份数字表。
- AI Agent 叙事是可选的并行讲法，只用于投 AI 岗；Java 后端仍是主轴，不把简历讲成“两个方向都像”。
- 一次只执行一个 ticket，收尾写 `Status` 与 `## Handoff notes`。
- 文档任务不跑全量活体门禁；发布 commit 必须经过 JVM verify、收口审计和 GitHub Actions。

---

### Task 0: Record the recalibrated baseline

**Files:**
- Create: `docs/PROJECT_PLAN.md`

**Interfaces:**
- Consumes: 外部定位文件、当前 tracker、CI 与审计结果
- Produces: 本规划；后续任务以此处基线判断“定位文件是否已过期”

- [x] **Step 1: Recheck current repository state**

Run:

```powershell
git status --short --branch
git log -5 --oneline --decorate
gh run list --workflow ci-subset.yml --limit 1 --json databaseId,headSha,status,conclusion,url
```

Expected: `HEAD == origin/main`；最新 CI 对应当前 HEAD 或其后继提交，且 conclusion 为 success。

- [x] **Step 2: Record the evidence gap that changed the plan**

Run:

```powershell
$files = Get-ChildItem .scratch/shoppilot-mvp/issues -File | Sort-Object Name
foreach ($f in $files) {
  if ((Get-Content $f.FullName -Raw) -notmatch '## Handoff notes') { $f.Name }
}
```

Expected: 当前输出只有票 `21` 到 `27`；这七张票需要在 Task 5 补齐，而不是照外部定位只补 21-26。

- [x] **Step 3: Commit the plan**

```powershell
git add docs/PROJECT_PLAN.md
git commit -m "docs(v1.0): add freeze and portfolio plan"
```

### Task 1: Add the interview-feedback reopen trigger

**Files:**
- Create: `docs/adr/0031-interview-feedback-is-the-sixth-reopen-trigger.md`
- Modify: `.scratch/shoppilot-mvp/README.md`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: ADR 0030 的五条触发条件
- Produces: 第六条触发条件；所有冻结期改动必须引用它或前述五条之一

- [x] **Step 1: Write ADR 0031**

创建 ADR，正文必须包含：

```markdown
# 面试同一缺口被问两次且一天内可补，才是冻结期的新功能触发条件

Context: v1.0 定位为面试作品。ADR 0024 只定义什么能进入范围，ADR 0030 只定义五条重开条件；两者都没有把真实面试反馈接入停止机制。

Decision: v1.0.0 冻结后，只有同一面试缺口被不同面试官问到两次以上，且能在 1 天内以不改变验收判据的方式补齐，才允许新开功能 ticket。单次提问、纯偏好建议、需要重写架构或超过 1 天的事项只登记，不重开。

Consequences: 未触发时不做是决定，不是拖延；同一缺口达到两次时自动回到射程；AI 包装、告警、工单持久化、跨实例验证仍分别服从 ADR 0030 的原有触发条件。
```

不要修改 ADR 0030；新增 ADR 只接入第六个信号源。

- [x] **Step 2: Mark the tracker policy**

在 `.scratch/shoppilot-mvp/README.md` 的“当前状态”下增加：

```markdown
- v1.0.0 冻结后默认不开功能票；重开条件见 ADR 0030 五条与 ADR 0031 的面试反馈触发。
```

- [x] **Step 3: Add the rule to AGENTS**

在 `AGENTS.md` 的“必须遵守”下增加：

```markdown
- v1.0.0 冻结后，新功能票必须引用 ADR 0030 或 ADR 0031 的触发条件；没有触发就登记，不执行。
```

- [x] **Step 4: Verify and commit**

```powershell
git diff --check
python .scratch/shoppilot-mvp/round3-closeout-audit.py
git add docs/adr/0031-interview-feedback-is-the-sixth-reopen-trigger.md .scratch/shoppilot-mvp/README.md AGENTS.md
git commit -m "docs(v1.0): add interview-feedback reopen trigger"
```

Expected: 审计 `FAIL 0`。

### Task 2: Create the v1.0 release artifact

**Files:**
- Create: `RELEASE.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: `README.md`、`docs/EVIDENCE.md`、ADR 0024、ADR 0030、ADR 0031
- Produces: 一个面试官和外部读者都能先读的发布入口

- [x] **Step 1: Write `RELEASE.md`**

只写六节，不复制 README 的完整指标表：

```markdown
# ShopPilot v1.0.0

## What it does
一句话说明买家问题如何经状态机走到政策答复、工具办理或可查工单。

## Verified highlights
三条最好的实测数字，只给结论并链接 `README.md` / `docs/EVIDENCE.md`。

## Known red lines
拦截率、吞吐、TTFT 三条未达成及各自归因。

## Reproduce
链接 README 的快速开始、三条演示、CI badge。

## Known limits
H2、mock 身份、单实例、数据规模与异机未验。

## Freeze policy
v1.0.0 后只接受 ADR 0030/0031 触发或事实性修正。
```

- [x] **Step 2: Put the release entry at the top of README**

在 README 第一屏的接手导航前增加一行：

```markdown
这是冻结版 v1.0.0：先读 `RELEASE.md`，再按需要进入作品集、代码地图或证据库。
```

- [x] **Step 3: Verify links and release facts**

```powershell
git diff --check
python scripts/verify_eval_judge.py
```

Expected: `40/40`；`RELEASE.md` 中每个数字都能在 README/EVIDENCE 找到同源。

- [x] **Step 4: Commit**

```powershell
git add RELEASE.md README.md
git commit -m "docs(v1.0): add release notes"
```

### Task 3: Build the 3-minute portfolio layer

**Files:**
- Create: `docs/portfolio-hr.md`
- Create: `docs/portfolio-interview.md`
- Modify: `README.md`
- Optional: `docs/portfolio-ai-agent.md`

**Interfaces:**
- Consumes: `RELEASE.md`、README 的架构图与指标、`docs/interview-qa.md`
- Produces: HR 初筛版与面试官会前版；不替代 README 证据库

- [x] **Step 1: Write `docs/portfolio-hr.md`**

严格控制在 5 行内容以内：

```markdown
项目定位：高并发智能客服与业务网关，面试作品，单机可复现。
业务问题：热点重复不进模型、对话能落业务、语义缓存不串号、依赖故障可降级。
核心结果：命中路径 P99 22ms；Token 节约 62.4%；虚拟线程 400-800 并发 +64%。
工程取舍：三条指标未达标也保留实测值与归因，不换口径刷绿。
技术关键词：Java 21 / Spring Boot / Function Calling / RAG / Redis / Qdrant / ES。
```

- [x] **Step 2: Write `docs/portfolio-interview.md`**

固定为四块：

1. 一屏架构图：复用 README 图，不重画第二版。
2. 四个工程问题：重复热点、业务闭环、语义串号、依赖降级。
3. 三个数字与一个踩坑：P99 22ms、Token 62.4%、虚拟线程 +64%；冷缓存雪崩 `35264 -> 18`。
4. “最值得问我的三处”：缓存后置、工具循环上限 2 轮、判据与量具分离。

所有数字只写摘要，并链接 README/EVIDENCE。文末固定写：

```markdown
完整证据、未达成项与限制见 `README.md` 和 `docs/EVIDENCE.md`。
```

- [x] **Step 3: Add README entry points**

在“接手导航”中前三行改为读者分流：

```markdown
- 想 3 分钟看懂：`docs/portfolio-interview.md`
- 想先看能否投递：`docs/portfolio-hr.md`
- 想核对全部证据：`docs/EVIDENCE.md`
```

原有 AGENTS、tracker、CODE_MAP 入口保留在后续行。

- [ ] **Step 4: Optional AI-role packaging**

只有明确以 AI Agent 岗为主投方向时，才新增 `docs/portfolio-ai-agent.md`。保持同一份代码，只调整讲述顺序为：

```text
有界 Agent 状态机 -> Function Calling 工具编排 -> 三级意图路由
-> 混合检索 RAG -> 语义缓存/极性守卫 -> fail-open/fail-closed 决策
```

不得改写 README 标题、不得暗示存在未实现的 Agent 自主规划，也不得把 Java 工程主轴降成附注。

- [ ] **Step 5: Run the cold-read test**

让未参与项目的人只看 `docs/portfolio-interview.md` 三分钟，然后必须能回答：

```text
它解决什么问题？
最难的技术取舍是什么？
最好和未达成的数字分别是什么？
一个真实踩坑是什么？
```

答不出任意一项就继续删内容，不补新内容。

当前完成的是结构与数字自检；独立读者的三分钟 cold-read 仍需在发布前由未参与项目的人执行。

- [x] **Step 6: Commit**

```powershell
git add README.md docs/portfolio-hr.md docs/portfolio-interview.md
git commit -m "docs(v1.0): add three-minute portfolio"
```

### Task 4: Freeze the public release

**Files:**
- Modify: `.scratch/shoppilot-mvp/README.md`

**Interfaces:**
- Consumes: Tasks 1-3 的全部产物
- Produces: `v1.0.0` tag；后续改动必须有触发来源

- [x] **Step 1: Run the release gate locally**

```powershell
.\mvnw.cmd -B -ntp verify
python .scratch/shoppilot-mvp/round3-closeout-audit.py
git diff --check
git status --short --branch
```

Expected: JVM 测试全绿；审计 `FAIL 0`；工作树 clean。

- [x] **Step 2: Push and wait for CI**

```powershell
git push origin main
gh run list --workflow ci-subset.yml --limit 1 --json databaseId,headSha,status,conclusion,url
```

Expected: 最新 run 对应 release commit，conclusion 为 success。

- [x] **Step 3: Tag the exact release commit**

```powershell
git tag -a v1.0.0 -m "ShopPilot v1.0.0"
git push origin v1.0.0
```

Tag 必须指向 CI 已绿的 commit；CI 在 tag 后变红时先撤回 tag，不许把红提交留在版本号下。

- [x] **Step 4: Record the freeze**

在 tracker 当前状态段增加：

```markdown
- `v1.0.0` 已冻结；当前没有开放的 feature ticket。后续新工作必须引用 ADR 0030 或 ADR 0031。
```

```powershell
git add .scratch/shoppilot-mvp/README.md
git commit -m "docs(v1.0): freeze feature scope"
git push origin main
```

### Task 5: Backfill ticket handoffs and regenerate interview Q&A

**Files:**
- Modify: `.scratch/shoppilot-mvp/issues/21-bind-address-gated-credential-fail-fast.md`
- Modify: `.scratch/shoppilot-mvp/issues/22-conversation-owned-by-shop-and-customer.md`
- Modify: `.scratch/shoppilot-mvp/issues/23-dependency-health-outside-readiness.md`
- Modify: `.scratch/shoppilot-mvp/issues/24-mdc-trace-correlation-across-async.md`
- Modify: `.scratch/shoppilot-mvp/issues/25-rest-error-envelope.md`
- Modify: `.scratch/shoppilot-mvp/issues/26-debug-console-remaining-defects.md`
- Modify: `.scratch/shoppilot-mvp/issues/27-writeback-pool-shutdown-seam-and-saturation.md`
- Modify: `docs/interview-qa.md`

**Interfaces:**
- Consumes: 七张 ticket 的决策、代码、ADR 与验证记录
- Produces: 完整 Handoff notes 与重新生成的面试问答库

- [x] **Step 1: Add `## Handoff notes` to tickets 21-27**

每张票写三条可与本人现场对话的追问与答案。建议起问：

| Ticket | 三条追问 |
| --- | --- |
| 21 | 为什么触发器取绑定地址而不是 prod profile？为什么回环只是必要条件？默认值为什么只能有一个来源？ |
| 22 | 为什么会话归属必须加买家段？为什么归一化买家标识会拆开两道防线？为什么旧状态不迁移？ |
| 23 | 为什么 Qdrant/ES/知识库不进 readiness？`deps` 组修了假健康灯的哪一端？真上 K8s 要改什么？ |
| 24 | 为什么 MDC 不能依赖线程继承？手工包装跨了哪些边界？漏接一个边界会出现什么日志？ |
| 25 | 为什么错误信封只覆盖网关自产错误？为什么透传下游错误会破坏契约？400/405 保持不变说明了什么？ |
| 26 | 为什么页面断言要进 seam？为什么不能只测静态 HTML 字符串？`aria-disabled` 欠账后来去哪了？ |
| 27 | 为什么停机要区分 await、timeout、force？为什么 caller-runs 要计数？队列深度解释了哪个指标的适用边界？ |

答案必须来自 ticket、ADR 和实现，不写“以后会”或未验证承诺。

- [x] **Step 2: Regenerate the Q&A**

```powershell
python scripts/collect_interview_questions.py
```

Expected: 不再列出票 21-27；覆盖 ticket 数应为 32/32。若某张票的格式未被解析，修 ticket 格式，不改生成器来猜自由文本。

- [x] **Step 3: Verify the generated artifact**

```powershell
git diff --check
python scripts/verify_eval_judge.py
```

Expected: `40/40`。

- [x] **Step 4: Commit**

```powershell
git add .scratch/shoppilot-mvp/issues docs/interview-qa.md
git commit -m "docs(interview): backfill round13-14 handoffs"
```

### Task 6: Refresh the interview walkthrough against v1.0

**Files:**
- Create: `docs/interview-guide.md`
- Create: `docs/interview-mastery/MISSION.md`、`RESOURCES.md`、`NOTES.md`、`learning-records/`、`reference/`、`lessons/`
- Modify: `README.md`

**Interfaces:**
- Consumes: `docs/PROJECT_PLAN.md`、`docs/CODE_MAP.md`、`docs/EVIDENCE.md`、当前 ticket 与命令
- Produces: 不依赖外部桌面文件、可随仓库克隆走的 8 站掌握路线

- [x] **Step 1: Build eight lifecycle stations**

按下面固定顺序建站；每站的 `First read` 使用这些路径，`Reproduce` 使用仓库当前命令，不得引用外部桌面文档：

| # | Station | First read |
| --- | --- | --- |
| 1 | 入口与身份 | `shoppilot-gateway/src/main/java/com/shoppilot/gateway/identity/AuthFilter.java`、`JwtService.java`、`TenantContext.java`、`shoppilot-gateway/src/main/java/com/shoppilot/gateway/config/DevDefaultsPolicy.java` |
| 2 | 意图三级级联 | `shoppilot-gateway/src/main/java/com/shoppilot/gateway/triage/TriageEngine.java`、`T0RuleLayer.java`、`T1CentroidLayer.java` |
| 3 | 两级缓存与穿透合并 | `shoppilot-gateway/src/main/java/com/shoppilot/gateway/cache/CacheService.java`、`L1Cache.java`、`L2SemanticCache.java`、`PolarityGuard.java`、`SingleFlight.java` |
| 4 | 检索与入库 | `shoppilot-gateway/src/main/java/com/shoppilot/gateway/knowledge/HybridRetriever.java`、`EmbeddingClient.java`、`shoppilot-gateway/src/main/java/com/shoppilot/gateway/ingest/MarkdownChunker.java` |
| 5 | Agent 编排与工具 | `shoppilot-gateway/src/main/java/com/shoppilot/gateway/agent/AgentStateMachine.java`、`ToolDispatcher.java`、`shoppilot-tool-api/src/main/java/com/shoppilot/tool/` |
| 6 | 业务中台与隔离 | `shoppilot-biz-mock/src/main/java/com/shoppilot/bizmock/service/BizMockService.java`、`repo/`、`domain/` |
| 7 | 降级、限流、可观测 | `shoppilot-gateway/src/main/java/com/shoppilot/gateway/agent/FallbackService.java`、`shoppilot-gateway/src/main/java/com/shoppilot/gateway/ratelimit/RateLimitService.java`、`shoppilot-gateway/src/main/java/com/shoppilot/gateway/config/RuntimeStateMetrics.java` |
| 8 | 量化与门禁 | `scripts/run-acceptance.ps1`、`scripts/run_loadtest.py`、`scripts/build_loadtest_report.py`、`docs/EVIDENCE.md` |

每站正文固定包含五段：`Decision`、`Reproduce`、`Three questions`、`Failure symptom`、`Evidence link`。`Three questions` 必须逐题给出答案，不能只列问题。

- [x] **Step 2: Replace stale numbers**

使用当前基线：

```text
JVM tests: 221
Acceptance audit: 95 checks, 0 FAIL
CI: build + 221 JVM tests
ADR range: 0001-0031 after Task 1
```

不要复制外部《掌握路线图》里的 155 tests、28 ADR 或旧 ticket 状态。

- [x] **Step 3: Add README link**

在接手导航增加：

```markdown
- 想系统准备面试：`docs/interview-guide.md`
```

- [x] **Step 4: Verify and commit**

```powershell
git diff --check
git add docs/interview-guide.md docs/interview-mastery README.md docs/PROJECT_PLAN.md
git commit -m "docs(interview): add v1 walkthrough"
```

### Task 7: Operate the freeze with interview feedback

**Files:**
- Create: `docs/interview-feedback.md`

**Interfaces:**
- Consumes: ADR 0031
- Produces: 冻结期的外部信号账本

- [x] **Step 1: Create the feedback ledger**

```markdown
# Interview Feedback Ledger

| Date | Company / Role | Question or gap | Seen count | Fixable in 1 day | Existing trigger | Decision |
| --- | --- | --- | ---: | --- | --- | --- |
| | | | 0 | yes/no | ADR 0030 #? / ADR 0031 / none | register / open ticket |
```

- [x] **Step 2: Apply the freeze rule**

```text
0-1 occurrence: record only
2+ occurrences and <=1 day: open one tracer-bullet ticket
2+ occurrences and >1 day: write a new spec first
metric/criterion change: ADR 0030 trigger required
architectural rewrite: new effort; do not reopen v1.0 quietly
```

- [x] **Step 3: Commit**

```powershell
git add docs/interview-feedback.md
git commit -m "docs(v1.0): add interview feedback ledger"
```

### Task 8: Final definition of done

**Files:**
- Read: `RELEASE.md`
- Read: `docs/PROJECT_PLAN.md`
- Read: `.scratch/shoppilot-mvp/README.md`

**Interfaces:**
- Consumes: Tasks 1-7
- Produces: 可宣布的 v1.0 冻结状态

- [x] **Step 1: Check the release gates**

```powershell
git status --short --branch
git tag --list v1.0.0
gh run list --workflow ci-subset.yml --limit 3 --json headSha,status,conclusion,url
python .scratch/shoppilot-mvp/round3-closeout-audit.py
```

Expected:

```text
worktree clean
v1.0.0 exists
release commit CI success
audit FAIL 0
```

- [x] **Step 2: Check the portfolio gates**

```text
docs/portfolio-hr.md exists
docs/portfolio-interview.md exists
cold-read test passes
no duplicate metric table
```

- [x] **Step 3: Check the interview gates**

```text
tickets 21-27 have Handoff notes
docs/interview-qa.md covers 32/32 tickets
docs/interview-guide.md uses current 221-test baseline
```

- [x] **Step 4: Announce the freeze**

冻结公告只写：

```text
ShopPilot v1.0.0 is frozen.
New work requires ADR 0030 trigger or ADR 0031 interview-feedback trigger.
Known red metrics and limits remain documented.
```

## Out of Scope at v1.0

- Round16+ 功能扩展、P1 指标挂账、最小告警集、Docker/部署形态。
- 工单持久化、RESOLVED 回流、跨实例 singleflight、分布式追踪、异机复现。
- 英文文档、开源运营、Issue/PR 模板、依赖升级。
- 为让三条红指标变绿而改口径、阈值、Mock 延迟或采样方式。
- 把作品改成“大模型自主 Agent”项目，或重写核心架构。

## Self-Review

**Spec coverage:** 外部定位的推荐路径 B + C + D（条件）+ A 已分别映射到 Task 1-4、Task 5-6、Task 3 Step 4、Task 7-8。

**Placeholder scan:** 未使用 TBD/TODO；每个新增文件都有内容边界、验证动作和完成判据。

**Type consistency:** 冻结触发统一称为 ADR 0030 五条与 ADR 0031 第六条；作品集文件统一为 HR / Interview / 条件式 AI Agent 三层；规划基线统一为 `11cd26c`、221 tests、95 checks。
