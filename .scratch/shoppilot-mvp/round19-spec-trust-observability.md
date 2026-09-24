# round19 规格：补齐可信性观测（embedding 计时器 / Plan 记录 / 上下文组成 / 输出上限）

> 依据：ADR 0044（重开依据与范围）｜基线：HEAD `06331a4`（v1.0.0 `7f4334c`，round18 已收口，票 45/46 回归修复已落）
> 触发物：`D:\WorkBuddyData\RAG与Agent管线_指标闭环可补齐项清单_2026-09-24.html`、`七项目定位档案_2026-09-24.html`（**参考不属裁决**，本仓纪律见 AGENTS.md）
> 复核来源：2026-09-24 围绕六个维度的对仓复核（结论摘要见下节）

## 复核结论摘要（本轮范围的事实来源）

六个维度逐条对仓核对后的结论，**只有第一堆进本轮范围**：

| 维度 | 结论 | 本轮处置 |
|---|---|---|
| ①需求与架构匹配 | 高，偏差全部有 ADR 与归因；四项判据未达成、红不摘 | 不动（登记节第 6 项） |
| ②任务规划 | 部分具备：有前序依赖表达式与失败中止，但无显式 Plan 对象 | **票 48** |
| ③上下文工程 | 部分具备：区块化构建 + 版本化 Prompt 齐全；无摘要、无单请求预算、无输出上限 | **票 49、50**（预算裁剪登记） |
| ④可观测性与评估 | 评估强（判据唯一 + 量具自证 40/40 + CI 五步）；可观测缺 embedding 计时器 | **票 47** |
| ⑤人机协同 | 部分具备：三出口齐全、工单可查、反馈可采；**工单不回流、无审批** | 不动（登记节第 1、2、3 项） |
| ⑥业务闭环 | 部分具备：读闭环完整；**退款停在受理态、知识不反向沉淀** | 不动（登记节第 1、8 项） |

## 基线复核（2026-09-24，对仓现场核对）

外部清单里**五处说法与仓内实测不符**，本轮按实测口径登记，不改任何判据：

| 清单口径 | 实测 | 依据 |
|---|---|---|
| ShopPilot 有 30 份 ADR | **42 份**（`0001`–`0043`，无 `0022`）——核对时点的读数；round19 自己加了 ADR 0044，收口后为 **43 份** | `ls docs/adr/*.md \| wc -l` |
| 170 个 `@Test` + 10 参数化 | **226 个 `@Test` + 10 个 `@ParameterizedTest`**（声明数）；本地执行 `3 + 21 + 253 = 277` | 全仓 `src` grep 现算 |
| ShopPilot 的 Qdrant 距离度量**未声明**（`grep Cosine\|Distance\.` → 0 命中） | **不成立**：`knowledge/QdrantRestClient.java:52` 建集合时硬编码 `vectors.put("distance", "Cosine")`。口径落在客户端而非 `application.yml`，但已锁定 | 见上，清单的 grep 结果取自旧副本或错误路径 |
| 416 条场景分片 | **双算**：gold 的 180 条本身就是 part1-3 合并（`scripts/build_eval_set.py:18-24`），180 + 236 = 416 把 180 算了两遍。唯一用例数 **236**（180 gold + part4-7 的 56） | 逐文件非空行计数 |
| 「降级原因 README 写 9、旧简历写 8，仓内自身不一致」 | **已过期**：`README.md:271` 已定换算规则——枚举 **10** 为全集、「降级」= 枚举 − 主动转人工 = **9**、`verify-fallback.ps1` 确定性表 **7 行**、限流是第 8 步。三处数字是三个不同集合，不是互相矛盾 | `README.md:271` |

清单**核实为真**的部分（本轮不改）：`docs/retrieval-comparison.md` 的 dense-only hit@5 16/16 对 hybrid 16/16、「更好查询 0 条」、以及「刻意不做自定义分词器 / 同义词词典 / 精排」逐字属实；`cache/PolarityGuard.java` 确实存在（清单建议把它迁移到 OpsPilot，属跨项目范式沉淀，不是本仓缺口）。

## 票据拆分

