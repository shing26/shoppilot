# 36 — SentimentGate 情绪门：词典层 0 token 定案 + EMOTION_ESCALATION（第 10 降级因）

**What to build:** 按 ADR 0034 新增 `sentiment` 包，情绪门插在 `INTAKE → TRIAGE` 之间（状态机 10 状态不扩，判定发生在 INTAKE 内部）：第一层情绪词典（`sentiment/lexicon.yml` 配置资产，纯 JVM 0 token）命中强愤怒/威胁/急迫词直接定案 ANGRY/URGENT；第二层词典不确定时走一次 LLM 分类（复用 `LlmGateway`，dev/local 口径），LLM 不可用或解析失败一律 UNCERTAIN fail-open。升级判据：ANGRY 恒升级，URGENT 需置信度 ≥ 0.8。`FallbackReason` 新增第 10 个枚举值 `EMOTION_ESCALATION`，话术先安抚后转接，工单带 `priority=high`（biz-mock 工单链路全层落字段，队列可反查）。被情绪门升级的请求不进 TRIAGE、不进缓存（分母定义不变、构成变化随指标登记）。

**Blocked by:** 票 34（CI rescore 门禁）+ 票 41（工具循环语义）——均已收口。

**Status:** implemented（2026-09-19；全量 JVM `3 + 12 + 225 = 240` 绿，量具 40 PASS，rescore exit 0，CI 见 Handoff）

- [x] `sentiment/lexicon.yml` 词典资产入库（改词条不改代码；数字词条必须加引号——YAML 会把裸数字解析成 Integer，首跑抓到）
- [x] `Emotion`（CALM/DISSATISFIED/ANGRY/URGENT/UNCERTAIN）+ `SentimentGate` 两级判定；`UNCERTAIN` 是门级 fail-open 输出，不是第五种情绪
- [x] `AgentStateMachine` INTATE 内接线：升级 → `fallback(INTAKE, EMOTION_ESCALATION, "emotion=… via …")`，不进 TRIAGE/缓存
- [x] `FallbackReason.EMOTION_ESCALATION`（第 10 位）；`FallbackService` 增 4 参重载（priority），3 参原样保留
- [x] biz-mock 工单链路 priority 字段：`Ticket` 实体（ddl-auto 自动建列）→ `CreateTicketRequest` → `createTicket` → `TicketView`（队列反查可见）
- [x] 指标 4 个：`shoppilot_sentiment_lexicon_decided_total`、`shoppilot_sentiment_llm_classified_total`、`shoppilot_sentiment_llm_latency_seconds`、`shoppilot_sentiment_escalated_total{emotion}`
- [x] `SentimentGateTest` 6 项 0 token：词典层 8 条升级定案（LLM 零触碰）、12 条非升级零误伤、ANGRY>URGENT 优先、第二层置信度判据、散文/围栏容错、perf 词典层-only
- [x] `GatewayMainPathJvmTest` 情绪升级集成用例：TRIAGE 之前落工单、`promptVersion` 进响应、模型零调用
- [x] `verify-emotion.ps1` 活体验收脚本（20 条用例 + 工单队列反查 priority=high），UTF-8 BOM、PS 5.1 语法通过
- [x] 评测集 `cases-part4-emotion.jsonl` 20 条已在票 34 前入库；CI rescore 门禁确认 judge 语义零漂移

**Verify:**

```powershell
.\mvnw.cmd -B -ntp verify
python scripts\verify_eval_judge.py
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\check-ps-syntax.ps1   # verify-emotion.ps1 零错误
# 活体（需 dev 栈）：
pwsh -NoProfile -File scripts\verify-emotion.ps1
```

预期：全量三模块 240 绿；量具 40 PASS；rescore `tool_diff=4` exit 0；活体脚本 PASS 20 / FAIL 0。

**验收项**

1. 8 条词典层升级样本 0 token 定案（SentimentGateTest 机器断言，LLM 零交互）；活体走 EMOTION_ESCALATION 工单且 priority=high 可反查。
2. 12 条非升级样本零误伤（含两条反讽样本——词典层不命中，dev 口径由第二层分类兜为 DISSATISFIED 不升级）。
3. 情绪门升级发生在 TRIAGE 之前：意图判定不执行、缓存不进、拦截率分母定义不变（构成变化由 `shoppilot_sentiment_*` 指标单独可见）。
4. 已知口径照登：perf 下情绪门只有词典层（MockLlmClient 刻意"不聪明"，对它做分类必然 UNCERTAIN 还给含缓存命中在内的每个请求平添一跳固定延迟，压测读数会失真）；活体 LLM 分类只在 dev/local 口径，判据由 verify-emotion.ps1 承载。
5. 语义重叠登记：part3 的 ESC-04「我要投诉你们客服」含威胁词，现在会在情绪门定案（EMOTION_ESCALATION + high）而非走 T0 的 USER_REQUESTED——都是转人工、工单都落库，评测判的是 escalate 标志不变；rescore 门禁确认 180 条旧明细的选对工具结论零漂移。

