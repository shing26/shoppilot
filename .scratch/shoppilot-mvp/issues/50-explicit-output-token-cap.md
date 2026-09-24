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
- [x] 收口时确认 180 条 gold 读数未漂移 —— **2026-09-25 已验，三条腿**（见 Handoff 的「验证落点」）：解析证明该上限在本工作负载上**不可能生效**；正向对照证明旋钮**确实活着**；24 条 dev 实测与 2026-09-10 基线逐条比 23/24 一致、唯一差异是变好。**未做**的是 180 条全量重跑（要约 19 万 token 且比较会被后几轮改动混淆，见下）
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
- **验证落点（2026-09-25 补齐，三条腿）**：
  1. **解析证明「这个上限不可能生效」**：仓库里全部 dev 明细（180 条全量跑 + 按意图补跑）共 **1176 条**的 `completion_tokens` 是 p50=63 / p95=222 / **max=440**，**超过 1024 的 0 条**；今天 cap 开那轮 24 条的 max 是 362。`max_tokens` 只限制生成长度，够不到就完全不影响输出 → 在 1024 上它**截断不了任何一条答案**。
  2. **正向对照证明「旋钮确实活着」**：把 `SHOPPILOT_LLM_MAX_OUTPUT_TOKENS` 压到 20（写在 `.env`，见下面那条坑）后重启，同一问句的答案被**截在 20 token、句子断在半路**（`…不是“二`）。旋钮生效、字段确实下发到 DashScope、且 DashScope 认它。
  3. **实测对照**：dev 档 24 条全过（10 个意图 100%、0 错误、completion 合计 3401）。与 **2026-09-10 基线逐条比：23/24 一致**，唯一差异 `ACT-ADR-03` 是 `False → True`（**变好**，不是漂移）。
- **未做（如实登记）**：180 条**全量重跑**。两个理由：① 机制问题已由第 1 条解析证明结清；② 更要紧的是**全量重跑与本问题的比较会被混淆**——round17 之后 `StyleService` 往 system prompt 里注入了风格段、dev 档还多了情绪分类那一次 LLM 调用，所以「今天跑出的数字 vs 09-10 基线」的差异**不能归因到票 50**。要实测票 50 的贡献只能做同代码的 A/B（cap 1024 vs 0，各 180 条，约 38 万 token）。
- **两个会再咬人的坑（本轮实测踩到）**：
  1. **环境变量只能走 `.env`**：launcher 由 `lib-launch.ps1` 的 **WMI `Win32_Process.Create`** 创建，**WMI 起的进程不继承调用方环境**，runner 脚本里只有 `.env` 的键。所以在 shell 里 `export SHOPPILOT_LLM_MAX_OUTPUT_TOKENS=0` **完全无效**——我第一次的 A/B 因此是「cap 开 vs cap 开」，白跑一轮。要改任何 `SHOPPILOT_*` 一律写 `.env`（`.env.example` 的措辞「设环境变量」指的正是这个文件）。
  2. **换 profile 必须先 stop**：`start-gateway.ps1 -Profile dev` 在已有网关运行时，新进程会以 `Port 8082 already in use` 启动失败，而 `/actuator/health/readiness` 仍从**旧进程**返回 200 → 假绿。判据要用 `/api/v1/support/ops/switches` 的 `llmMode`，不是 readiness。

**你需要能当场回答的三个追问**

1. *Q：加输出上限，会不会把答案截断导致准确率掉？* A：默认 1024 相对「200 字上限 + 工具调用 JSON」有 5 倍以上余量，而且**这个值是显式护栏不是调优旋钮**——真要压成本该动的是 ADR 0012 的日预算。另外它可被 `SHOPPILOT_LLM_MAX_OUTPUT_TOKENS` 覆盖，也能设 0 关掉。**诚实边界**：漂移与否我没重跑 180 条 dev 评测，按未验证登记了。
2. *Q：为什么 Ollama 不叫 `max_tokens`？* A：因为 Ollama 的 `/api/chat` 不认这个字段名，它把生成参数放在 `options` 里、上限叫 `num_predict`。用例里明确断言 Ollama 请求体**不含** `max_tokens`，就是为了防止有人「统一字段名」把它改错。
3. *Q：这个字段为什么现在才加？之前没有上限不是也能跑吗？* A：能跑，但输出长度完全由模型自行决定——一个跑飞的生成会把一次请求拖到读超时，而超时在链路上表现为降级（`LLM_TIMEOUT`），根因被藏在「模型不可用」后面。显式上限把这种失败模式从「偶发的降级」变成「确定的上限」。