| 票 | 标题 | ADR | 依赖 | 估时 |
|---|---|---|---|---|
| 47 | embedding 段服务端计时器（补分段耗时唯一盲区） | 0044 | 无 | 0.5 天 |
| 48 | Plan 提升为一等记录 + 落 `done` 帧 | 0044 | 无 | 0.5 天 |
| 49 | `done` 帧携带上下文组成 | 0044 | 48（同一帧，同批改） | 0.5 天 |
| 50 | 显式输出上限 `max_tokens` / `num_predict` | 0044 | 无 | 0.5 天 |
| 51 | `CONTEXT.md` 术语补齐 + 风格档位已知边界 | 0044 | 无 | 0.5 天 |
| 52 | 登记文档收口 + EVIDENCE/tracker 同步 | 0044 | 全部 | 0.5 天 |

建议顺序：48 → 49（同一帧）；47、50、51 三者文件面不重叠，可并行；52 收口时做。

## 验收判据（每票一组，机器可复跑）

### 票 47 — embedding 段服务端计时器

1. `knowledge/EmbeddingClient.java` 新增 `Timer shoppilot_embedding_latency_seconds`，**带 `result` 标签**（取值与既有 `shoppilot_embedding_calls_total` 一致：`remote` / `in-process-cache` / `singleflight-merge`）。不带标签等于把两条路径混成一个数，比现在的探针估算更糊。
2. 新增 JVM 用例断言：命中进程内缓存时不打远程（既有 seam 已能区分），且两类调用的计时器都真的被记录（`registry.find(...).tags("result", ...).timer()` 非空）。
3. `scripts/ttft_attribution.py` 的 `probe_vectorize()` 外部探针路径改为读新指标；**保留探针作为回退**（网关未起时仍能出数），并在脚本注释里写明两者的口径差。
4. **变异对照**：把 Timer 的 `result` 标签去掉（或写成固定值）→ 第 2 条用例必须变红。
5. `README.md:211` 与 `docs/loadtest-report.md` 的相关行加换代指针；`docs/EVIDENCE.md` 的「TTFT 725 ms 归因」行登记新测量方法与新读数。**旧值按原样并列保留，不摘红、不换口径**——这是测量保真度提升，不是达标。
6. 若新读数与旧的 311 ms 探针估值差异显著，**差异本身就是结论**（说明探针低估/高估），写进 Handoff，不为了「和旧值一致」调数字。

### 票 48 — Plan 提升为一等记录

1. `AgentStateMachine` 里每步执行记录升级为 `{工具名, 参数, 状态, 耗时}`（现为 `List<String> stepResults` 只留结果 JSON）。
2. `agent/AgentResult` 新增嵌套 `record PlanStep` 与 `List<PlanStep> plan`；`web/SseEventSink.done(...)` 加 `plan` 字段；唯一调用点 `ChatController.java:138`。
3. **ADR 0036 执行语义一字不动**：仍是有序步骤 ≤ 2、前步失败即中止、串行不并行、不重排。`PlanExecutionTest` 全部原样通过。
4. 新增 JVM 用例：两步链的 `done` 帧含 2 条 `PlanStep`（工具名/状态/耗时齐备）；前步失败中止时含 1 条且 `planAborted` 可见；**不调工具的纯政策回答 `plan` 为空数组**（不是 null，避免客户端判空分支）。
5. `verify-console.mjs` 新增一条断言：真实 SSE 流的 `done` 帧含非空 `plan`（走 action 意图的演示步）。
6. **不新增 SSE 事件类型**（`verify-console.mjs` 的帧序列断言与 `SseEventSinkTest` 的字段断言都不得因此改写语义，只许加断言）。

### 票 49 — `done` 帧携带上下文组成

1. `AgentResult` 新增 `ContextComposition`：条款 `ruleId` 列表、历史轮数、估算 prompt token。
2. `done` 帧加 `context` 字段。**纯观测，不改注入内容**——`composeUserMessage` 产出的 Prompt 文本必须逐字节不变（加断言钉住）。
3. 新增 JVM 用例：命中政策检索时 `context.ruleIds` 与 `citations` 一致；零召回时 `ruleIds` 为空数组且 `composeUserMessage` 仍输出「（本轮未检索到相关条款）」。
4. 历史轮数断言：6 轮窗口满时报告值 = 6（与 `historyTurns` 配置同源，不另算一份）。
5. **变异对照**：把 `context.ruleIds` 改成写死空数组 → 第 3 条用例变红。

