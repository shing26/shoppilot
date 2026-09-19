# 41 — 工具循环语义钉死：超限 FALLBACK、parallel_tool_calls 防御、轮次上限 JVM 测试

**What to build:** 把轮次用尽的行为对齐 ADR 0008 字面（"超限强制 FALLBACK"），修复并行工具调用的转录协议缺陷，并补上票 11 自陈的"2 轮上限无 JVM 单测"缺口。全部改动集中在 `AgentStateMachine.runModelPath` 与 `OpenAiCompatibleLlmClient` 请求构造两处，0 token 可测（MockLLM 驱动）。依据：2026-09-19 外部审查交叉核实（登记见 round17 spec 附录），用户裁决记录在案。

**Blocked by:** code-review（round17 草稿，fixed point `578722e`）通过——已通过（双轴报告见 `492e9c2` 审查记录，硬违规已在 `aa143ba` 修复）。

**Status:** implemented（2026-09-19；`GatewayMainPathJvmTest` 7/7，全量 JVM `3 + 12 + 213 = 228` 绿，量具 40 PASS，两组变异对照各自变红）

- [x] `FallbackReason` 新增 `TOOL_ROUNDS_EXHAUSTED`（第 9 位），`userMessage()` 话术：先说明已把查到的信息交给人工，不复述计划、不承诺未执行的动作；复用 ADR 0009 落库与队列反查路径
- [x] `AgentStateMachine.runModelPath`：轮次用尽分支改为三走向——写动作未办成 → 直接 FALLBACK（守卫）；预算外规划调用后模型仍要工具 → 按字面 FALLBACK 落工单；不再要工具 → 其正文即最终答案；`shoppilot_tool_round_exhausted_total` 计数保留且口径变准（见 Handoff 追问 3）
- [x] `OpenAiCompatibleLlmClient` 请求 payload 增加 `parallel_tool_calls: false`
- [x] `runModelPath` 防御分支：模型仍返回 >1 个 toolCalls 时只派发并只记录第一个——assistant 转录只含被派发的那个 tool_call，与 tool 响应一一配对；不伪造未执行工具的响应；新增 `shoppilot_llm_multi_tool_calls_total` 计数器（状态机构造器既有模式）
- [x] JVM 测试（`GatewayMainPathJvmTest`，MockLLM 驱动，无 Docker/Ollama/ES/Qdrant）：
  - `toolRoundsExhaustedFallsBackWithTicket`：模型永远要工具 → 恰好 2 次派发 + 3 次 complete（含预算检查）→ FALLBACK 带工单号，exhausted 计数 +1，无流式调用
  - `budgetCheckWithoutFurtherToolNeedAnswersFromThePlan`：两轮链完成且模型不再要工具 → 照常出答案、不落工单（钉住 ADR 0008 Consequences"2 轮覆盖真实链式调用"）
  - `writePendingAtBudgetExhaustionFallsBackWithoutAnotherPlanCall`：写动作轮次内没办成 → 直接 FALLBACK，连预算检查调用都没有
  - `multipleToolCallsInOneReplyDispatchOnlyTheFirst`：双 toolCalls → 只派发第一个、转录 1 对 1 配对、multi-tool 计数 +1
  - payload 断言进 `OpenAiCompatibleLlmClientTest`：请求体含 `parallel_tool_calls:false`
- [x] 变异对照：MUTATION-1 恢复"记录全部 toolCalls" → 多调用用例 BUILD FAILURE；MUTATION-2 短路超限 FALLBACK 分支 → 超限用例 BUILD FAILURE；均恢复后 7/7 绿
- [x] 联动小改：README 已知限制与 CI 门禁段更新为 228；票 11 补追记（口径与缺口说明）；`docs/EVIDENCE.md` 224 基线行更新；tracker README 状态行更新
- [x] 全量 `mvnw verify` 绿（`3 + 12 + 213 = 228`）；`verify_eval_judge.py` 40 PASS；`git diff --check` 干净

**Verify:**

```powershell
.\mvnw.cmd -B -ntp -pl shoppilot-gateway -am "-Dtest=GatewayMainPathJvmTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -B -ntp verify
python scripts\verify_eval_judge.py
git diff --check
```

预期：聚焦用例 `Tests run: 7` 全绿；全量三模块 `3 + 12 + 213 = 228` 全绿；量具 40 PASS。

**验收项**

