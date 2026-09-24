# 49 — done 帧携带上下文组成

**What to build:** 让一次回答「用了什么上下文」可被外部观察。现状 `composeUserMessage`（`AgentStateMachine:701-713`）把政策条款拼进 Prompt、`appendHistory`（`:723-730`）注入历史，但两者都不回传用了哪些条款、几轮历史、估算多少 token——`done` 帧只有 `citations` 与 `usage`，`usage` 是模型侧回报的真实 token，回答不了「注入的条款是哪几条」。

**Blocked by:** 48（同一帧、同批改；两票合起来只动 `AgentResult` 与 `SseEventSink.done` 一次）。

**Status:** implemented（2026-09-24）。

口径（ADR 0044 已定，本票只执行）：

- **纯观测，不改注入内容**：`composeUserMessage` 产出的 Prompt 文本必须**逐字节不变**（加断言钉住）。本票只把「注入了什么」记下来，不改注入策略——输入侧裁剪是登记项（round19 spec 登记节第 7 项），不在本轮。
- **历史轮数与配置同源**：报告值必须来自 `historyTurns` 配置，不另算一份，否则两处会漂移。
- **`ruleIds` 与 `citations` 一致**：同一份数据两个出口，不一致即为缺陷（用例钉住）。

- [ ] `agent/AgentResult` 新增 `ContextComposition`（条款 `ruleId` 列表、历史轮数、估算 prompt token）
- [ ] `done` 帧加 `context` 字段（与票 48 的 `plan` 同批改）
- [ ] 断言 `composeUserMessage` 产出的 Prompt 文本逐字节不变（本票「不改注入内容」的机器证据）
- [ ] JVM 用例：命中政策检索时 `context.ruleIds` 与 `citations` 一致
- [ ] JVM 用例：零召回时 `ruleIds` 为空数组，且 `composeUserMessage` 仍输出「（本轮未检索到相关条款）」
- [ ] JVM 用例：6 轮窗口满时历史轮数报告值 = `historyTurns` 配置值
- [ ] 变异对照：把 `context.ruleIds` 改成写死空数组 → 「与 citations 一致」那条用例变红
- [ ] 覆盖率：新增 gateway 代码带用例，`check_coverage.py` 仍 exit 0

**Verify:**

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/check_coverage.py
```

## Handoff notes

**关键决策**

- **`ruleIds` 与 `citations` 用同一份数据、同一个顺序。** `contextComposition` 里的 `ruleIds` 与收尾阶段算 `citations` 的那行是同一个 `retrieved.rules().stream().map(ruleId)` 表达式。这不是巧合而是设计：两个出口不一致就是缺陷，所以用例直接断言它们相等（`contextCompositionMirrorsCitationsAndHistory`），而不是各测各的。
- **`historyTurns` 数的是「实际注入的用户轮」，不是配置值。** 配置（`agent.history-turns`）决定窗口大小，但**真实注入了几条**由 `SessionStore` 的滑窗决定。报告前者等于报配置，报后者才叫观测——所以实现是遍历 `session.turns()` 数 `role=="user"`，用例注入 3 条消息（2 user + 1 assistant）断言报告 2。
- **`estimatedPromptTokens` 用显式启发式，并说清它不是计费口径。** 中日韩字符 1 token/字、其余 4 字符/token，写成一个带 javadoc 的 `estimateTokens`。**没有引分词器**——为一个观测字段增加依赖面不符合本轮「旁挂」的定义；要精确值就得用模型回报的 `promptTokens`（`usage` 里已有），那才是计费口径。两者在 `done` 帧里并列存在，读者能一眼看出差别。
- **「Prompt 逐字节不变」做成机器断言，不是口头承诺。** `zeroRecallKeepsEmptyRuleIdsAndThePlaceholder` 断言零召回时 user 消息仍以 `【政策条款】\n（本轮未检索到相关条款）\n\n【买家问题】\n` 开头。这是本票唯一可能出事的地方（观测代码顺手改了注入内容），所以必须有一条会红的用例守着。
- **同步响应同形加了 `context`**（理由同票 48：两个协议面不该对同一问题给不同答案）。

**验证落点**

- `GatewayMainPathJvmTest.contextCompositionMirrorsCitationsAndHistory`：注入 2 条规则块 + 3 条历史消息 → `citations` 与 `context.ruleIds` 各 2 条且同序、`historyTurns==2`、`estimatedPromptTokens` 是数字。
- `GatewayMainPathJvmTest.zeroRecallKeepsEmptyRuleIdsAndThePlaceholder`：零召回 → `ruleIds` 空数组 + Prompt 占位文本逐字保留。
- `SseEventSinkTest` 两条：`done` 帧的 `context` 字段与空值归一。
- **变异对照**：把 `ruleIds` 写死 `List.of()` → `contextCompositionMirrorsCitationsAndHistory` 变红。
- 全量：gateway 253 → 269 的一部分。

**你需要能当场回答的三个追问**

1. *Q：`estimatedPromptTokens` 和 `usage.promptTokens` 有什么区别？* A：前者是**我按字符数粗估的注入体积**（中日韩 1 token/字、其余 4 字符/token），后者是**模型回报的计费真值**。两者并列放在 `done` 帧里，正是为了让读者看出估算偏差有多大——而不是让一个估算值冒充计费值。
2. *Q：加了观测字段，Prompt 会变吗？* A：不会，而且有机器证据：零召回那条用例断言 user 消息的占位文本逐字不变。观测代码只读 `messages`、不改它；`contextComposition` 是个纯函数。
3. *Q：为什么 `ruleIds` 不单独算一遍？* A：因为它和 `citations` 是同一件事的两种呈现（「注入了哪些条款」/「答案引用了哪些条款」）。各算各的就会出现两个数漂移而没人发现；同源同序 + 一条相等断言把这个问题从「可能发生」变成「不可能编译过不了用例」。
