# 50 — 显式输出上限（max_tokens / num_predict）

**What to build:** 给模型输出加显式上限。现状 `max_tokens` 全仓零命中——两个客户端只设 `temperature`（`OpenAiCompatibleLlmClient.payload()` 放 `model/temperature/messages/stream/[stream_options]/[tools,tool_choice,parallel_tool_calls]`，`OllamaLlmClient.payload()` 放 `model/messages/stream/options.temperature/[tools]`），输出长度完全由模型自行决定。

**Blocked by:** None（只碰 `llm/`、`config/GatewayProperties`、`application.yml` 与两个测试类）。

**Status:** implemented（2026-09-24）。

口径（ADR 0044 已定，本票只执行）：

- **这是稳定性护栏，不是成本优化**：成本护栏是 `TokenBudget` 的日预算熔断（ADR 0012），本票防的是**输出失控**。所以默认值**给足余量**——`PromptCatalog` 规则 11 写「不超过 200 字」，但输出里还要含工具调用 JSON，按 200 字设小会截断评测答案、反而污染 dev 读数。
- **只在配置值 > 0 时下发**：0 = 不限制，保持向后兼容（与本仓「显式关闭」的既有习惯一致）。
- **若 dev 读数漂移，按「保留红值 + 归因」处理**，不改判据、不改 gold、不调默认值去凑旧读数。

- [ ] `GatewayProperties.Llm` 新增 `max-output-tokens`，带 `@Min` 校验（与既有 `temperature` 的 `@DecimalMin/@DecimalMax` 同风格）
- [ ] `application.yml` 给默认值（给足余量），并说明「防输出失控」而非省 token
- [ ] `OpenAiCompatibleLlmClient.payload()` 加 `max_tokens`（配置 > 0 时）
- [ ] `OllamaLlmClient.payload()` 的 `options` 加 `num_predict`（配置 > 0 时）
- [ ] JVM 用例：两个客户端发出的请求体都含该字段且值等于配置
- [ ] JVM 用例：`max-output-tokens: 0` 时两个客户端都不下发该字段
- [ ] `ConfigValidationTest` 补越界校验（负数即启动失败）
- [ ] 收口时确认 180 条 gold 读数未漂移（默认值给足余量应当不漂移）；若漂移，记录归因
- [ ] 覆盖率：新增代码带用例，`check_coverage.py` 仍 exit 0

**Verify:**

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/check_coverage.py
```

## Handoff notes

**关键决策**

- **默认值 1024 是「给足余量」，不是「按 200 字设小」。** `PromptCatalog` 规则 11 要求回答不超过 200 字，但输出里还要含工具调用 JSON，且**截断会污染 dev 评测读数**（答案被切一半，准确率掉下来却看不出是护栏干的）。所以这个上限的作用是**防输出失控**，不是省 token——省 token 是 ADR 0012 的日预算熔断，两件事不重叠。
- **0 = 不发这个字段，不是发 `max_tokens: 0`。** 后者在多数端点上等于「最多生成 0 个 token」，是个会把服务打瘫的值。所以配置校验允许 0（`@Min(0)`），客户端只在 `> 0` 时下发，并有一条用例专门钉这个向后兼容口（`zeroMaxOutputTokensOmitsTheField`）。
- **字段名跟着端点走。** OpenAI 兼容面是 `max_tokens`，Ollama 是 `options.num_predict`——不是同一个字段名的两处复制。用例也分开断言：Ollama 那条明确断言 `has("max_tokens")` 为 false。
- **本仓此前**没有任何**用例碰过 `OllamaLlmClient` 的请求体**（`local` 档的采样参数等于没人看）。本票新建了 `OllamaLlmClientTest`，用假 Ollama（`HttpServer` 收原始请求体，与 `EmbeddingSingleFlightTest` 同一套 seam）钉住 `num_predict` 的下发与省略。
- **新增 env 占位符被门禁拦住，处置是登记而不是绕过。** `ConfigValidationTest.applicationPlaceholderSetIsPinned` 逐字钉着 `application.yml` 里的 SHOPPILOT 占位符集合，加 `SHOPPILOT_LLM_MAX_OUTPUT_TOKENS` 会让它红。这条门禁的意思正是「扩大可配置面要显式决定」，所以我把新占位符按字母序加进清单（`DAILY_TOKEN_BUDGET` 与 `MODEL` 之间）——家法同 ADR 0012 的日预算：**要能靠环境变量改的东西，就不该逼人去动仓库里的配置文件**。

**验证落点**

- `OpenAiCompatibleLlmClientTest`：请求体 `max_tokens == 1024`（两条既有用例各加一条断言）；`zeroMaxOutputTokensOmitsTheField` 断言 0 时字段不存在。
- `OllamaLlmClientTest`（新建 3 条）：`options.num_predict == 512` 且 `has("max_tokens")` 为 false；0 时 `options` 里无 `num_predict`；流式路径同样下发（`stream: true` + `num_predict == 256`）。
- `ConfigValidationTest.maxOutputTokensMustNotBeNegative`：`-1` 即启动失败；`applicationPlaceholderSetIsPinned` 同步登记新占位符。
- 全量：gateway 253 → 269 的一部分。
- **未验证**：180 条 gold 读数是否漂移（需云端额度重跑 dev 全量评测）。按未验证登记，不得声称「未漂移」。

**你需要能当场回答的三个追问**

1. *Q：加输出上限，会不会把答案截断导致准确率掉？* A：默认 1024 相对「200 字上限 + 工具调用 JSON」有 5 倍以上余量，而且**这个值是显式护栏不是调优旋钮**——真要压成本该动的是 ADR 0012 的日预算。另外它可被 `SHOPPILOT_LLM_MAX_OUTPUT_TOKENS` 覆盖，也能设 0 关掉。**诚实边界**：漂移与否我没重跑 180 条 dev 评测，按未验证登记了。
2. *Q：为什么 Ollama 不叫 `max_tokens`？* A：因为 Ollama 的 `/api/chat` 不认这个字段名，它把生成参数放在 `options` 里、上限叫 `num_predict`。用例里明确断言 Ollama 请求体**不含** `max_tokens`，就是为了防止有人「统一字段名」把它改错。
3. *Q：这个字段为什么现在才加？之前没有上限不是也能跑吗？* A：能跑，但输出长度完全由模型自行决定——一个跑飞的生成会把一次请求拖到读超时，而超时在链路上表现为降级（`LLM_TIMEOUT`），根因被藏在「模型不可用」后面。显式上限把这种失败模式从「偶发的降级」变成「确定的上限」。
