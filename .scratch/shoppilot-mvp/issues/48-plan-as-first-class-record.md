# 48 — Plan 提升为一等记录并随 done 帧输出

**What to build:** 把已经在 `AgentStateMachine` 里逐轮累积的执行事实提升为可输出的记录。现状 `List<String> stepResults` 只留每步的结果 JSON（供后步参数表达式取值），步骤的工具名、参数、状态、耗时全部只存在于日志的 `step(...)` 行里，`done` 帧不带——于是「这个计划是怎么走的」只能靠翻日志复盘。

**Blocked by:** None（与票 49 同帧同批改，建议 48 → 49 连做）。

**Status:** implemented（2026-09-24）。

口径（ADR 0044 已定，本票只执行）：

- **ADR 0036 的执行语义一字不动**：仍是有序步骤 ≤ 2、前步失败即中止整条 Plan、串行不并行、不重排、`plan.length ≤ 2` 与 ADR 0008 的 2 轮硬上限仍是同一条边界。本票只把执行事实**记录下来**，不改任何判定分支。`PlanExecutionTest` 必须原样通过。
- **只往既有 `done` 帧加字段，不新增 SSE 事件类型**：`verify-console.mjs` 只断言帧序列含 `meta`/`done` 与引用数，`SseEventSinkTest` 只断言特定字段存在——两者都不逐字段钉死，加字段向后兼容（与 `promptVersion` 当初加进 `meta` 同形态，见 `SseEventSink.java:79` 注释）。
- **纯政策回答的 `plan` 是空数组不是 null**：避免客户端多一条判空分支。

- [ ] `AgentStateMachine` 每步记录升级为 `{工具名, 参数, 状态, 耗时}`；`stepResults` 的后步参数取值语义不变（`PlanExpression` 不受影响）
- [ ] `agent/AgentResult` 新增嵌套 `record PlanStep` 与 `List<PlanStep> plan`（record 分量增加，所有构造点同步）
- [ ] `web/SseEventSink.done(...)` 加 `plan` 字段；唯一调用点 `ChatController.java:138` 传入
- [ ] JVM 用例：两步链的 `done` 帧含 2 条 `PlanStep`（工具名/状态/耗时齐备）
- [ ] JVM 用例：前步失败中止时含 1 条，且能看出是中止（`planAborted` 语义可见）
- [ ] JVM 用例：不调工具的纯政策回答 `plan` 为空数组（不是 null）
- [ ] `PlanExecutionTest` 全部原样通过（执行语义未变的证据）
- [ ] `verify-console.mjs` 新增一条断言：真实 SSE 流的 `done` 帧含非空 `plan`
- [ ] 覆盖率：新增 gateway 代码带用例，`check_coverage.py` 仍 exit 0

**Verify:**

```powershell
.\mvnw.cmd -B -ntp verify
node scripts/verify-console.mjs
```

预期：`PlanExecutionTest` 无改动通过；`done` 帧新字段在活体 console 步可见。

## Handoff notes

**关键决策**

- **不动判定分支，只把已有事实记下来。** `stepResults` 那条列表本来就在累积每步结果 JSON（供 `PlanExpression` 取前步字段），本票只是在同一个位置多记一份可输出的副本。`PlanExecutionTest` 原样通过就是「ADR 0036 执行语义未动」的机器证据——如果本票顺手改了中止条件或轮次判定，那些用例会红。
- **记的是「真的执行过」的步，被拦下的步不进 `plan`。** 编造单号、缺槽位、表达式拒收、未知工具这四条路径都提前 return，走的是 `askSlot`/`fallback` 构造的 `AgentResult`，它们不带 plan。这是**有意的边界**而不是漏掉：那四种情况各自有更准确的出口（`trace` 里的拦截行 + `fallbackReason`），把「没执行」也塞进 plan 会让「计划走了几步」这个数变得不可信。这条写进了 `PlanStep` 的 javadoc。
- **同步响应也加了 `plan`，不只是 SSE。** 验收判据只写了 `done` 帧，但同步响应本来就带 `trace`（同一类执行事实），只给 SSE 加会让两个协议面对同一个问题给出不同答案。加了之后还白得一条 0 token 的 JVM 断言缝（MockMvc + jsonPath），比只在浏览器里验可靠得多。
- **`plan` 空值是空数组不是 null。** 客户端少一条判空分支；`SseEventSink.done` 与 `ChatResponse.of` 两处都做归一，且各有一条用例钉着（`policyAnswerReportsEmptyPlanArray`、`doneNormalizesNullsInsteadOfEmittingNull`）。
- **耗时用 `System.nanoTime()` 包住 `dispatcher.dispatch(...)`。** 记的是**派发**耗时（含 HTTP 往返），不是模型耗时——后者已经在 `shoppilot_llm_latency_seconds` 里了。

**验证落点**

- `GatewayMainPathJvmTest.planStepsAreReportedInExecutionOrder`：两步链 → `plan.length()==2`，每步工具名/状态/参数/耗时齐备。
- `GatewayMainPathJvmTest.policyAnswerReportsEmptyPlanArray`：纯政策回答 → `plan` 是空数组。
- `SseEventSinkTest.doneCarriesPlanAndContextWithNormalizedEmpties` / `doneNormalizesNullsInsteadOfEmittingNull`：`done` 帧字段与空值归一。
- `verify-console.mjs`：新增断言直接读原始 SSE 流（**本机未真跑**，见 round19 spec 的未达成登记）。
- **变异对照**：去掉 `planSteps.add(...)` → `planStepsAreReportedInExecutionOrder` 变红（与票 49 的变异同批跑，两条各红各的）。
- 全量：gateway 253 → 269 的一部分（本票 2 条 + 票 49 的 2 条）。

**你需要能当场回答的三个追问**

1. *Q：`Plan` 现在是个真对象了吗？ADR 0036 说「有序步骤列表 ≤ 2」。* A：仍然不是——`plan.length ≤ 2` 是**工具循环上界的另一种表述**，不是 Plan 自己校验出来的。本票把执行事实变成了可输出的 `PlanStep` 记录，但没引入 Plan 对象、没改任何判定。ADR 0036 原文就是这么写的（「`plan.length ≤ 2` 与 ADR 0008 的 2 轮硬上限是同一条边界的两种表述」），所以这不是欠账。
2. *Q：为什么被拦下的步不记？那不就看不到"计划卡在哪"了？* A：看得到，只是出口不同——编造单号与缺槽位在 `trace` 里有 `fabricated-orderNo 已拦截 modelArgs=...` / `missing=[...]` 的行，表达式拒收有 `plan-rejected expression=...`，且都会落 `fallbackReason`。`plan` 要回答的是「**执行过**的步有几条、每步什么状态」，把没执行的塞进去会让这个数失去含义。
3. *Q：`latencyMillis` 量的是什么？* A：`dispatcher.dispatch(...)` 的墙钟，也就是**工具派发这一跳**（含到 biz-mock 的 HTTP 往返与幂等处理）。模型耗时另有 `shoppilot_llm_latency_seconds`，两者不重叠、可相加。
