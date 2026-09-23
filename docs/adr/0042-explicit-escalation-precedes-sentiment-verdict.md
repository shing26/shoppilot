# 显式转人工优先于情绪判定

Context: ADR 0017 把「转人工」下沉到 T0 规则层，理由是转人工是可用性兜底，不该由会抖的外部依赖决定（2026-09-09 的验收第 7 条就是被 bge-m3 超时害的：用户明确喊转人工，却先花 25 秒等大模型）。ADR 0034 又加了情绪门，位于 INTAKE、**先于**意图判定，高情绪直接落工单转人工，动机是"不让激动的买家走完全链路才被转接——等待本身就是二次激怒"。

两条在本 case 上冲突：一句平静的「转人工」不是情绪问题，但情绪门第二层会把它判成 `URGENT`，于是 INTAKE 的短路先 return，T0 那张含「转人工」的词表**根本没机会执行**。这是 ADR 0034 对 ADR 0017 的回归，不是口径问题：

- 2026-09-20 的 22 步全量矩阵：`fallback` 步红（`run-acceptance.ps1` 的该步就是 `verify-fallback.ps1`），根因登记为该矩阵的 F2。
- 2026-09-23 单跑复现：`verify-fallback.ps1` step 7 出 `reason=EMOTION_ESCALATION`（期望 `USER_REQUESTED`）；同一次运行里 `shoppilot_sentiment_llm_classified_total=19`、`shoppilot_sentiment_escalated_total{emotion=URGENT}=1` —— 是第二层模型判的 URGENT，不是词表命中，也不是 fail-open。

Decision: **显式转人工优先。** 情绪门的短路在「查询命中 T0 升级词表（含紧邻否定窗口）」时**不生效**，请求继续走到 triage，由既有 `Intent.ESCALATE -> FallbackReason.USER_REQUESTED` 出口收口。要点：

- 情绪判定本身照常计算，不跳过：`styleTier` 仍按它选档（先安抚再转接，与 ADR 0034 的取向一致），trace 里 `sentiment=` 那一步照记。
- **不新增出口、不改状态机枚举**（10 状态不变，ADR 0008 的边界不动）：复用的是已有的 TRIAGE/USER_REQUESTED 出口，避免出现第二处发 `USER_REQUESTED` 的地方。
- 优先级只在这一处生效：非显式转人工的高情绪仍由情绪门在 INTAKE 转接，ADR 0034 的行为不变。
- 代价是一次 0 token 的词表匹配（在情绪判定之后、短路之前）。

Considered Options:

- 情绪门的短路只对 `source()=="lexicon"` 生效、LLM 判的 URGENT 放行到 triage：否决。等于废掉 ADR 0034 第二层在 INTAKE 的作用，真正激动的买家会走完检索/模型/工具全链路才被转接，正面撞 ADR 0034 的动机。
- 只改 reason（仍走情绪路径、报 `USER_REQUESTED`）：否决。工单优先级与状态还是情绪那条，等于在错的路径上贴对标签。
- 改分类提示词/词表让模型别把「转人工」判 URGENT：否决。治不了顺序风险——任何一句被误判 URGENT 的显式转人工都会复现，而 prompt 是概率性的，换个说法就漏。
- 把 triage 整体提到情绪门之前：否决。ADR 0034 的"先于意图判定"是刻意的（省一次 triage 与 embedding），整体前移会把它的收益还回去；只需要一条纯词表谓词。

Consequences:

- `转人工` 恢复落 `USER_REQUESTED`，ADR 0017 的承诺还回来了；其余高情绪仍由情绪门在 INTAKE 转接，ADR 0034 不变。两条 ADR 的边界在本 ADR 里被显式定成优先级，不再靠执行顺序偶然决定。
- 可机器判定的验收：`scripts/verify-fallback.ps1` 的 step 7（活体）与 `GatewayMainPathJvmTest.explicitEscalationSurvivesAnUrgentSentimentVerdict`（0 token，0.089 s，确定性）都必须绿；后者在修法落地前是红的。
- 本 ADR 记录的是**回归修复的优先级决策**，不是新功能；冻结线允许修回归，故不适用 ADR 0031 的面试触发条件。
