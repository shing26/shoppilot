# 66 — token 计量加 `source` 标签（名字不变、计数不变）

**What to build:** 让 token 计量的口径在指标上**机器可读**。`shoppilot_llm_tokens_total` 的三个来源计量方法不同却共用一个名字：`MockLlmClient` 用 `estimateTokens()` **估算**（`:35/:120`）、`OpenAiCompatibleLlmClient` 用 provider 回报的 `usage.prompt_tokens` **真值**（`:161`）、`OllamaLlmClient` 用模型自报的 `prompt_eval_count` **真值**（`:161`），三者都喂给 `LlmGateway.java:115` 同一个 counter、无标签区分。模式在部署期固定，所以同序列内不混方法 —— **缺的是口径声明的机器可读性，不是测量正确性**。

**Blocked by:** None（票 57 已收口；此处只记它与 57 的计数耦合）。

**Status:** implemented（2026-09-28）。

**依据：政策越过（ADR 0048）。** **本票是 round22 最弱的一票，可无损删** —— 若工期紧，第一个砍它。

口径（ADR 0048 已定，本票只执行）：

- **给现有计数器加 `source=provider|estimate` 标签，不加新指标名**（名字不变 → **指标名计数不变**），照票 47 的 `result` 标签先例。perf / local 档记 `estimate`、dev 云端档记 provider。
- **红线：不改 `TokenBudget` 的放行语义。** 预算是另一件事，本票只动计量标签。
- **与票 67 相容**：67 的告警规则引用真实指标名；本票不改任何名字，故不触发规则的「改名即红」守卫。

- [x] `shoppilot_llm_tokens_total` 带 `source` 标签，Mock（perf）→ `estimate`；Ollama（local）与云端（dev）→ `provider`
- [x] **指标名计数不变**（现场 grep 现算仍为 53）
- [x] `TokenBudget` 放行行为逐字不变（`TokenBudgetTest` 原样通过，不新增断言去适配）
- [x] 两档的 `source` 取值有 JUnit 用例可分辨
- [x] 全量 `verify` 绿；`check_coverage.py` exit 0

**Verify**
```bash
./mvnw.cmd -B -ntp verify
python scripts/check_coverage.py
grep -rhoE 'shoppilot_[A-Za-z0-9_]+' shoppilot-gateway/src/main shoppilot-biz-mock/src/main shoppilot-tool-api/src/main | sort -u | wc -l   # 53
```

## Handoff notes

**关键决策**

- **标签挂在「这一路怎么算 token」上，不挂在 mode 上。** 新增 `LlmClient.tokenSource()`（默认 `provider`），`MockLlmClient` 覆写成 `estimate`；`LlmGateway` 用它给计数器加标签。**没有**从 `mode()` 反推 source —— 那是把「哪种计数方法」与「哪个部署档」绑死，而两者本来就是两回事（dev 档将来若换成本地模型，模式名不变、计数方法会变）。
- **加标签不加指标名**：`Counter.builder("shoppilot_llm_tokens_total").tag("mode", …).tag("source", …)`。现场 grep 复算仍是 **53** 个唯一指标名 —— 这是本票的硬约束（票 68 收口的指标名重算会复核这一条）。
- **红线守住**：`TokenBudget` 一行未动（`TokenBudgetTest` 6 条原样通过）。本票只动计量标签，不动放行语义。

**验证落点**

- 新增 `llm/LlmTokenSourceLabelTest` **3 条**：perf → `source=estimate`；dev 与 local → `source=provider`；以及「加标签不新增指标名」——断言名字前缀为 `shoppilot_llm_tokens` 的 meter **恰好一条**，并验证加分仍读得到（`increment(7)` → `count()==7`）。
- 全量 `.\mvnw.cmd -B -ntp verify` → **`5 + 29 + 304 = 338` 绿**（gateway 301 → 304）。
- 覆盖率棘轮 exit 0：gateway LINE 61.95% → **62.79%**；biz-mock 79.30%、tool-api 47.95%。
- 指标名现场复算 **53**（与票 64 之后一致）。

**未达成**

- 无未达成项。**但本票是 round22 最弱的一票**（ADR 0048 已写明「可无损删」）：它换来的是口径声明的机器可读性，不是测量正确性 —— 部署期模式固定，所以同一条序列里本来就不会混方法。若将来有人质疑这层标签的价值，删掉它的代价就是这一条用例与一个 tag。

**你需要能当场回答的三个追问**

1. *Q：为什么不新增一个指标名来区分？* A：名字一变指标名计数就变（53 → 54+），而计数本身是 README 的公开读数、每次换代都要写指针。这里要的是「口径声明的机器可读性」，一个 tag 就够。
2. *Q：为什么不让 `mode` 兼任 source？* A：`mode` 答的是「打哪个端点」（dev/local/perf），`source` 答的是「这个 token 数是估的还是供应商报的」。今天两者一一对应，明天不一定 —— 把它们绑死会在换端点的那天静默给出错的声明。
3. *Q：这票和 TokenBudget 有什么关系？* A：没关系，而且必须没关系。预算管的是放行，本票管的是读数口径；`TokenBudgetTest` 原样通过就是「没动预算语义」的机器证据。