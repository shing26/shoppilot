# 66 — token 计量加 `source` 标签（名字不变、计数不变）

**What to build:** 让 token 计量的口径在指标上**机器可读**。`shoppilot_llm_tokens_total` 的三个来源计量方法不同却共用一个名字：`MockLlmClient` 用 `estimateTokens()` **估算**（`:35/:120`）、`OpenAiCompatibleLlmClient` 用 provider 回报的 `usage.prompt_tokens` **真值**（`:161`）、`OllamaLlmClient` 用模型自报的 `prompt_eval_count` **真值**（`:161`），三者都喂给 `LlmGateway.java:115` 同一个 counter、无标签区分。模式在部署期固定，所以同序列内不混方法 —— **缺的是口径声明的机器可读性，不是测量正确性**。

**Blocked by:** None（票 57 已收口；此处只记它与 57 的计数耦合）。

**Status:** ready-for-agent

**依据：政策越过（ADR 0048）。** **本票是 round22 最弱的一票，可无损删** —— 若工期紧，第一个砍它。

口径（ADR 0048 已定，本票只执行）：

- **给现有计数器加 `source=provider|estimate` 标签，不加新指标名**（名字不变 → **指标名计数不变**），照票 47 的 `result` 标签先例。perf / local 档记 `estimate`、dev 云端档记 provider。
- **红线：不改 `TokenBudget` 的放行语义。** 预算是另一件事，本票只动计量标签。
- **与票 67 相容**：67 的告警规则引用真实指标名；本票不改任何名字，故不触发规则的「改名即红」守卫。

- [ ] `shoppilot_llm_tokens_total` 带 `source` 标签，perf/local → `estimate`、dev → `provider`
- [ ] **指标名计数不变**（现场 grep 现算仍为 53）
- [ ] `TokenBudget` 放行行为逐字不变（`TokenBudgetTest` 原样通过，不新增断言去适配）
- [ ] 两档的 `source` 取值有 JUnit 用例可分辨
- [ ] 全量 `verify` 绿；`check_coverage.py` exit 0

**Verify**
```bash
./mvnw.cmd -B -ntp verify
python scripts/check_coverage.py
grep -rhoE 'shoppilot_[A-Za-z0-9_]+' shoppilot-gateway/src/main shoppilot-biz-mock/src/main shoppilot-tool-api/src/main | sort -u | wc -l   # 53
```