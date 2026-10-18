# 145 反思观测面 + 响应 token 口径回填（R12）

**Status:** implemented（2026-10-19）

**Claimed by:** zcode implement session 2026-10-19（round35 实现轮：145 + 146；144 由并行 session 同日收口）

## What to build

让反思变成可枚举可判的（触发 / 不触发 / 次数用尽），并把响应里的 token 字段补成全量。
可独立验证的行为：一次反思回合的 `done` 帧 `plan[]` 里能读到 `attempt=2` 且 `reflection=true`，
`shoppilot_reflection_total{outcome}` 两条各 +1，trace 有一行小写头 `reflection=...`；
响应 token 字段在该请求的全部 LLM 调用上求和（含入口层），不再是最后一跳或 0。

- `AgentResult.PlanStep` **只增字段**：`attempt`（1-based，按工具出现次数）+ `reflection`（bool）；
  **失败的那一枪不进 `plan[]`**（否则 `eval_task.py` 取第一个同名工具步会把反思后成功判成「没办成」）。
- 新 counter `shoppilot_reflection_total{outcome="triggered"|"exhausted"}`；指标名 55/57 → **56/58**
  （README:402 与 `docs/EVIDENCE.md` 同步）。
- trace 新增**小写头**行 `reflection=<tool> attempt=<n>`（挂 `AgentState.PLAN`；gold 的 `executed_tools`
  只收大写无空格头，写成大写会把工具序列判红）。
- **R12 三处缺口**：① 预算检查后收尾分支复用 `sum(roundReplies)+final`；② `fallback()` 与
  `pendingApprovalAnswer()` 回填已发生轮次；③ 缓存命中路径把入口层调用计入响应字段。
- **SSE 不加新事件**；**`FallbackReason` 不 +1**（README「枚举 10 / 降级 9」/ EVIDENCE / architecture-faq 零同步）。

## Blocked by

[144](144-reflection-branch.md)（先有反思分支，才有可观测对象）。

## 口径

- 依据：[ADR 0069](../../../docs/adr/0069-round35-agent-autonomy-upgrade-reopen-basis.md) §五与 §六；
  139 的字段 / 载体清单是唯一口径；R12 的触发条件 = 「实现轮动观测面时顺手补」（`registered-debt.md`）。
