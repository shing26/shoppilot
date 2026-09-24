# ShopPilot Tracker

这里是本仓的正式 ticket tracker，不是临时草稿目录。目录名里的 `.scratch` 是历史命名；路径已被 README、ticket、审计脚本和提交记录大量引用，不要为了改名而移动。

最后整理日期：2026-09-21。

## 当前状态

- 当前交付路线见 [`docs/PROJECT_PLAN.md`](../../docs/PROJECT_PLAN.md)：v1.0 收口、3 分钟作品集、面试掌握与冻结；它不新增功能 frontier。
- `v1.0.0` 已冻结，tag `v1.0.0` 指向 CI run `35104751284` 验证通过的 release commit `7f4334c`；默认不开功能票，重开条件见 ADR 0030 五条与 ADR 0031 的面试反馈触发；round17（票 34-41）按 ADR 0033 记录的所有者政策覆盖执行；编排层选型与 provider 缝的触发线见 ADR 0032。
- 2026-09-16：v1.0 release candidate 的 ADR、release note、3 分钟作品集与票 21-32 问答补录均已落地；本地 JVM verify 221 绿、量具 40/40、收口审计 `PASS 93 / FAIL 0 / SKIP 2`。
- 2026-09-18：round16 质量轮增加网关主链路 JVM 集成缝，当前 JVM verify 为 `3 + 12 + 209 = 224` 绿；历史 round14/round15 的 221 读数仍保留为当时落点。
- 2026-09-19（晚）～09-20（凌晨）：票 41 工具循环语义钉死——超限对齐 ADR 0008 字面（预算检查后模型仍要工具 → `TOOL_ROUNDS_EXHAUSTED` 落工单）、`parallel_tool_calls:false` 请求约束 + 多调用防御分支、写动作守卫；票 34 评测量具进 CI（selfcheck + rescore 门禁）；票 35 Prompt 版本化（外置 v1.0.0.md + meta.json fail-fast + SSE/评测报告携带版本）；票 36 情绪门（词典层 0 token 定案 + dev 口径 LLM 分类兜底 + EMOTION_ESCALATION 第 10 降级因 + 工单 priority=high）；票 37 满意度反馈闭环（显式点踩落 biz-mock feedback 表 + 三个隐式信号计数 + ingest 待复核队列与复核流转）；票 38 三渠道契约（channel 包 + webhook/email 入站 + ChatAdmission 提取 + 限流/meta/计数按渠道）；风格票（StyleService 档位矩阵 + 基座+注入段拼装 + SSE meta 带 style；无编号，票号 39 归 Plan）；有意不做清单成文（README 按 ADR 0040 增"有意不做 + 触发条件"小节；无编号）；票 39 Plan 有序步骤（前序依赖表达式 + 前步失败即中止 + `shoppilot_plan_steps_total` 分账，硬闸门 95.0% → 95.0% 通过）。
- 2026-09-20（凌晨，活体栈恢复时抓到并修复的两个真缺陷）：`FeedbackService` 双构造器致网关无法启动（`9a0052d`）；情绪分类器把平静业务查询误判 URGENT 导致全量评测动作类掉到 22-72%（`daa40cf` 从严校准 + 分类提示词外置为版本资产）。当前 JVM verify 为 `3 + 15 + 249 = 267` 绿。
- 2026-09-19：票 33 做了一次双轴复核（Standards / Spec），两条轴都抓到工具循环用例假绿；已补请求体断言并做变异对照（删掉 `AgentStateMachine` 里的 tool 回填即变红）。同一天的登记型改动还有 ADR 0032 与 `.gitattributes`。
- 2026-09-19（下午）：外部审查（5 份跨项目报告）交叉核实后并入 round17——两个发现升级为前置票 41（工具循环语义钉死），登记项落 round17 spec 附录；round17 草稿（spec、ADR 0033-0040、评测集 part4-7、票 41）首次入仓。
- 2026-09-20（凌晨）：round17 收口——全票（34-39、41、风格票、有意不做成文票）已完成并各自留 Handoff；票 39 硬闸门同一 180 条 gold 改前/改后均 95.0%（分意图逐项一致），产物入库登记；`interview-qa.md` 重生成至 157 问/覆盖 42 个 ticket；README 主链路与架构章节补齐 round17 模块；审计 E5/E5b 常数与 B7 禁面按换代指针更新。当前 JVM verify 为 `3 + 15 + 249 = 267` 绿。
- 2026-09-20（下午）：本机全量活体验收第一次真正跑起来（便携版 PowerShell 7.4.20 落在 `D:\tools\pwsh-7.4.20`，Docker 引擎与 Ollama 手工拉起）。**两次落点**：18:12 那次 2005s、10 步红——Ollama 冷启动且 `OLLAMA_MAX_LOADED_MODELS=1`，向量化 3s 超时 < 模型换入换出约 6s，检索降级后写回被 ADR 0006 资格拒掉，缓存相关六步全红；修 Ollama（两模型同时驻留，嵌入 0.9s / 生成 1.2s）并修掉我脚本的两个缺陷后，18:30 那次 **503s、16 步绿 / 6 步红**（plan、hitzero、fallback、emotion、feedback、plansteps）。审计 G6 常数随之换代 `[3, 12, 206]` → `[3, 15, 249]`（build/unit 实测 `3 + 15 + 249 = 267`），本地读数 `PASS 93 / FAIL 2 / SKIP 0`（两红是 A2 并发写入者与 H12 的连带——入仓读数产物记的是本机全绿那一跑，不为适配这次的脏工作树重落一份带红的）。六步红的四条根因与脚本侧修复登记在 round17 spec 的"活体验收登记"表里：情绪门第二层给 94% 请求各加一次分类调用（破坏 local/dev 的"命中路径零模型调用"）、纯转人工请求被小模型判成紧急、Plan 两步链在 3B 模型下不可复现、feedback 三条隐式信号未观测到增长。**一条判据都没改。**
- 2026-09-20（上午）：补做 round17 spec 登记的两条后续。① part4-7 的 56 条新用例接进离线判分器：新增 `scripts/eval_suites.py`（按 kind 分发的 `score_case`，入口 `run_tool_eval.py --suite`），判据自带 24 条夹具（0 token、无网关可跑）——夹具的强制点是 `--suite` 的预检（坏了不发请求）与 CI 新增的第三步 `python3 scripts/eval_suites.py`；**没有**并进 `verify_eval_judge.py`，因为收口审计 B7 有意把那份跑器（判据的断言载体）留在内容级禁面，本轮不改它、也不为放行自己收窄 B7。② 五条活体脚本接进 `run-acceptance.ps1` 矩阵（步骤名 `emotion/channel/style/feedback/plansteps`），矩阵键表从 17 步扩到 22 步。同日另修面试资产里残留的过期数字（224 → 267、17 步 → 22 步），并在票 32/33 与 `interview-qa.md` 留换代指针。落点 `fcba75c`，CI run `35501583017`（58 s）三步 0 token 门禁一次全过。
- 2026-09-21：**round18（票 42-44）收口**——按 ADR 0041 记录的所有者政策覆盖执行，对标《项目开发判断标准-三维度评分体系》补齐 B8 数据层与 B9 测试体系。票 42 引入 Flyway（`V1__baseline.sql` 由 Hibernate 导出后固化，9 表/3 索引/1 唯一约束）并把 `ddl-auto` 全档切到 `validate`；票 43 把复核队列索引落成 `V2` 增量迁移，**工单列表的索引实测后否决**（四轮读数符号会翻转，等于量不出收益），完整读数与归因见 [`docs/slow-query-optimization-2026-09-21.md`](../../docs/slow-query-optimization-2026-09-21.md)；票 44 接上 JaCoCo，棘轮由 `scripts/check_coverage.py` 按模块分别判定（LINE 设闸 / BRANCH 只报，门槛 = floor(实测) − 1.0）。当前 JVM verify 为 `3 + 21 + 249 = 273` 绿；CI 门禁从三步 0 token 扩到**四步**（新增覆盖率棘轮），落点 `edbd3b1`、CI run `35538377810`（92 s，**五步一次全过**，含覆盖率棘轮在干净 runner 上的首次运行）。B5 可观测性按清单列为「可选补」，未执行，触发条件沿用 ADR 0030 第 5 条。
- 2026-09-23：**票 45 收口（回归修复，冻结线允许，不适用 ADR 0031）**——ADR 0034 的情绪门位于 INTAKE、先于意图判定，把一句平静的「转人工」判成 URGENT 后抢先落 `EMOTION_ESCALATION`，T0 那张含「转人工」的升级词表根本没机会执行，即 ADR 0034 回归掉了 ADR 0017 的承诺（round17 spec 活体验收登记 F2；2026-09-20 矩阵 `fallback` 步红；2026-09-23 单跑复现）。按 ADR 0042 把优先级定成**显式转人工优先**：命中 T0 升级词表时不走情绪短路，放行到 triage 由既有 `USER_REQUESTED` 出口收口（不新增出口、不动 10 状态枚举、情绪判定照常计算）。gateway 249 → 250（新增 1 条回归用例），全仓 `3 + 21 + 250 = 274` 绿，收口审计 G6 常数随之换代 `[3, 21, 249]` → `[3, 21, 250]`；活体 `verify-fallback.ps1` **7/7 PASS、exit 0**。
- 2026-09-23：**票 46 收口（回归修复）**——`SentimentGate` 把「词典层-only」只限定在 `perf` 上，`local` 也走第二层 LLM 分类；而情绪门位于 INTAKE、**先于缓存查询**，于是 local 下每个词表未命中的请求都多一跳模型调用，round17 起 `hitzero` 红（活体验收登记 F1：`sentiment_llm_classified_total=47/requests_total=50`）。按 ADR 0043 把第二层限定为 **dev 口径**（实现回到 ADR 0034 与 README 已有的措辞，不动状态机顺序、不改 ADR 原文）。gateway 250 → 253（新增 3 条：回归 + 机制 + dev 正对照），全仓 `3 + 21 + 253 = 277` 绿，G6 常数换代 `[3, 21, 250]` → `[3, 21, 253]`。**活体：机制已实测转绿**（local 档三次请求分类增量 **0**，trace `sentiment=UNCERTAIN via local-lexicon-only`）；**整脚本 `verify-hit-zero-llm.ps1` 未达成**，根因是环境——本机 `OLLAMA_MAX_LOADED_MODELS=1` 使 bge-m3 与 qwen2.5:3b 不能同时驻留，未命中路径的生成调用超读超时，按未达成登记不摘红，放开条件 = 该值 ≥2 并重启 Ollama。
- 2026-09-24：**外部清单交叉核实登记（登记不执行）**——来源 `D:\WorkBuddyData\RAG与Agent管线_指标闭环可补齐项清单_2026-09-24.html` 与 `七项目定位档案_2026-09-24.html`（该目录不在版本控制下，属参考不属裁决）。ShopPilot 相关说法逐条对仓核实，**四处与仓内实测不符，引用前须按本仓口径**：① 清单 C4「ShopPilot 的 Qdrant 距离度量未声明（`grep -rnE "Cosine|Distance\.|hnsw" shoppilot-gateway/src/main/java/` → 0 命中）」**不成立**——`gateway/knowledge/QdrantRestClient.java:52` 在建集合时硬编码 `distance: Cosine`；口径落在客户端而非 `application.yml`，但已锁定，C4 不构成本仓缺口。② ADR 数清单记 30，实为 **42**（`docs/adr/` 0001-0043，无 0022）**（换代指针：同日稍后 round19 加 ADR 0044，现为 43 份）**。③ 测试数清单记 170 `@Test` + 10 参数化，实为 **226 `@Test` + 10 `@ParameterizedTest`**（声明数），本地执行 `3 + 21 + 253 = 277`**（换代指针：round19 收口后为 `3 + 21 + 269 = 293`）**；清单自述的「170 vs 前轮 221 是声明数 vs 展开数」这层解释也不对（221 是更早一版的执行数）。④ 清单「降级原因 README 写 9、旧简历写 8、**仓内自身不一致**」**已过期**——README 验收对照段已定换算规则：枚举 **10** 为全集、「降级」= 枚举 − 主动转人工 = **9**、`verify-fallback.ps1` 确定性表 **7 行**（6 种降级 + 主动转人工）、限流是第 8 步；三处数字是三个不同集合，不是互相矛盾。**清单核实为真的部分**：`docs/retrieval-comparison.md` 的 dense-only hit@5 16/16 对 hybrid 16/16、「更好查询 0 条」、以及「刻意不做自定义分词器 / 同义词词典 / 精排」逐字属实；`cache/PolarityGuard.java` 确实存在（清单建议把它迁移到 OpsPilot，属跨项目范式沉淀，不是本仓缺口）。**清单里唯一与本仓相关的新指标是 A4（编造/泄露计数的稳定性）**，本轮以「从既有入库产物导出」结案、未新增探针，结论与证据行登记在 `docs/EVIDENCE.md`。清单其余 19 项宿主在 OpsPilot 与 moa-gateway，不在本仓纪律约束范围内，只在报告原文保留。本条当时不改判据、不改阈值、不新增 ticket、不动主干代码（同日稍后按 ADR 0044 开了 round19，见下条）。
- 2026-09-24：**round19 开轮（票 47-52，所有者政策覆盖）**——同日那次六维度对仓复核（①需求与架构匹配 ②任务规划 ③上下文工程 ④可观测性与评估 ⑤人机协同 ⑥业务闭环）给出结论：**评估体系与护栏是强项，缺口集中在「闭环的最后一公里」与「上下文/可观测的最后一格」**。前者（工单不回流、退款停在受理态、无审批闸门、知识不反向沉淀、无 task 级判据）既不是事实错误/回归，也没命中 ADR 0030 五条触发线，按 ADR 0031 与冻结线**只登记不执行**；后者按 ADR 0044 走**所有者政策覆盖**开 round19，只做不触碰保护对象的旁挂观测项。**执行六项**：票 47 embedding 段服务端计时器（补分段耗时唯一盲区，带 `result` 标签）、票 48 Plan 提升为一等记录并随 `done` 帧输出（ADR 0036 执行语义一字不动）、票 49 `done` 帧携带上下文组成（纯观测，Prompt 逐字节不变）、票 50 显式输出上限 `max_tokens`/`num_predict`（此前全仓零命中；防输出失控非成本优化）、票 51 `CONTEXT.md` 补 5 术语 + 风格档位已知边界（**核对后它不是缺陷**：intent 是活的匹配维度，只是 `profiles.yml` 没写规则、INTAKE 阶段意图未定所以传 null 诚实）、票 52 登记收口。**登记八项**各挂触发条件（退款终态、工单回流、审批闸门、task 级判据、CI 覆盖活体、拦截率裁决、输入侧裁剪、知识反向沉淀）。**硬约束**：gateway LINE 覆盖率门槛 54.0、round18 实测 55.37%，余量仅 1.37pp，新增 gateway 代码一律配 JUnit 用例；活体只跑 `verify-console.mjs` 一条针对性步，不跑全量 22 步矩阵（本机 Ollama 单模型驻留会引入已知环境红）。**拦截率 74% vs 80% 本轮明确维持挂起**（三个裁决选项已列进登记节，一个都不选，理由见 ADR 0044 的 Considered Options）。
- 2026-09-24：**round19 收口（票 47-52，ADR 0044 所有者政策覆盖）**——六维度复核里「旁挂轴」的六项全部落地：票 47 `EmbeddingClient` 加 `shoppilot_embedding_latency_seconds`（带 `result` 标签，与既有 `shoppilot_embedding_calls_total` **同分法**，补分段耗时唯一盲区——此前 TTFT/稠密/词法/模型四段都有服务端计时器，只有向量化没有），`ttft_attribution.py` 改为优先读指标、读不到才回退探针；票 48 计划步骤提升为一等记录 `AgentResult.PlanStep`（工具名/状态/耗时/参数）并随 `done` 帧与同步响应输出，**ADR 0036 的执行语义一字未动**（`PlanExecutionTest` 原样通过）；票 49 `done` 帧与同步响应加 `context`（`ruleIds`/`historyTurns`/`estimatedPromptTokens`），**Prompt 文本逐字节不变**（用例钉着）；票 50 显式输出上限 `max_tokens` / `options.num_predict`（此前全仓零命中；0 = 不限制的向后兼容口）；票 51 `CONTEXT.md` 补 5 个 canonical term（缓存写回资格 / 槽位 / 计划 / 降级 / 复核队列，38 → 43）并把风格档位的 intent 维度写成**已知边界**（核对后它不是缺陷）；票 52 登记收口。**读数**：gateway 253 → **269**（新增 16 条），全仓 `3 + 21 + 269 = 293` 绿；覆盖率 gateway **55.37% → 57.95%**（门槛 54.0）；CI 三门禁全过（判据自检 `40/40`、套件夹具 `ok=24`、离线 rescore `cases=180 files=6 tool_diff=4` 精确）；收口审计 G6 常数换代 `[3, 21, 253]` → `[3, 21, 269]`、`ROUND_FP` 重锚到 `06331a4`。**变异对照**：票 47 把缓存命中桶的标签改成 `remote` → 5 条里 3 条红；票 48/49 去掉 `planSteps.add(...)` 且把 `ruleIds` 写死空数组 → **恰好** `planStepsAreReportedInExecutionOrder` 与 `contextCompositionMirrorsCitationsAndHistory` 两条红。**一条判据都没改，gold 与 `judge()` 未动**。
- 2026-09-24：**round19 的三条未达成（按实登记，不摘红）**——① **活体针对性步未跑**：`verify-console.mjs` 新增的「原始 SSE 流的 `done` 帧带 `plan` 数组与 `context.ruleIds`」断言只做了语法检查（`node --check`）与静态核对，**没有真跑**（需 `up.ps1` 起栈 + Playwright 浏览器）；所以「新字段真的出现在真实 SSE 流里」这件事当前只有 JVM 层证据。② **全量 22 步活体矩阵未跑**（ADR 0044 的 Consequences 已写明本轮不跑：本机 `OLLAMA_MAX_LOADED_MODELS=1` 会让未命中路径必红，已知环境红与真实信号混在一起反而降信号），票 46 那条 `verify-hit-zero-llm.ps1` 的未达成项原样保留、放开条件不变。③ **票 50 的「180 条 gold 读数未漂移」未验证**：默认值给足余量（1024）以规避截断，但重跑 dev 全量评测需要云端额度与预算，本轮未跑；漂移与否按未验证登记，收口时不得声称「未漂移」。
- 2026-09-24：**round19 的一处口径扩面（有意，非顺带）**——票 50 往 `application.yml` 新增了环境变量占位符 `SHOPPILOT_LLM_MAX_OUTPUT_TOKENS`，被 `ConfigValidationTest.applicationPlaceholderSetIsPinned` 当场拦住（该用例逐字钉着 SHOPPILOT 占位符集合）。处置是**显式登记进那份清单**而不是绕过门禁：加 env 覆盖是扩大可配置面，本仓的家法（ADR 0012 的日预算同理）是「要改的东西应该能靠环境变量改，而不是去动仓库里的配置文件」。CI 第 5 步覆盖率棘轮同期换代读数 `gateway 55.37% → 57.95%`。
- 当前没有 `ready-for-agent` 的开放 ticket。票 01-39 与 41、风格票、有意不做成文票、票 42-52 均已收口；round17、round18、round19 均已收口。
- `done` 与 `implemented` 在本 tracker 中都表示已收口；差异只是早期票和后续 round 的用词。
- Git push 与 PR 由 `.github/workflows/ci-subset.yml` 跑干净 runner 的构建、JVM 测试与 0 token 评测门禁（票 34：判据自检 + 离线 rescore 比对；round18 票 44 加第四步：覆盖率棘轮）；全量 22 步活体验收仍是作者本机证据。
- 下一轮不能从旧 `ready-for-agent` 字样推断。重开条件与仍然挂红的裁决见 [`docs/adr/0030-round14-closure-scope-and-reopen-triggers.md`](../../docs/adr/0030-round14-closure-scope-and-reopen-triggers.md)。

