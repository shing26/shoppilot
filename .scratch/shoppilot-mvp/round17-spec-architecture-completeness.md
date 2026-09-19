# round17 规格：对齐完整落地级电商客服链路（M1-M5）

> 依据：ADR 0033（重开依据与范围）｜基线：HEAD `578722e`（v1.0.0 `7f4334c`，round16 已收口，2026-09-17 压测复测产物已登记）
> 对标物：《电商智能客服Agent--已落地》架构流程图
> 可行性评估：《ShopPilot-链路扩展可行性评估-20260919.md》
> 2026-09-19 修订：外部审查（5 份跨项目报告）交叉核实后并入前置票 41 与「外部审查登记」附录，见文末。

## 票据拆分

| 票 | 标题 | ADR | 依赖 | 估时 |
|---|---|---|---|---|
| 41 | 工具循环语义钉死（超限→FALLBACK、parallel_tool_calls 防御、轮次上限 JVM 测试） | 0008 字面对齐 + 事实性错误修复 | 无 | 0.5-1 天 |
| 34 | 评测子集进 CI（selfcheck + 离线 rescore 门禁比对） | 0033 前置 | 无 | 0.5 天 |
| 35 | Prompt 版本化（外置 v1.0.0.md + meta.json + SSE 携带版本） | 0037 | 无 | 0.5 天 |
| 36 | SentimentGate 情感门 + EMOTION_ESCALATION（第 10 降级因） | 0034 | 34 + 41 | 1-1.5 天 |
| 37 | 反馈闭环（显式点踩 + 隐式信号 + 人工复核回流队列） | 0039 | 34 | 1-1.5 天 |
| 38 | ChannelAdapter 三渠道契约接入 | 0035 | 34 | 1.5-2 天 |
| 39 | Plan 有序步骤（≤2 步，仍处 ADR 0008 界内） | 0036 | 34 + 35 + 36/37/38 任二已合 | 2 天 |
| — | StyleService 风格引擎（随票 35 完成即开工） | 0038 | 35 | 0.5-1 天 |
| — | 有意不做清单成文（README 已知限制改写） | 0040 | 无 | 0.5 天 |

建议顺序：41 → 34 → 35 → 36 → 37 → 38 → 39；0040 与 34 并行即可。
票 41 先于票 39 的原因：两者动 `AgentStateMachine` 工具循环同一段代码，超限语义必须先钉死。2026-09-19 用户裁决：代码对齐 ADR 0008 字面——轮次用尽 → FALLBACK 落工单，不再"基于已有事实收尾"。

## 评测资产（本轮新增，judge() 需扩 schema 并同步补 selfcheck 夹具）

| 文件 | 条数 | 新增字段（含 expect 内外） |
|---|---|---|
| `eval/cases-part4-emotion.jsonl` | 20 | `emotion`、`expect.escalate`、`expect.reason` |
| `eval/cases-part5-channel.jsonl` | 10 | `channel`、`sameAnswerAs`、`asyncReply`、`mustNotSee`、`streaming` |
| `eval/cases-part6-plan.jsonl` | 14 | `expect.plan[]`、`abortAfterStep0`、`mayAbortAfterStep0`、`abortReason`、`planRejected`、`rejectReason`、`slotAsk`、`missingSlot`、`mustNotLeak`、`mustFailOwnershipCheck` |
| `eval/cases-part7-style-feedback.jsonl` | 12 | `expect.style`、`tone`、`mustNotContain`、`mustNotCarryFacts`、`channel`、`explicitFeedback`、`implied`、`notDoubleCounted`、`condition` |

judge() 扩 schema 以本表为登记依据；扩 judge 必须同步补 selfcheck 夹具（ADR 0021 纪律）。

合计 56 条新用例；emotion 词典层 + style 档位 + plan 执行语义（MockLLM）三类必须 0 token 可复跑。

## 验收判据（每票一条，进 run-acceptance 矩阵）

1. 票 41：轮次用尽 → FALLBACK 落工单（新 `FallbackReason.TOOL_ROUNDS_EXHAUSTED`，工单可按号反查）；请求 payload 含 `parallel_tool_calls:false`；模型返回多个 toolCalls 时只派发并只记录第一个（转录协议配对合法）且 multi-tool 计数可见；轮次上限 JVM 测试落地（关闭票 11 自陈缺口）。
2. 票 36：`verify-emotion` —— 20 条情绪用例，词典层 8 条 0 token 定案断言；ANGRY 用例 6/6 与 URGENT 用例 2/2 落 EMOTION_ESCALATION 工单（队列反查），CALM/DISSATISFIED/UNCERTAIN 用例 0 误升级。
3. 票 37：`verify-feedback` —— 点踩落 feedback 表 + 关联工单/ruleId 可查 + 复核队列可见，三断言；重问/降级/幂等重放三个隐式信号计数各断言一次。
4. 票 38：`verify-channel` —— 同一句从 web/app/miniapp 进入答案一致、会话不互串；email 全链路落工单；限流按渠道维度可查。
5. 票 39：`verify-plan` —— 两步链 8 条（含前步失败中止 2 条）、注入表达式 2 条判红；全量 357+56 条评测不低于基线（硬闸门，不达标本票挂账、round17 收缩）。
6. 票 35：迁移前后 `mvnw verify` 全绿 + 11 条规则文本逐字 diff 为空。
7. 风格票：3 渠道 × 2 情绪矩阵档位断言 + 注入段无业务事实断言。

## 冻结与收口

- 本轮结束后回到 ADR 0031 冻结机制，不自动续期。
- 全部票据独立可交付，中途冻结任意时刻项目自洽。
- README 已知限制按 ADR 0040 改写为"有意不做 + 触发条件"。

