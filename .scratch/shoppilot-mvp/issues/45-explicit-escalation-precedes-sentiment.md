# 45 — 显式转人工优先于情绪判定（回归修复）

**What to build:** 修 ADR 0034 对 ADR 0017 的回归。一句平静的「转人工」被情绪门第二层判成 `URGENT` 后，INTAKE 的情绪短路抢先落 `EMOTION_ESCALATION`，T0 那张含「转人工」的升级词表根本没机会执行。按 ADR 0042 把优先级定成「显式转人工优先」：命中 T0 升级词表（含紧邻否定窗口）时不走情绪短路，放行到 triage，由既有 `Intent.ESCALATE -> USER_REQUESTED` 出口收口。

**Blocked by:** None（只碰 `triage/` 与 `agent/AgentStateMachine`，与已收口的票 42-44 文件面不重叠）。

**Status:** implemented（2026-09-23）。

口径（ADR 0042 已定，本票只执行）：

- **只加一条纯词表谓词**，不把 triage 整体前移——ADR 0034 的"先于意图判定"是刻意的，整体前移会把它的收益还回去。
- **复用既有出口**，不新增第二处发 `USER_REQUESTED` 的地方，也不动 10 状态枚举（ADR 0008 的边界）。
- **情绪判定照常计算**：`styleTier` 仍按它选档（先安抚再转接），trace 里 `sentiment=` 那一步照记。

- [x] `T0RuleLayer.explicitEscalation` 由包内 `static` 提为 `public static`（纯谓词、无状态；第二个调用方是状态机），补 javadoc 说明第二个调用方
- [x] `TriageEngine` 增 `public boolean isExplicitEscalation(String query)`，委托给 T0 那张词表
- [x] `AgentStateMachine` 的情绪短路加前置条件：`sentiment.escalated() && !triageEngine.isExplicitEscalation(query)`
- [x] 回归用例转绿：`GatewayMainPathJvmTest.explicitEscalationSurvivesAnUrgentSentimentVerdict`（该用例在修法落地前是红的，见 Handoff）
- [x] 变异对照：把 `&& !…isExplicitEscalation(query)` 摘掉 → 该用例当场变红（`expected:<USER_REQUESTED> but was:<EMOTION_ESCALATION>`）
- [x] 活体：`scripts/verify-fallback.ps1` step 7 由 `EMOTION_ESCALATION` 转 `USER_REQUESTED`，整脚本 **7/7 PASS、exit 0**
- [x] 非显式转人工的高情绪仍被情绪门在 INTAKE 转接（修后 `shoppilot_sentiment_escalated_total{emotion=URGENT}` 仍计 1，`SentimentGateTest` 8 条升级样本与 12 条不误伤样本未回归）

**Verify:**

```powershell
.\mvnw.cmd -B -ntp verify
pwsh -NoProfile -File scripts/verify-fallback.ps1
```

预期：全量 `3 + 21 + 250 = 274` 绿（gateway 249 → 250，新增 1 条回归用例；其余用例数未动）；活体脚本 step 7 PASS、整脚本 7/7 PASS、exit 0。

## Handoff notes

**关键决策**

- **只加一条纯词表谓词，不把 triage 整体前移。** ADR 0034 的"情绪门先于意图判定"是刻意的（省一次 triage 与 embedding），整体前移会把它的收益还回去；需要的只是"这句是不是显式喊人"这一个判断。代价是 INTAKE 多一次 0 token 的 `contains` 扫描。
- **复用既有 `USER_REQUESTED` 出口，不新增第二处。** 短路条件是加在情绪门那条 `if` 上的，显式转人工于是照常流到 triage，由 `Intent.ESCALATE` 那个既有出口收口——状态码、`meta`、工单语义全不变，10 状态枚举也没动（ADR 0008 的边界）。反面做法是"在 INTAKE 直接发一个 USER_REQUESTED"，那会让同一语义有两个发源地。
- **情绪判定照常计算，不跳过。** `styleTier` 仍按 `sentiment.emotion()` 选档（URGENT → 安抚档），所以显式转人工被判 URGENT 时话术仍是"先安抚再转接"，与 ADR 0034 的取向一致；trace 里 `sentiment=` 那一步照记。修后 `shoppilot_sentiment_escalated_total{emotion=URGENT}` 仍计 1，证明**只有优先级变了、检测没变**。
- **否决了另外三条候选**（ADR 0042 的 Considered Options 逐条记了理由）：只让 lexicon 判的升级生效会废掉第二层；只改 reason 是错的路径贴对标签；改 prompt 治不了顺序风险。
- **这是回归修复，不是新功能**，故不适用 ADR 0031 的面试触发条件；ADRs 0017 与 0034 的原文一字未改，优先级由新 ADR 0042 定，不再靠执行顺序偶然决定。

**验证落点**

- 回归用例（0 token、0.089 s、确定性）：`GatewayMainPathJvmTest.explicitEscalationSurvivesAnUrgentSentimentVerdict`。**它在修法落地前是红的**——本票不是"把一条既有用例改名"，而是新增第 250 条（`3 + 21 + 249` → `3 + 21 + 250`），收口审计 G6 常数随之换代。
- **变异对照**：摘掉 `&& !triageEngine.isExplicitEscalation(query)` → 该用例当场红（`expected:<USER_REQUESTED> but was:<EMOTION_ESCALATION>`），恢复 → 绿。所以这条不是恒绿假防线。
- 全量：`.\mvnw.cmd -B -ntp verify` → `3 + 21 + 250 = 274` 绿。**注意**：网关在跑时 jar 被占用，`repackage` 会报 `Unable to rename ... .jar.original`，须先 `down.ps1`。
- 活体：`pwsh -NoProfile -File scripts/verify-fallback.ps1` → step 7 `USER_REQUESTED` PASS、7/7 PASS、exit 0（修前同一命令出 `EMOTION_ESCALATION`、`FAILED: USER_REQUESTED`、exit 1）。跑前须把 Ollama 配成两模型同时驻留，否则第二层分类超时 fail-open，step 7 会**假绿**。
- 缝的可信度：`GatewayMainPathJvmTest` 的 `TriageEngine` 是 mock，默认对 `isExplicitEscalation` 返回 false，修法在这条缝上会永远不生效。所以 harness 里显式把该谓词委托给真实现 `T0RuleLayer.explicitEscalation`——这条 stub 是本票能成立的前提。

**你需要能当场回答的三个追问**

1. *Q：为什么不让情绪门只管情绪、显式转人工交给 T0 就行？* A：那正是本票的形状——但顺序上做不到"交给 T0 就行"，因为情绪门在 INTAKE、T0 在 TRIAGE 之后，情绪门先 return 就没有 T0 的机会。所以修的是一条优先级判定，不是把谁挪走。ADR 0017 与 ADR 0034 单看都对，冲突只在"同一句同时命中两者"这一点上。
2. *Q：那 ADR 0034 的动机（不让激动的买家等完全链路）还成立吗？* A：成立，且没被削弱。它管的是**没有显式喊人**的高情绪；本票只把"已经明确喊了人"这一类提前交出去，这类请求本来就不需要情绪门替它做决定。检测侧完全没动，指标可查。
3. *Q：为什么只加一个词表判断，而不是让模型来判优先级？* A：因为 ADR 0017 立这条 T0 词表的理由就是"人要不要来"不该由会抖的外部依赖决定。用模型判优先级等于把刚还回去的承诺再押在一次概率调用上；而 `转人工` 这类词是确定的、0 token 的、可枚举的。