2026-09-16 的整理修正了四处历史状态位：

- 票 16、18、19 已实际收口，但票面误留在 `ready-for-agent`。
- round13 spec 的票 21-26 已实际完成，但 spec 误留在 `ready-for-agent`。
- 票 28、30 文件尾部各误粘了下一张票的草稿；已移除，正式票 29、31 保留在独立文件中。

状态位的原则：以验收证据、Handoff notes 和后续提交为准；发现历史 `ready-for-agent` 与实现证据冲突时，先校正 ticket，再判断 frontier。

## Round 与 Spec

| Round | 范围 | Spec/入口 | 状态 |
| --- | --- | --- | --- |
| round3 | 票 01-20，初始 MVP 到材料固化 | [`round3-plan.md`](round3-plan.md)；审计 [`round3-closeout-audit.py`](round3-closeout-audit.py) / [`round3-closeout-audit.txt`](round3-closeout-audit.txt) | done |
| round13 | 票 21-26，后端外壳加固 | [`round13-spec-backend-shell-hardening.md`](round13-spec-backend-shell-hardening.md) | done |
| round14 | 票 27-31，停机/配置/指标/记账/崩溃归因 | [`round14-spec-shutdown-metrics-and-evidence-closure.md`](round14-spec-shutdown-metrics-and-evidence-closure.md) | done |
| round15 | 票 32，CI 子集门禁 | [`round15-spec-ci-subset-gate.md`](round15-spec-ci-subset-gate.md) | done |
| round16 | 票 33，网关主链路 JVM 集成缝 | [`round16-spec-gateway-main-path-jvm-tests.md`](round16-spec-gateway-main-path-jvm-tests.md) | done |
| round17 | 票 34-41，对齐完整落地级电商客服链路（外部审查补强并入） | [`round17-spec-architecture-completeness.md`](round17-spec-architecture-completeness.md) | done |
| round18 | 票 42-44，补齐评分维度完备性（B8 数据层 + B9 测试体系） | [`round18-spec-scoring-dimension-completeness.md`](round18-spec-scoring-dimension-completeness.md) | done |
| round19 | 票 47-52，补齐可信性观测（embedding 计时器 / Plan 记录 / 上下文组成 / 输出上限）；八项登记不执行 | [`round19-spec-trust-observability.md`](round19-spec-trust-observability.md) | done |