1. 轮次用尽且模型仍要工具 → 响应带 `fallbackReason=TOOL_ROUNDS_EXHAUSTED` 与工单号（JVM 层已验；活体队列反查归 17 步全量验收）。
2. 严格 OpenAI 兼容端点视角：转录中任意 assistant 消息的 tool_call 与后续 tool 响应一一配对，无 400 面（JVM 断言已钉）。
3. 模型一次要多个工具时，业务事实只来自真实派发的那个工具，转录不出现未执行工具的响应。
4. 已知限制照登：`parallel_tool_calls:false` 是请求方约束，个别忽略该字段的宽松端点仍可能返回多调用——防御分支兜底但 multi-tool 计数会暴露它；Ollama 对该字段的容忍度在下次全量活体验收时确认，不在本票 Verify 内。
5. spec 判据 1 的其余三要素随本票一并验收：请求 payload 断言含 `parallel_tool_calls:false`；multi-tool 计数器可见（状态机构造器注册）；轮次上限 JVM 测试落地（票 11 自陈缺口关闭）。

## Handoff notes

**关键决策**

- **超限的判定形态（本票最重要的一次语义裁决）**：预算耗尽的出口上 `lastReply.wantsTool()` 恒为真（`rounds` 只在模型要工具时递增），若在该点无条件 FALLBACK，会把"先查订单再查物流"这类正常两轮链全部打成转人工——直接违反 ADR 0008 自己的 Consequences（"2 轮覆盖真实链式调用"）与 round16 前的既有验收"两轮工具内出答案"。因此实现取三走向：写动作未办成 → 直接 FALLBACK（关掉外部审查发现的"口头承诺收尾"守卫缺口）；预算外做一次规划调用问模型还要不要工具（这是模型调用、不是第三个工具轮，与纠偏轮同例）→ 仍要工具即"超限"按字面 FALLBACK；不要 → 其正文即最终答案。这是 ADR 0008 全文自洽的唯一读法。
- **并行工具调用取"请求层 + 防御分支"双层**：请求层 `parallel_tool_calls:false` 对诚实端点关掉并行返回；防御分支对忽略该字段的宽松端点兜底——只派发并只记录第一个 toolCall。拒绝全量派发（外部报告的另一选项）：它违反 ADR 0008 串行预算，且与票 39 的 ≤2 步 Plan 语义叠加后更不可控。
- **exhausted 计数口径变准**：旧实现在每次预算出口 +1（正常完成的两轮链也计），ticket 11 写的"这个数就是该扩到 3 轮的证据"在旧口径下不成立；新口径只在真超限或写动作没办成时 +1。

**验证落点**

- 聚焦：`GatewayMainPathJvmTest` `Tests run: 7, Failures: 0`；`OpenAiCompatibleLlmClientTest` 8/8；`FallbackReasonTest` 6/6（既有降级出口不受影响）。
- 全量：`3 + 12 + 213 = 228` 绿（224 + 新增 4）；`verify_eval_judge.py` 40 PASS（评测集 part1-3 不含超限用例，gold 无需动）。
- 变异对照两次：摘防御分支红、摘超限 FALLBACK 红，恢复后全绿——证明四条新用例不是恒绿。

**现场追问**

1. *为什么超限判定要加一次预算外规划调用，而不是预算耗尽直接 FALLBACK？* 见关键决策第一条：预算出口上 `wantsTool()` 恒真，直接 FALLBACK 会把两轮链全部转人工；"超限"只能指"模型在第 3 次规划中仍请求工具"，这需要问一次才知道。该调用与纠偏轮同例——`rounds` 只统计真正执行过的工具（ticket 11 第 11 条既有口径）。
2. *只记录第一个 toolCall，把模型多要的调用从转录里删掉，算不算篡改模型输出？* 转录里保留未执行的 tool_call 才是谎言：严格端点因 tool_call_id 无配对直接 400，宽松端点静默丢结果。诚实形态 = assistant 只带被派发的调用 + 配对 tool 响应 + multi-tool 计数器暴露端点违规；并行请求已在请求层用 `parallel_tool_calls:false` 关掉。
3. *exhausted 计数的口径变了，历史压测读数还能比吗？* 不能直接比。0917 等产物里的读数是旧口径（每次预算出口 +1，含正常两轮链），新口径只数真超限与写动作未办成；对比时要换算或重测。EVIDENCE 纪律下这是"指标口径演进"，不是改分母——旧产物保留原样，新读数从本票起算。
