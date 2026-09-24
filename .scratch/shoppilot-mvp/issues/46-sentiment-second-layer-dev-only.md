# 46 — 情绪门第二层只在 dev 口径启用（回归修复）

**What to build:** 修 round17 引入的不变量破坏：`SentimentGate` 把「词典层-only」只限定在 `perf` 上，于是 `local` 也走第二层 LLM 分类；而情绪门位于 INTAKE、**先于缓存查询**，本地每个词表未命中的请求都多一跳模型调用，「命中路径零模型调用」这条 PLAN 承诺项在 local 下不成立。按 ADR 0043 把第二层限定为 `dev`：非 dev 一律 `UNCERTAIN`（reason `<mode>-lexicon-only`）。

**Blocked by:** None（只碰 `sentiment/SentimentGate` 与两个测试类；与已收口的票 45 文件面邻接但不重叠——票 45 改的是状态机的优先级，本票改的是门的模式判定）。

**Status:** implemented（2026-09-23）。

口径（ADR 0043 已定，本票只执行）：

- **收回到 ADR 0034 自己的措辞**——它写的就是「dev 口径 LLM 分类兜底」，实现比措辞宽了一档。
- **不动状态机顺序**，不把情绪门挪到 CACHE_READ 之后（那是对 ADR 0034 语义的改动，要另开决策）。
- **dev 必须保留第二层**，并加正对照钉住，否则「关掉第二层」会变成恒绿。

- [x] `SentimentGate` 的第二层守卫由 `"perf".equals(mode())` 改为 `!"dev".equals(mode())`；reason 记为 `<mode>-lexicon-only`（perf 的原值 `perf-lexicon-only` 不变）
- [x] 类的 javadoc 写明 local 的理由（位于 INTAKE、先于缓存查询，压承诺项）与 dev 的例外地位
- [x] 回归用例转绿：`GatewayMainPathJvmTest.cacheHitPathStaysZeroModelCallsInLocalMode`（local 口径、缓存命中、断言门不调模型）
- [x] 机制用例：`SentimentGateTest.localModeIsLexiconOnly`（local → UNCERTAIN、source=`local-lexicon-only`、`never().complete/stream`）
- [x] **正对照**：`SentimentGateTest.devModeStillUsesTheLlmLayer`（dev → 同一句必须真的调一次 `complete`）
- [x] 变异对照：守卫改回 `"perf".equals(...)` → 上面前两条**同时变红**
- [x] 活体**机制**：local 档下三次请求后 `shoppilot_sentiment_llm_classified_total` 增量为 **0**（F1 登记时 94%），trace 为 `sentiment=UNCERTAIN via local-lexicon-only`
- [x] 活体**整脚本** `verify-hit-zero-llm.ps1` 转绿 —— **2026-09-24 已达成（10/10 PASS、exit 0）**。此前记的阻塞原因是 `OLLAMA_MAX_LOADED_MODELS=1`，**这条归因是错的**：当天实测报的是 `cudaMalloc failed: out of memory` 与 `failed to allocate CUDA_Host buffer`（本机同时跑四套项目共 17 个容器，显存与主机内存瞬时争抢；当时 `/api/ps` 零模型驻留）。逐个预热四个模型后 `qwen2.5:3b` 与 `bge-m3` 可同时驻留，**且该环境变量全程未改（仍为 1）**，脚本即 10/10 通过。本票的机制修复（第二层限定 dev）未变，转绿只是环境不再争抢

**Verify:**

```powershell
.\mvnw.cmd -B -ntp verify
pwsh -NoProfile -File scripts/verify-hit-zero-llm.ps1
```

预期：全量 `3 + 21 + 253 = 277` 绿（gateway 250 → 253：本票新增 3 条——1 条回归 + 1 条机制 + 1 条正对照）；活体脚本打印 `全部通过：命中路径零模型调用`、exit 0。

## Handoff notes

**关键决策**