### 票 50 — 显式输出上限

1. `GatewayProperties.Llm` 新增 `max-output-tokens`（带 `@Min` 校验，与既有 `temperature` 的校验风格同形）；`application.yml` 给默认值，**给足余量**（目的为防输出失控，不是省 token）。
2. `OpenAiCompatibleLlmClient.payload()` 加 `max_tokens`；`OllamaLlmClient.payload()` 的 `options` 加 `num_predict`。两者都必须**只在配置值 > 0 时**下发（0 = 不限制，保持向后兼容）。
3. 新增/同步 JVM 用例：两个客户端发出的请求体都含该字段且值等于配置；`max-output-tokens: 0` 时字段不下发。
4. `ConfigValidationTest` 补一条越界校验（负数即启动失败）。
5. **收口时确认 180 条 gold 读数未漂移**（默认值给足余量应当不漂移）；若漂移，按「保留红值 + 归因」处理，不改判据、不改 gold。

### 票 51 — 术语补齐 + 已知边界

1. `CONTEXT.md` 新增 5 个 canonical term，按现有格式（粗体 term + 定义行 + 可选 `_Avoid_`，**语义邻接插入**而非追加末尾）：
   - **槽位 (Slot)**：进 `## Language`，邻接「待办动作」
   - **降级 (Fallback)**：进 `## Language`，须写明「降级 9 = 枚举 10 − 主动转人工」的换算规则并指向 `README.md:271`
   - **缓存写回资格 (Write-back Eligibility)**：进 `## Language`，邻接「缓存准入」并写明二者是不同的门
   - **复核队列 (Review Queue)**：进 `## 知识侧`，邻接「引用」
   - **计划 (Plan)**：进 `## Language`，写明「有序步骤 ≤ 2、与 2 轮硬上限是同一条边界的两种表述」（引 ADR 0036）
2. 风格档位已知边界：把「档位在 INTAKE 阶段计算、此时意图未定，故 `profiles.yml` 的 intent 维度当前不可能命中」写成已知边界（`README.md` 已知限制段或 ADR 0038 注）。**不改代码**——`Rule.matches(channel, emotion, intent)` 里 intent 是活的匹配维度、`StyleServiceTest` 有 7 处传真 Intent 作断言、`profiles.yml` 只是没写 intent 规则，三者自洽。
3. `git diff --check` + `git status --short` 干净；术语定义与代码抽查一致（每条至少核对一处源码落点）。

### 票 52 — 登记文档收口

1. 本 spec 的「登记不执行」节补齐 8 项与触发条件（见下节）。
2. `docs/EVIDENCE.md` 新增/换代：embedding 计时器与 TTFT 归因新读数、`done` 帧新字段的证据落点。
3. tracker `README.md`：round 表加 round19 行、票索引加 47-52、`当前状态` 加收口条目。
4. `docs/CODE_MAP.md`：`llm` 行补输出上限、`agent` 行补 Plan 记录、Test Map 补对应用例行。
5. 收口审计 `ROUND_FP` 按 ADR 0023 重锚到 `06331a4`（round19 起点）；`G6_EXPECT` 按实测用例数换代。

## 冻结与收口

- 本轮结束后回到 ADR 0031 冻结机制，**不自动续期**。
- 全部票据独立可交付，中途冻结任意时刻项目自洽。
- **覆盖率硬约束**：gateway LINE 门槛 `54.0`，round18 实测 `55.37%`，余量 **1.37pp**。新增 gateway 代码一律配 JUnit 用例，否则 `python scripts/check_coverage.py` 直接红。这是本轮最容易踩的坑。
- 活体只跑 `verify-console.mjs` 一条针对性步（按 ADR 0044 的 Consequences），**不跑全量 22 步矩阵**。

## 禁面核对（ADR 0023）

本轮改动面与内容级禁面**零交集**，已逐条核对：