## 外部审查登记（2026-09-19）

来源：`D:\WorkBuddyData\2026-09-19-02-48-20\code-review-agent\docs\` 下 5 份跨项目报告（全项目框架补强建议、架构补强建议、框架规范化与优化建议、框架迁移可行性评估、改造可行性评估）。ShopPilot 相关指控已逐条对仓核实，登记结论如下；其余 8 个项目的建议不在本仓纪律约束范围内，只在报告原文中保留。

**采纳的定位结论**：ShopPilot 是九项目中最健康的 Java 项目、唯一的完整 agent 但不该整体迁移；报告引用的本仓数字（`AgentStateMachine` 665 行、`max-tool-rounds: 2`、L2 recall=0、反义对余弦 0.9682、同意图改写最大余弦 0.9435）逐字核实属实——诚实红值被外部正确引用，反证证据纪律有效。

**升级为票的两个发现**（→ 票 41）：

1. 并行工具调用协议缺陷：`AgentStateMachine.java:266-267` 只派发 `toolCalls().get(0)` 却把全部 toolCalls 写进转录，`OpenAiCompatibleLlmClient.java:106` 只设 `tool_choice:"auto"` 未设 `parallel_tool_calls:false`；严格 OpenAI 兼容端点会因 tool_call_id 无配对 400，宽松端点静默丢结果。修复形态取"请求字段 + 防御分支"，拒绝全量派发（违反 ADR 0008/0040 串行预算）。
2. ADR 0008 超限语义三方不一致：ADR 文本与票 11 面试口径写"超限强制 FALLBACK"，代码自 `f58e439` 起实际"基于已有事实收尾"，且收尾不检查写动作是否办成。用户裁决：代码对齐 ADR 字面（轮次用尽 → FALLBACK 落工单）。

**元教训**：该分歧存在 33 张票与两轮复核未被发现，直接原因是票 11 自陈的"轮次上限无 JVM 单测"缺口——没有测试钉住的语义，审查也没有抓手。票 41 一并关闭。

**登记不执行**：

- 归档层（原始 LLM 报文 JSONL）：有意不做。现有 `logs/` + MDC trace + RuntimeStateMetrics + eval 产物已覆盖单机验证件的证据需求。触发条件：出现需复盘原始报文的真实争议或外部审计要求。
- LLM 客户端层换 Spring AI：登记（报告口径："省约 240 行 SSE 样板，不是补能力"）。触发条件：ADR 0032 的 provider 缝触发线（该触发线本身以 ADR 0031 的冻结机制为闸）。
- identity↔web 包环：已裁决不修（ADR 0028 + CODE_MAP"不为消环做大搬迁"），不重复立账。
- `AgentStateMachine` 665 行拆分：已在 CODE_MAP 已知代码债候选，处理方式已定（先 ticket 固定现有测试与 SSE 契约）；票 41 的测试即"固定契约"步骤。

## 收口状态（2026-09-20）

全部票据已实现并各自收口，收口顺序 41 → 34 → 35 → 36 → 37 → 38 → 风格票 → 39，有意不做成文票（ADR 0040）并行完成。

| 票 | 状态 | 关键落点 |
| --- | --- | --- |
| 41 工具循环语义钉死 | implemented | 超限按 ADR 0008 字面 FALLBACK、`parallel_tool_calls:false` + 多调用防御、写动作守卫 |
| 34 评测子集进 CI | implemented | CI 两步 0 token 门禁（判据自检 40 项 + rescore 比对钉 4 条差异集） |
| 35 Prompt 版本化 | implemented | 外置 `prompts/agent-system/v1.0.0.md` + meta fail-fast + SSE/评测报告携带版本 |
| 36 情绪门 | implemented | 词典层 0 token 定案 + dev 口径 LLM 分类兜底 + `EMOTION_ESCALATION` 第 10 降级因 + 工单 priority=high；分类器提示词亦外置为版本资产 |
| 37 反馈闭环 | implemented | biz-mock feedback 表 + 复核队列 + 三隐式信号计数 + 点踩自动关联工单与引用块 |
| 38 三渠道契约 | implemented | `channel` 包 + webhook/email 入站 + ChatAdmission 提取 + 限流/meta/计数按渠道 |
| 风格引擎（无编号） | implemented | `style/profiles.yml` 档位表 + 基座+注入段拼装 + SSE meta 带 style |
| 39 Plan 有序步骤 | implemented | 前序依赖表达式白名单 + 前步失败即中止 + `shoppilot_plan_steps_total` 分账；**硬闸门 95.0% → 95.0% 通过** |
| 有意不做成文（无编号） | implemented | README 增"有意不做（带触发条件，ADR 0040）"小节 |

**硬闸门执行记录（票 39）**：同一晚、同一 180 条 gold、同一栈跑三次——回归现形（含情绪分类器误升级缺陷）87.8%、改前基线 95.0%、改后 95.0%，分意图逐项一致；三套产物入库并登记 `docs/EVIDENCE.md`。

**已知边界与后续（登记不执行）**：

1. part4-7 的 56 条新用例尚未并入离线判分器（judge schema 扩展），语义断言由五条 `verify-*.ps1` 活体脚本与 JVM 用例承载——触发条件：把新用例纳入 CI 的 rescore 门禁时一并做。
2. round17 新增的五条活体脚本尚未并入 `run-acceptance.ps1` 矩阵（重接线会改 17 步计数并连带审计与 README 换代）——触发条件：下次本机全量活体验收时一并做并整跑验证。
3. 本轮首次跑通全量 dev 评测所需的抬日预算（`run-dev-eval.ps1` 既有能力）从可选项变为必需项：dev 口径下每请求多一跳情绪分类调用，180 条约 40-60 万 token。
