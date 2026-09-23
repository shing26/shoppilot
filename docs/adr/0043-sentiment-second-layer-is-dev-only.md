# 情绪门第二层只在 dev 口径启用

Context: ADR 0034 把情绪门设计成两级——词典层 0 token 定案；词典不确定时走一次 LLM 分类。它自己的措辞是「**dev 口径** LLM 分类兜底」。但实现把这条限定只写在 `perf` 上：`SentimentGate` 里是 `if ("perf".equals(llm.mode()))` 才走词典层-only，于是 `local` 也进第二层。

**README 里写的也是 dev 口径**（`README.md` 主链路那段的「不确定才走一次 LLM 分类（dev 口径）」）——所以本 ADR 的执行结果是让**实现回到仓内已有文档的措辞**，而不是新立一条口径。

而情绪门位于 **INTAKE、先于缓存查询**，所以 local 下每一个「词表未命中的请求」都会多一跳固定延迟的模型调用——`perf` 登记的理由（票 36：MockLlmClient 不聪明、给每个请求平添一跳、压测读数失真）在 local 上同样成立，甚至更严重：`local` 是**演示与 22 步活体验收**的口径，于是 PLAN 承诺项「命中路径零模型调用」在 local 下不成立。

这是 ADR 0034 的实现口径比它的措辞宽了一档，而宽出来的那一档正好压在这条承诺项上：

- round17 活体验收登记 F1 实测 `shoppilot_sentiment_llm_classified_total=47 / requests_total=50`（**94%**），分类耗时均值 0.47s、最大 0.90s。
- 同一批证据里 `hitzero` 步红（命中路径多一次模型调用），`plan` 步红在「连打打不出 429」——被分类调用占掉的预算。
- JVM 侧可确定性复现：缓存命中请求 + local 口径的门 → `SentimentGate.evaluate` 打到 `llm.complete`（`GatewayMainPathJvmTest.cacheHitPathStaysZeroModelCallsInLocalMode` 修前红）。

Decision: **第二层只在 `dev` 口径启用。** 词典层未命中时，非 `dev` 一律直接 `UNCERTAIN`（reason 记为 `<mode>-lexicon-only`），不再调 LLM；`local` 与 `perf` 都只有词典层。`dev` 是唯一保留语义情绪判断的口径。

Considered Options:

- **把情绪门整体挪到 CACHE_READ 之后**：否决。能保留 local 的语义判定，但等于改说「一句缓存能命中的话术不会因情绪被转接」——那是对 ADR 0034 语义的改动，要另开决策；而本项只需要把实现收回到 ADR 0034 自己的措辞。
- **第二层只用于计数与话术档位、不再决定转接**：否决。治不了症状——LLM 还是被调用，命中路径照样多一跳固定延迟。
- **扩词典词表，减少落进第二层的比例**：否决。只要还有一条未命中就违约；且扩词要连否定词窗口一起看（ADR 0017 的教训：`不是真人`、`别找客服`不能被吞进去）。
- **把「命中路径零模型调用」改成 perf-only 口径**：否决。那是把判据改窄去适配实现，本仓明令禁止。

Consequences:

- `local`（演示与 22 步活体验收的口径）恢复「命中路径零模型调用」。
- **活体实测（2026-09-23，local 档）**：三次请求后 `shoppilot_sentiment_llm_classified_total` 增量为 **0**（F1 登记时是 94%），trace 里是 `sentiment=UNCERTAIN via local-lexicon-only`——即情绪门在 local 已 0 次模型调用。**整脚本 `verify-hit-zero-llm.ps1` 仍未转绿，卡在环境而不是本修法**：本机 `OLLAMA_MAX_LOADED_MODELS=1`（User 与 Machine 两处都显式设为 1），bge-m3 与 qwen2.5:3b 不能同时驻留，未命中路径的生成调用要等一次模型换入换出（约 6 s）而超过网关读超时 → `LLM_CIRCUIT_OPEN` → 首答不可写回 → 下游四条断言连带红（trace 原文：`TRIAGE layer=T0 intent=POLICY_FRESH` 正确、`RETRIEVE dense=20 lexical=20 fused=5 degraded=false` 正确、随后 `FALLBACK LLM_CIRCUIT_OPEN 本地模型返回 500`）。放开条件 = 把 Ollama 配成 `OLLAMA_MAX_LOADED_MODELS≥2` 并重启；该值是机器级设置且在两处被显式写下，本票没有擅自改。
- `dev` 保留语义情绪判断，由 `SentimentGateTest.devModeStillUsesTheLlmLayer` 作正对照钉住——否则「关掉第二层」会变成恒绿假防线。
- 代价：`local` 只有词典层，语义更弱（未命中词表）的情绪信号在 local 不再升级。这是有意的：local 是演示/验收档，dev 才是云端语义口径。ADR 0034 在 dev 下完全不变。
- 判据（全部 0 token、确定性）：`GatewayMainPathJvmTest.cacheHitPathStaysZeroModelCallsInLocalMode`（回归）、`SentimentGateTest.localModeIsLexiconOnly`（机制）、既有的 `perfModeIsLexiconOnly`、`devModeStillUsesTheLlmLayer`（正对照）。
- 本 ADR 记录的是**回归修复**（round17 引入的不变量破坏：`hitzero` 此前是绿的），不是新功能，故不适用 ADR 0031 的面试触发条件；ADR 0034 原文一字未改。