| 本轮要改 | 是否禁面 | 依据 |
|---|---|---|
| `shoppilot-gateway/src/main/java/com/shoppilot/gateway/knowledge/EmbeddingClient.java` | **否** | 禁面项是仓库**顶层** `knowledge/`（政策语料 30 个 md），路径前缀判定见审计脚本 `:351` 的 `f.startswith("knowledge/")`。Java 包路径 `shoppilot-gateway/src/main/java/com/shoppilot/gateway/knowledge/` 不以 `knowledge/` 开头，不在禁面内 |
| `agent/AgentResult.java`、`agent/AgentStateMachine.java`、`web/SseEventSink.java`、`web/ChatController.java` | 否 | 禁面只含 gold 三文件、`eval/results/` 的改删、顶层 `knowledge/`、`scripts/verify_eval_judge.py`、本轮起点已在库的旧 ADR |
| `llm/*.java`、`config/GatewayProperties.java`、`application.yml` | 否 | 同上。**注意**：`application.yml` 里的阈值不在路径禁面内（ADR 0023 照登的已知缺口）；本票只**新增** `max-output-tokens` 键，不动任何既有阈值常量 |
| `scripts/ttft_attribution.py`、`scripts/verify-console.mjs` | 否 | 同上；禁面只钉 `scripts/verify_eval_judge.py` 一支 |
| `docs/adr/0044-*.md`（新增） | 否 | 新写的 ADR 不在 `prior_adrs` 集合内（该集合取自 `ROUND_FP:docs/adr`） |
| `CONTEXT.md`、`docs/EVIDENCE.md`、`docs/CODE_MAP.md`、`README.md` | 否 | 同上 |

**本轮不改**：`eval/tool-cases.jsonl`（改了会连带 `.scratch` C7 硬编码的 `a8c88525fa7c` 与多份文档）、`eval/cases-part*.jsonl`、`scripts/run_tool_eval.py` 的 `judge()`、`scripts/verify_eval_judge.py`、顶层 `knowledge/`。

## 登记不执行

八项，各挂触发条件。**它们不是被丢掉，是触发前不做**（ADR 0030 第 22 行的口径）：

1. **退款终态推进**：`REFUNDED` 只出现在 `OrderStatus` 的枚举声明与注释里，无任何运行时代码推进（实测 grep 零命中）。系统能确认「退款申请已受理」（落 `PROCESSING` / 订单 `REFUNDING`），不能确认「已到账」。触发条件 = 出现需要区分「受理」与「到账」的真实诉求，或接入真实支付通道。
2. **工单 RESOLVED 回流消费点**：网关侧对工单状态**零消费者**（`grep RESOLVED|ASSIGNED` 于 `shoppilot-gateway/src` 零命中）。触发条件 = ADR 0030 第 2 条：biz-mock 被真工单系统替换。**在那之前「进程生命周期内可查」是本仓官方口径**（`CONTEXT.md:130`），且「工单回流」一词被 `CONTEXT.md:132` 列为 `_Avoid_`——本轮不改这条术语结论。
3. **敏感动作审批闸门**：全仓 `approval|审批|feishu|HITL` 零命中；写动作直接执行（`ToolDispatcher.java:74-79`），唯一的 `Guard` 是幂等两态。转人工是**事后登记**，不是**事前审批**。触发条件 = ADR 0008 的 2 轮时延预算被重新论证（审批会引入人工等待轮次，直接冲击该论证）+ 资金动作放行成为产品要求。可迁移来源：moa-gateway 的 Guard 三态 + HITL 审批。
4. **task-level 判据（任务是否办成）**：现有分项是意图/工具/参数/槽位四列，缺「这件事最终办成了吗」。触发条件 = ADR 0030 第 3 条邻域：有人提出一条能机器判定「任务是否办成」且现有门禁承载得了的判据。**新增独立评测模块（`eval_suites.py` 形态）比改现有 gold 安全**——后者会撞 `verify_eval_judge.py:218/221` 的精确断言与 CI rescore 差异集合。
5. **CI 覆盖活体数字**：CI 五步只覆盖「判据不漂移」，不覆盖任何活体准确率/验收/压测数字（`docs/EVIDENCE.md:41` 逐字）。触发条件 = 出现可离线复跑的录制/回放路径。
6. **拦截率 74% vs 80% 裁决（本轮明确维持挂起）**：三个选项已列，本轮一个都不选——「改口径」踩 AGENTS.md 的「不改指标分母来让结果变绿」铁律；「改架构（缓存前置）」推翻 ADR 0003 并重新引入跨意图串号（本仓最不可协商的一条是串号 0 次）；「正式作废判据」客观上让一条红消失，读者可能归为改判据刷绿。触发条件 = ADR 0030 第 4 条：冲突被显式裁决（改定义或改架构，写成新 ADR）。
7. **上下文输入侧裁剪（单请求 token 预算）**：现状有硬截断（历史滑窗 + 单条条款 600 字上限），无摘要、无单请求预算。触发条件 = 出现超预算的真实请求或长会话投诉。**本轮只做输出上限（票 50）不做输入裁剪**——输入裁剪会改 Prompt 内容，进而可能改 180 条 gold 读数，代价与本轮「旁挂」定义冲突。
8. **知识反向沉淀自动咬合**：入库→检索→缓存写回→纪元失效这条链完整；反向缺失——问答结论只进答案缓存不进知识库，`kbEpoch.bump()` 只有两个调用点（离线入库 `IngestRunner.java:127` + 人工运维 `OpsController.java:299`）。ADR 0039 写的「（触发条件下）知识库修订 → kb_epoch 递增」中，`markReviewed` 是队列终点，无任何代码连线。触发条件 = ADR 0039 第 19 行：显式评分覆盖率达到可观测水平。