## Handoff notes

**关键决策**

- **第二层 LLM 分类按 mode 开关而非无条件执行**：`"perf".equals(llm.mode())` 时跳过。ADR 0034 原文的"LLM 不可用时 UNCERTAIN"覆盖了 Mock 场景，但那样 perf 的每个请求（含缓存命中）都会多一跳 300ms 固定延迟，22ms P99 与 500 QPS 读数全部失真；MockLlmClient 自陈"任何质量类指标都不得用这个客户端测出来"。词典层在 perf 照常跑（0 成本，压测里愤怒买家同样被前置转人工）。
- **词典威胁词单独成表（threat_markers）**：EMO-ESC-04 的形态是"平淡抱怨 + 明说要走 12315"，没有强愤怒词——威胁渠道本身就是强烈不满的直接表达，单独成表让"投诉/315/曝光"无需共现条件即可定案，词表语义比共现规则可解释。
- **priority 走全层落字段而非复用 transcript**：工单的优先级是队列排序的一等字段，塞进 transcript 字符串等于让机器读人话；`Ticket` 实体 → 请求记录 → 视图四层贯通，`ddl-auto: create` 免迁移脚本。

**验证落点**

- 全量 `mvnw verify`：`3 + 12 + 225 = 240` 绿（新增 SentimentGateTest 6 项 + 情绪升级集成 1 项）。
- 量具 40 PASS；rescore `tool_diff=4` exit 0（本票不改 judge 语义，CI 门禁背书）。
- `verify-emotion.ps1`：活体验收未跑（本机无 dev 栈），登记为 17 步全量验收的既有边界；脚本 PS 5.1 语法零错误（check-ps-syntax 在本机 5.1 下对其余脚本的历史报错与本票无关）。
- CI run 见 Handoff 末行。

**现场追问**

1. *为什么情绪门放在会话加载之后、待办续办之前？* 门在 INTAKE 内统一前置于一切业务分支：待办续办的补充信息（地址、订单号）不会撞词典，而带着情绪的续办请求被升级恰恰是对的——激动的买家不该继续跟机器对话。统一入口也让"升级不进 TRIAGE/缓存"的口径没有例外分支。
2. *ESC 类查询（显式转人工）撞上威胁词怎么办？* 见验收项 5 的登记：含"投诉"的显式转人工现在走情绪门（EMOTION_ESCALATION + high），平静的"给我转人工"仍走 T0（USER_REQUESTED）。两条路径都落可查工单，区别是队列优先级与指标口径——这正是 ADR 0034"情绪升级指标必须独立可查"想要的分账。
3. *词典为什么用 snakeyaml 现读而不是塞进 application.yml？* ADR 0034 把词典定义为"改词条不改代码"的独立配置资产：它在自己的文件里带注释、按语义分三张表、被 0 token 单测直接驱动；application.yml 是网关运行参数，混进去会让"运营调词表"和"运维调网关"共享同一个变更面。数字词条加引号（YAML Integer 坑）已写进文件头注释。

**2026-09-20 凌晨追记（活体栈恢复后抓到，属本票）**：硬闸门第一次全量评测就现形——旧分类提示词把"90002 的快递到哪了"这类平静业务查询判成 `URGENT`（置信度 ≥0.8）→ 情绪门在 TRIAGE 前误升级，ACTION_LOGISTICS 选对工具掉到 22.2%、ORDER 50%（回归现形跑 `tool-eval-20260920-021255-dev-round17-baseline.*` 留档作证据）。JVM 测试因 mock 分类器看不到分类质量，这是评测集第一次跑就抓到的系统性问题。修复 `daa40cf`：(1) 从严校准提示词（短问句默认 CALM、URGENT 收紧为真实紧急情境、附参考标尺）；(2) 分类提示词按 ADR 0037 纪律外置为 `prompts/sentiment-classifier/v1.0.0.md`（PromptCatalog 泛化出可配置基座 + `PromptAssetsConfig` 具名 Bean）。随之照登的新口径：dev 口径下每个请求多一跳分类调用，全量评测 token 成本约翻倍；校准后同晚改前基线回到 95.0%（与历史 dev 基线同型）。