- JVM：反思回合 `reflection=true` / `attempt=2`、非反思回合无此标记；counter `triggered` 与 `exhausted` 各 1 条。
- `eval_task.py` 夹具回归：失败步不进 `plan[]` 的不变量（task_done 不被反转）。
- 公开口径唯一变动 = 指标名计数；`plan_steps_total` 值域说明（`3`/`4`）随 143 一起写进 EVIDENCE。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/eval_suites.py
python scripts/eval_task.py
git diff --check; git status --short
```

## Handoff notes

**关键决策**：

1. **`PlanStep` 只增两个字段、没有默认值兜底**：`attempt`（1-based，含失败的那一枪）与 `reflection`
   （这一步是反思重试）。没有留 4 参便利构造器——留了就可能在某个调用点静默落成 `attempt=1/false`，
   把三态判据悄悄变成恒绿；三个构造点（主循环、网关派生、SSE 用例）全部显式传值。
   `attempt` 的账在「这一枪真的派发了」之后才记（编造拦截 / 缺槽位不计数），**失败的那一枪也算**
   ——否则反思重试成功的那一步会是 `attempt=1`，与票 139 的判据对不上。
2. **counter 两格共用一个指标名**（`shoppilot_reflection_total{outcome}`），不做成两个名字：
   票 139 的判据是「一个名字、两个标签值」，指标名计数只 +1（55/57 → **56/58**）。
   `exhausted` 只在**配额已用掉后的第二次③失败**时 +1；写工具与「没有轮预算」两种情况都不计数
   （前者不在反思范围、后者是预算边界），这样三态表才不会被它们灌成假阳性。
3. **trace 行挂 `AgentState.PLAN`、头**必须小写**——`reflection=<tool> attempt=<n>`**。gold 的
   `executed_tools` 只收「大写且无空格」的头，写成大写会把工具序列判红（`corrective=` 同例）。
   `n` 记的是**这一枪重试的序号**（失败枪 + 1），与 `PlanStep.attempt` 同一个数法。
4. **R12 用一个逐请求的 `TokenUsage` 总账收口**，不保留「最后一跳」的特殊分支：
   入口层（情绪分类）先入账 → 每次 `complete` / `stream` 都入账 → 所有出口（正常 / 降级 / 受理 /
   回放 / 缓存命中 / 追问）统一读总账。三处缺口因此一次全补，且不会出现「修了甲路径漏了乙路径」。
   `SentimentGate.Verdict` 加了两个 token 字段（不调模型的词典层/fail-open 记 0），
   入口层的真实用量第一次能进响应字段。
5. **响应 token 字段仍不是计费口径**（`AgentResult` 的 javadoc 已写死这一条）：part10 的成本列照旧读
   `shoppilot_llm_tokens_total` 增量 / 日预算计数，不读响应字段。

**验证落点**：

- 新增 `ReflectionObservabilityJvmTest`（4 格，0 token）：触发（`attempt=2` + `reflection=true` +
  `triggered=1` + trace 小写行）、不触发（`attempt=1`、两格 counter 都不动、无 trace 行）、
  次数用尽（`exhausted=1` + `TOOL_UNAVAILABLE` + `plan[]` 为空）、写工具不落任何一格。
- 新增 `ResponseTokenFieldsJvmTest`（4 格，0 token）：缓存命中只报入口层（400/30）、
  预算检查后收尾求和（入口 7/1 + 4 轮 10/2 + 收尾 25/5 = 72/14）、降级出口回填、受理出口回填。
- `SseEventSinkTest` 的 `done` 帧断言扩到新字段（`plan[0].attempt=2` / `reflection=true`）——
  **SSE 不加事件**，字段随既有 `plan[]` 出去。
- 全量 `.\mvnw.cmd -B -ntp verify` 四模块 **`10 + 73 + 444 + 45 = 572`** 全绿
  （gateway 436 → 444：+8 即上面两个新类）；`python scripts/eval_suites.py` 与
  `python scripts/eval_task.py` 的 0 token 夹具均绿（失败枪不进 `plan[]` 的不变量未破）。
- 口径：README:402 指标名 55/57 → **56/58**（现场 grep 现算：三模块 56、四模块 58），EVIDENCE 同步；
  `FallbackReason` 不 +1、「枚举 10 / 降级 9」/ architecture-faq 零同步。

**复审修正（2026-10-19，Reviewer 轮）**：

- **F1（真缺陷，已修）**：入口层**调过模型之后**才 fail-open 的三条路（分类正文为空 / 不是 JSON / 标签不认识）此前带回 0/0 的 token——dev 档问一句词典未命中的话、分类器回散文、请求随后命中缓存，响应字段就报 0 而账上已经花掉（正是 R12 要杀的低报形态）。修法：`SentimentGate` 新增 `uncertainPaid(source, reply)`，三条路把这一跳的用量带回；新用例 `paidFailOpensKeepTheirTokenUsage` + `unmodelledVerdictsReportZeroTokens`（**变异对照实测**：把三条路改回 `uncertain(...)` → 前者恰 1 条红）。
- **F3（语义收窄，已修一半、余下如实登记）**：反思印记按「同一工具的下一枪」消费——模型**换工具**重试时，`plan[]` 里没有任何一步带 `reflection=true`（三态仍读作「触发」：counter 与 trace 两处载体都在，`eval_autonomy.py` 的 `attempt_ok` 记**未观测**而不是判负）。已修的是反方向的假印记：反思没回到同一工具时待办作废，同一工具后来一次无关的正常调用不会被标成 `reflection=true`（新用例 `reflectionMarkIsNotCarriedOverToAnUnrelatedCall`）。**边界照登**：换工具重试的「未观测」是刻意的——票 139 的 `attempt` 定义就是「按工具出现次数」，换路那一枪在目标工具上确实不是第 2 次。

**三个现场追问**：

1. 为什么 `attempt` 要把失败针算进去、而 `plan[]` 又不收失败针？——两者服务不同的读者：
   `plan[]` 是「真的办成了什么」（`eval_task.py` 取第一个同名工具步，失败针进来会把反思后成功判成没办成）；
   `attempt` 是「这是第几次尝试」的序号，必须连续才有信息量（成功针读 2 才证明它前面失败过一次）。
2. 为什么 `exhausted` 不在「无轮预算」时 +1？——票 139 的三态是「触发 / 不触发 / **次数用尽**」，
   用尽指配额，不是预算；把预算边界也算进来会让「4 轮排满、一枪都没失败」的回合听起来像反思失败。
3. R12 回填会不会影响缓存命中路径的「零模型调用」承诺？——不会：承诺说的是**不新增调用**，
   回填只是把入口层已经付掉的用量记到字段里；`verify-hit-zero-llm.ps1` 断的计数器增量一个字节没变。