9. **工具 schema 描述不对称（2026-09-24 全量矩阵新暴露）**：`QueryOrderDetailRequest` 的 orderNo 描述带「用户未提供时必须追问而非猜测」，而 `QueryLogisticsRequest` / `ApplyRefundRequest` / `ModifyDeliveryAddressRequest` 只写「平台订单号，例如 10023」。本地 3B 模型照抄了那个示例值 → 编造出 `10023` 并真的派发（`action` 步的三条 slot_ask 断言因此红）。**这是先前就存在的问题，不是 round19 引入**（回退到 `06331a4` 重建后同样红）。触发条件 = 下一轮要动工具契约面时（改 schema description 属功能改动，不在本轮旁挂定义内）。可选的更强做法是让 `isFabricatedOrderNo` 之外再加一层「模型自报的参数是否出自对话」的判据，那需要新决策。

## 收口状态（2026-09-24）

六张票全部实现并各自留 Handoff。当前 JVM verify `3 + 21 + 269 = 293` 绿（round18 收口时为 `3 + 21 + 249 = 273`，票 45/46 到 277，本轮到 293）。

| 票 | 状态 | 关键落点 |
|---|---|---|
| 47 embedding 计时器 | implemented | `shoppilot_embedding_latency_seconds{result=remote\|in-process-cache\|singleflight-merge}`（与三个计数器同分法）+ `EmbeddingLatencyTimerTest` 5 条 + `ttft_attribution.py` 优先读指标 |
| 48 Plan 一等记录 | implemented | `AgentResult.PlanStep` + `done` 帧与同步响应的 `plan` 字段 + `GatewayMainPathJvmTest` 2 条 |
| 49 上下文组成 | implemented | `AgentResult.ContextComposition` + `done`/同步响应的 `context` 字段 + `contextCompositionMirrorsCitationsAndHistory` 与 `zeroRecallKeepsEmptyRuleIdsAndThePlaceholder` |
| 50 输出上限 | implemented | `GatewayProperties.Llm.maxOutputTokens` + `max_tokens` / `options.num_predict` + `OpenAiCompatibleLlmClientTest` 2 条 + `OllamaLlmClientTest` 3 条（新建）+ `ConfigValidationTest` 越界校验 |
| 51 术语与已知边界 | implemented | `CONTEXT.md` 38 → 43 个 canonical term；`README.md` 已知限制段加风格档位 intent 维度边界 |
| 52 登记收口 | implemented | 本 spec 的登记节（8 项）+ `docs/EVIDENCE.md` 两行 + tracker + `docs/CODE_MAP.md` + 收口审计 `ROUND_FP`/`G6_EXPECT` 换代 |

**验收判据实测落点**