## Ticket 索引

| # | Ticket | 状态 | 范围 |
| --- | --- | --- | --- |
| 01 | [`01-skeleton-and-compose.md`](issues/01-skeleton-and-compose.md) | done | 三模块骨架与中间件容器栈 |
| 02 | [`02-mock-jwt-identity.md`](issues/02-mock-jwt-identity.md) | done | mock JWT 发签与身份注入 |
| 03 | [`03-biz-mock-data-foundation.md`](issues/03-biz-mock-data-foundation.md) | done | biz-mock 数据底座与租户感知仓储 |
| 04 | [`04-knowledge-ingestion.md`](issues/04-knowledge-ingestion.md) | done | 政策知识库离线入库 |
| 05 | [`05-thin-vertical-slice.md`](issues/05-thin-vertical-slice.md) | done | 最细竖切：政策答案与 SSE |
| 06 | [`06-l1-cache-admission-writeback.md`](issues/06-l1-cache-admission-writeback.md) | done | L1 缓存、写回资格与 singleflight |
| 07 | [`07-intent-cascade.md`](issues/07-intent-cascade.md) | done | 意图三级级联判定 |
| 08 | [`08-es-bm25-rrf-fusion.md`](issues/08-es-bm25-rrf-fusion.md) | done | ES 倒排召回与 RRF |
| 09 | [`09-l2-semantic-cache-epoch.md`](issues/09-l2-semantic-cache-epoch.md) | done | L2 语义缓存与纪元失效 |
| 10 | [`10-tool-contract-http-boundary.md`](issues/10-tool-contract-http-boundary.md) | done | 工具契约与跨进程边界 |
| 11 | [`11-action-loop.md`](issues/11-action-loop.md) | done | 查订单与查物流闭环 |
| 12 | [`12-write-idempotency-state-guard.md`](issues/12-write-idempotency-state-guard.md) | done | 写操作幂等与状态校验 |
| 13 | [`13-rate-limiting.md`](issues/13-rate-limiting.md) | done | 双层限流与通道语义 |
| 14 | [`14-fallback-tickets-local-mode.md`](issues/14-fallback-tickets-local-mode.md) | done | 降级原因、工单与 local 模式 |
| 15 | [`15-debug-console.md`](issues/15-debug-console.md) | done | 单文件调试台 |
| 16 | [`16-tool-calling-eval.md`](issues/16-tool-calling-eval.md) | done | Tool Calling 标注评测 |
| 17 | [`17-threshold-calibration.md`](issues/17-threshold-calibration.md) | done | 语义缓存阈值标定 |
| 18 | [`18-load-test-dual-curves.md`](issues/18-load-test-dual-curves.md) | done | 双曲线、虚拟线程与节约率 |
| 19 | [`19-materials-star.md`](issues/19-materials-star.md) | done | README、演示与 STAR 材料 |
| 20 | [`20-action-order-attribution.md`](issues/20-action-order-attribution.md) | done | ACTION_ORDER 归因与量具修复 |
| 21 | [`21-bind-address-gated-credential-fail-fast.md`](issues/21-bind-address-gated-credential-fail-fast.md) | done | 密钥与运维端点绑定地址守护 |
| 22 | [`22-conversation-owned-by-shop-and-customer.md`](issues/22-conversation-owned-by-shop-and-customer.md) | done | 会话按店铺 + 买家归属 |
| 23 | [`23-dependency-health-outside-readiness.md`](issues/23-dependency-health-outside-readiness.md) | done | 依赖健康与就绪门分离 |
| 24 | [`24-mdc-trace-correlation-across-async.md`](issues/24-mdc-trace-correlation-across-async.md) | done | MDC 跨异步边界关联 |
| 25 | [`25-rest-error-envelope.md`](issues/25-rest-error-envelope.md) | done | REST 统一错误出口 |
| 26 | [`26-debug-console-remaining-defects.md`](issues/26-debug-console-remaining-defects.md) | done | 调试台剩余交互缺陷 |
| 27 | [`27-writeback-pool-shutdown-seam-and-saturation.md`](issues/27-writeback-pool-shutdown-seam-and-saturation.md) | implemented | 写回池停机与饱和计数 |
| 28 | [`28-config-format-validation-and-secret-non-echo.md`](issues/28-config-format-validation-and-secret-non-echo.md) | implemented | 配置格式/范围校验 |
| 29 | [`29-state-gauges-four-groups.md`](issues/29-state-gauges-four-groups.md) | implemented | 四组运行时状态指标 |
| 30 | [`30-bookkeeping-closure-readme-scopes-and-registries.md`](issues/30-bookkeeping-closure-readme-scopes-and-registries.md) | implemented | README 作用域与欠账登记 |
| 31 | [`31-crash-attribution-and-heap-caps.md`](issues/31-crash-attribution-and-heap-caps.md) | implemented | JVM 崩溃归因与堆上限 |
| 32 | [`32-ci-subset-gate.md`](issues/32-ci-subset-gate.md) | implemented | 干净 runner 的 CI 子集门禁 |
| 33 | [`33-gateway-main-path-jvm-tests.md`](issues/33-gateway-main-path-jvm-tests.md) | implemented | 网关主链路 JVM 集成缝 |
| 41 | [`41-tool-loop-semantics-pinned.md`](issues/41-tool-loop-semantics-pinned.md) | implemented | 工具循环语义钉死（round17 前置票） |
| 34 | [`34-eval-subset-ci.md`](issues/34-eval-subset-ci.md) | implemented | 评测子集进 CI（round17 前置票） |
| 35 | [`35-prompt-versioning.md`](issues/35-prompt-versioning.md) | implemented | Prompt 版本化（ADR 0037） |
| 36 | [`36-sentiment-gate.md`](issues/36-sentiment-gate.md) | implemented | SentimentGate 情绪门（ADR 0034） |
| 37 | [`37-feedback-loop.md`](issues/37-feedback-loop.md) | implemented | 满意度反馈闭环（ADR 0039） |
| 38 | [`38-channel-adapter.md`](issues/38-channel-adapter.md) | implemented | 三渠道契约接入（ADR 0035） |
| 39 | [`39-plan-ordered-steps.md`](issues/39-plan-ordered-steps.md) | implemented | Plan 有序步骤（ADR 0036；硬闸门 95.0% → 95.0%） |
| — | [`style-engine.md`](issues/style-engine.md) | implemented | 风格引擎（ADR 0038；无编号票，票号 39 归 Plan） |
| — | [`readme-non-goals.md`](issues/readme-non-goals.md) | implemented | 有意不做清单成文（ADR 0040；无编号票） |
| 42 | [`42-flyway-baseline-and-validate.md`](issues/42-flyway-baseline-and-validate.md) | implemented | Flyway V1 基线 + `ddl-auto: validate` 全档（ADR 0041） |
| 43 | [`43-slow-query-index-and-explain-evidence.md`](issues/43-slow-query-index-and-explain-evidence.md) | implemented | 慢查询优化：V2 索引迁移 + EXPLAIN 对照（ADR 0041；**落地一个索引、否决一个**） |
| 44 | [`44-jacoco-coverage-ratchet.md`](issues/44-jacoco-coverage-ratchet.md) | implemented | JaCoCo 覆盖率棘轮（ADR 0041；LINE 设闸 / BRANCH 只报，按模块） |
| 45 | [`45-explicit-escalation-precedes-sentiment.md`](issues/45-explicit-escalation-precedes-sentiment.md) | implemented | 显式转人工优先于情绪判定（ADR 0042；**回归修复**，冻结线允许，不适用 ADR 0031 触发） |
| 46 | [`46-sentiment-second-layer-dev-only.md`](issues/46-sentiment-second-layer-dev-only.md) | implemented | 情绪门第二层只在 dev 口径启用（ADR 0043；**回归修复**——round17 起的 `hitzero` 红；机制活体实测 0 次分类调用，整脚本受阻于本机 Ollama 单模型驻留，按未达成登记） |
| 47 | [`47-embedding-latency-timer.md`](issues/47-embedding-latency-timer.md) | implemented | embedding 段服务端计时器（ADR 0044；补分段耗时唯一盲区，带 `result` 标签，与三个计数器同分法） |
| 48 | [`48-plan-as-first-class-record.md`](issues/48-plan-as-first-class-record.md) | implemented | Plan 提升为一等记录并随 `done` 帧输出（ADR 0044；ADR 0036 执行语义不动） |
| 49 | [`49-done-frame-context-composition.md`](issues/49-done-frame-context-composition.md) | implemented | `done` 帧携带上下文组成（ADR 0044；纯观测，Prompt 逐字节不变） |
| 50 | [`50-explicit-output-token-cap.md`](issues/50-explicit-output-token-cap.md) | implemented | 显式输出上限 `max_tokens` / `num_predict`（ADR 0044；防输出失控，非成本优化；0 = 不限制） |
| 51 | [`51-context-terms-and-style-boundary.md`](issues/51-context-terms-and-style-boundary.md) | implemented | `CONTEXT.md` 补 5 术语 + 风格档位 intent 维度已知边界（ADR 0044；不改代码） |
| 52 | [`52-round19-registration-closeout.md`](issues/52-round19-registration-closeout.md) | implemented | round19 登记文档收口与证据同步（ADR 0044） |

## 如何新增或领取工作

1. 先读 `docs/adr/0030-round14-closure-scope-and-reopen-triggers.md`，确认这不是一个已有明确“当前不做”决定的事项。
2. 新工作先有 round spec，再拆成单个 tracer-bullet ticket；不要直接往旧 ticket 后面续写。
3. ticket 至少写清 `What to build`、`Blocked by`、`Status`、机器可跑的 `Verify` 和验收项。
4. 一次只领取一个 frontier ticket，收尾补 `## Handoff notes`，再提交。
5. 过期待办不要用删历史解决；在校正说明中写清原状态、校正依据和日期。

全仓接手入口见 [`AGENTS.md`](../../AGENTS.md)，代码地图见 [`docs/CODE_MAP.md`](../../docs/CODE_MAP.md)，证据地图见 [`docs/EVIDENCE.md`](../../docs/EVIDENCE.md)。