- **只改模式守卫，不动门的顺序。** 另一条能达成同样效果的修法是「把情绪门整体挪到 CACHE_READ 之后」——它甚至更好（local 也能保留语义判定）。但它改的是 ADR 0034 的语义（等于宣布「一句缓存能命中的话术不会因情绪被转接」），那要另开决策；而本票只需要把实现收回到 ADR 0034 自己写的措辞（「dev 口径 LLM 分类兜底」）。**顺带一条：这条修法之所以成立，是因为 ADR 0034 的原文本来就说了 dev 口径**——先读措辞再动代码，省掉一次架构争论。
- **`perf` 的理由与 `local` 的理由不同，但结论同向。** perf 是「MockLlmClient 不聪明 + 压测读数失真」（票 36 登记），local 是「位于缓存查询之前 + local 是验收口径」。两个理由都写进 javadoc，免得后人以为 local 是被顺手关掉的。
- **必须加 dev 的正对照。** 去掉第二层的修法有一个天然陷阱：把第二层整个删掉，本票的回归用例也会绿。所以 `devModeStillUsesTheLlmLayer` 断的是「dev 下同一句必须真的调一次 `complete`」——它让「关掉第二层」这条路现形。
- **回归用例的断言要尖。** 第一版写的是 `verifyNoInteractions(gateLlm)`，修完仍然红——因为门读一次 `mode()` 也算交互。改成 `never().complete/stream` 才是症状本身（「有没有打模型调用」），否则判据钉在一个无关的读上。这条同 `verify_eval_judge.py` 对「恒绿的夹具和恒红的断言一样是摆设」的口径。

**验证落点**

- 回归 + 机制 + 正对照：`GatewayMainPathJvmTest.cacheHitPathStaysZeroModelCallsInLocalMode`（0 token、确定性、修前红）、`SentimentGateTest.localModeIsLexiconOnly`、`SentimentGateTest.devModeStillUsesTheLlmLayer`。
- **变异对照**：守卫改回 `"perf".equals(...)` → 前两条同时红（`Tests run: 20, Failures: 2`），恢复即绿。
- 全量：`.\mvnw.cmd -B -ntp verify` → `3 + 21 + 253 = 277` 绿，收口审计 G6 常数 `[3, 21, 250]` → `[3, 21, 253]`，并刷新本地 `logs/acceptance/{build,unit}.log`（G6 读它们；跑 `run-acceptance.ps1 -Only build,unit -SkipStack`，它要求工作树干净，且 `mvn verify` 前须先 `down.ps1` 否则 jar 被占用）。
- 活体**机制已实测**（这是本票的关键读数）：local 档连续三次请求，`shoppilot_sentiment_llm_classified_total` 增量 **0**（F1 登记时是 94%），trace 为 `sentiment=UNCERTAIN via local-lexicon-only`——情绪门在 local 已零次模型调用。
- **未达成（2026-09-23 登记，2026-09-24 已关闭）**：整脚本 `verify-hit-zero-llm.ps1` 当时红（10 项里 6 项 FAIL），**按未达成登记、未摘红**。当时的归因是本机 `OLLAMA_MAX_LOADED_MODELS=1`，bge-m3 与 qwen2.5:3b 不能同时驻留、生成调用等模型换入换出超读超时 → `FALLBACK LLM_CIRCUIT_OPEN 本地模型返回 500` → 首答落不了可写回状态，下游四条断言连带红（trace 原文当时证明门与 triage 都是对的：`TRIAGE layer=T0 intent=POLICY_FRESH admissible=true`、`RETRIEVE dense=20 lexical=20 fused=5 degraded=false`）。**换代指针（2026-09-24）**：这条归因**是错的**——实测报的是 `cudaMalloc failed: out of memory` 与 `failed to allocate CUDA_Host buffer`（四套项目共 17 个容器并发抢显存与主机内存，当时 `/api/ps` 零模型驻留，所以根本不是"两模型不能同时驻留"）。逐个预热模型后 `qwen2.5:3b` 与 `bge-m3` 同时驻留，**该环境变量全程未改（仍为 1）**，脚本 **10/10 PASS、exit 0**。旧读数与旧归因按原样保留在此，只补这条更正。

**你需要能当场回答的三个追问**

1. *Q：为什么直接关掉 local 的第二层，而不是把它挪到缓存查询之后？* A：两条都能让命中路径零模型调用。挪位置更好，但它改的是语义（缓存能命中的话术不再因情绪被转接），要单独决策；关掉 local 的第二层只是把实现收回 ADR 0034 自己写的「dev 口径兜底」，不动任何 ADR 原文，也不动状态机顺序。**先取小改动、把语义改动留给真需要它的人。**
2. *Q：local 关掉第二层，情绪能力是不是变弱了？* A：是，而且是有意的——local 是演示与验收档，dev 才是云端语义口径。ADR 0034 的能力在 dev 下一点没少，`devModeStillUsesTheLlmLayer` 就是钉这件事的。代价写在 ADR 0043 的 Consequences 里，不藏。
3. *Q：这条为什么算回归而不是"一开始就这么设计的"？* A：因为 `hitzero` 步在 round17 之前是绿的，情绪门上来之后变红——一条此前成立的不变量被打破了，这是回归的定义。round17 spec 的活体验收登记也把它记成 F1（`sentiment_llm_classified_total=47/requests_total=50`），归因栏写的是「ADR 0034 未把第二层限定为 dev 口径」。