- 票 47：三桶计数与三个计数器**逐桶相等**（用例断言这条不变式）；**变异对照**把缓存命中桶标签改成 `remote` → 5 条里 3 条红。第一版踩的坑：断言「缓存桶 Timer 不存在」是错的——三个 Timer 在构造期就注册好了，桶是**存在但零样本**，判据改成「计数为零」。
- 票 48/49：**变异对照**去掉 `planSteps.add(...)` 且把 `ruleIds` 写死空数组 → **恰好**两条红（`planStepsAreReportedInExecutionOrder`、`contextCompositionMirrorsCitationsAndHistory`）；`PlanExecutionTest` 原样通过，是「ADR 0036 执行语义未动」的机器证据；`zeroRecallKeepsEmptyRuleIdsAndThePlaceholder` 断言 Prompt 的零召回占位文本逐字保留，是「纯观测」的机器证据。
- 票 50：`ConfigValidationTest.applicationPlaceholderSetIsPinned` 当场拦住了新增的 `SHOPPILOT_LLM_MAX_OUTPUT_TOKENS` —— 处置是显式登记进清单（加 env 覆盖是扩大可配置面，家法同 ADR 0012 的日预算），不是绕过门禁。
- 票 51：术语数 38 → 43；每条都核对了源码落点（写回资格七道门、`GATEWAY_CONFIDENT_SLOTS`、`PlanExpression`、`FallbackReason` 10 枚举与 README 的换算规则、`markReviewed` 单向流转）。

**CI 五步门禁（本机读数，2026-09-24）**

| 步 | 命令 | 读数 |
|---|---|---|
| 1 构建与 JVM 测试 | `.\mvnw.cmd -B -ntp verify` | `3 + 21 + 269 = 293` 绿 |
| 2 判据自检 | `python scripts/verify_eval_judge.py` | `合计 40/40 通过` |
| 3 离线重算 | `python scripts/run_tool_eval.py --rescore …` | `RESCORE DONE cases=180 files=6 tool_diff=4` |
| 4 套件夹具 | `python scripts/eval_suites.py` | `SUITE SELFCHECK ok=24` |
| 5 覆盖率棘轮 | `python scripts/check_coverage.py` | `gateway 57.95% / biz-mock 77.49% / tool-api 41.73%` 对门槛 `54.0 / 76.0 / 40.0`，`COVERAGE OK` |

第 3 步在本机落下的 `eval/results/tool-eval-20260924-050114-rescore.csv` 是本机验证副产物（差异集合与基线一致，不携带新信息），已删除、未入库（与 round18 同例）。

**对本 spec 验收判据的一处修正（记录实测结果，不是放宽判据）**

- 票 48 第 5 条原写「`verify-console.mjs` 新增一条断言：真实 SSE 流的 `done` 帧含**非空** `plan`（走 action 意图的演示步）」。实现时改成钉**确定的东西**：`plan` 是数组（空或非空都合法——取决于模型这一轮要不要工具）、`context.ruleIds` 非空且与 `citations` 同源同序（政策问句必然检索到条款，与模型行为无关）。理由：让活体门禁依赖「3B 模型这一轮会不会发 tool call」就是引入抖动，而「plan 非空」那一半已经由 JVM 用例在确定性桩下钉住了。**判据的强度没有降低，只是把不确定性那一半挪到了能确定的那一层。**

**活体针对性与全量矩阵（2026-09-24 补齐，round19 收口后同日）**

票 48 第 5 条那条「未真跑」的活体断言，以及此前一直挂在账上的全量 22 步矩阵，当天补跑完毕：

- **`verify-console.mjs` 36/36 全过**，含新增那条：`done frame carries plan array and context composition (tickets 48/49) — plan=0 ruleIds=5 citations=5`。**票 48/49 的活体针对性步由此关闭**。
- 另有两条独立探针（直接读原始 SSE 流，不依赖页面渲染）作为交叉证据：政策问句的 `done.context.ruleIds` 与 `citations` **逐项同序一致（各 5 条）**、`historyTurns=0`（新会话）、`estimatedPromptTokens=1946`、`plan=[]`；动作问句的 `plan` 有 1 条 `{tool: queryLogistics, status: NOT_FOUND, latencyMillis: 567, arguments: {orderNo: 90001}}`，四个字段齐备（`NOT_FOUND` 是归属双条件的正确行为）。
- **全量 22 步矩阵：512 s、18 步绿 / 4 步红**（落点 `logs/acceptance-run-20260924-180518.log`）。相比 2026-09-20 的 16 绿 / 6 红，`plan`、`hitzero`、`fallback` **三步转绿**（分别由 round17 后的脚本修复、票 46 / ADR 0043、票 45 / ADR 0042 交账）；**`verify-hit-zero-llm.ps1` 10/10、exit 0**，即票 46 那条挂红关闭。
- **`action` 是本次新出现的红，已定性为先前就存在的问题、不是 round19 引入**：把工作树回退到 round19 起点 `06331a4` 重建后同一步**同样红**（对照实验，7 s、同样的三条 slot_ask 断言失败）。机制：本地 3B 模型把工具 schema 描述里的示例订单号（`@ToolParam(description = "平台订单号，例如 10023")`，自 `f58e439` 起在仓）直接填进 `orderNo`，而 `ToolDispatcher.isFabricatedOrderNo` 只校验格式（`\d{1,12}`）——`10023` 格式合法因而拦不住。**新登记项**：`QueryLogisticsRequest` 的描述缺了 `QueryOrderDetailRequest` 那句「用户未提供时必须追问而非猜测」，这处描述不对称是可改的，但属功能改动、不在 round19 的旁挂定义内，**本轮只登记不执行**。
- 四步红里的 `emotion` / `feedback` / `plansteps` 是 2026-09-20 那批的延续，不在本轮范围。

**一处归因更正（重要）**：此前把活体阻塞记为 `OLLAMA_MAX_LOADED_MODELS=1`（票 46 Handoff 与 EVIDENCE 都这么写）。当天的实测报的是 **`cudaMalloc failed: out of memory`** 与 **`failed to allocate CUDA_Host buffer`**——本机同时跑着四套项目共 17 个容器，显存与主机内存瞬时争抢，而当时 `/api/ps` 显示**零模型驻留**，所以根本不是「两个模型不能同时驻留」。逐个预热四个模型后 `qwen2.5:3b` 与 `bge-m3` 可同时驻留，**且该环境变量全程未改（仍为 1）**。结论：该阻塞是资源争抢引起的瞬时状态，不是配置问题；下次遇到同类红应先断开源（显存/主机内存占用），而不是先改那个变量。旧读数按原样保留，本条只是加归因更正。

**票 50 的「180 条 gold 读数未漂移」：2026-09-25 已验（三条腿）**

1. **解析证明该上限不可能生效**：仓库里全部 dev 明细共 **1176 条**的 `completion_tokens` 是 p50=63 / p95=222 / **max=440**，超过 1024 的 **0 条**；当天 cap 开那轮 24 条 max=362。够不到上限就不影响输出。
2. **正向对照证明旋钮活着**：把上限压到 20 后重启，答案被**截在 20 token、断在半路**（`…不是“二`）。旋钮生效、字段下发到 DashScope、且对方认它。
3. **实测对照**：dev 档 24 条全过（10 意图 100%、0 错误），与 **2026-09-10 基线逐条比 23/24 一致**，唯一差异 `ACT-ADR-03` 是 `False → True`（**变好**）。

**未做**：180 条全量重跑。机制问题已由第 1 条结清；且「今天跑 vs 09-10 基线」的比较**会被后几轮改动混淆**（round17 给 system prompt 注入了风格段、dev 档多了情绪分类调用），差异不能归因到票 50。要实测其贡献只能做同代码 A/B（cap 1024 vs 0，各 180 条，约 38 万 token）。

**本轮仍未验证**

- 上述四步红中的三步（`action` 之外的 `emotion` / `feedback` / `plansteps`）仍红。
- 本机环境的三条硬限制（当天实测）：**TIME_WAIT 13153 / 动态端口 16384** → 反复出站 HTTPS 会撞 `WinError 10048`；主机内存 **15.8 GB 只剩 2.0 GB** → 网关启动期原生 OOM（`hs_err_pid29612.log`）；四套项目 17 个容器争抢 4 GB 显存。

**两条会再咬人的操作坑（当天踩到）**

1. **环境变量只能走 `.env`**：launcher 由 `lib-launch.ps1` 的 WMI `Win32_Process.Create` 创建，**不继承调用方环境**。shell 里 `export SHOPPILOT_*` 完全无效——第一次 A/B 因此变成「cap 开 vs cap 开」。
2. **换 profile 必须先 `stop.ps1`**：否则新网关以 `Port 8082 already in use` 启动失败，而 readiness 仍由**旧进程**返回 200 → 假绿。判据用 `/ops/switches` 的 `llmMode`。
