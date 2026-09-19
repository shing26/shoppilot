# 41 — 工具循环语义钉死：超限 FALLBACK、parallel_tool_calls 防御、轮次上限 JVM 测试

**What to build:** 把轮次用尽的行为对齐 ADR 0008 字面（"超限强制 FALLBACK"），修复并行工具调用的转录协议缺陷，并补上票 11 自陈的"2 轮上限无 JVM 单测"缺口。全部改动集中在 `AgentStateMachine.runModelPath` 与 `OpenAiCompatibleLlmClient` 请求构造两处，0 token 可测（MockLLM 驱动）。依据：2026-09-19 外部审查交叉核实（登记见 round17 spec 附录），用户裁决记录在案。

**Blocked by:** code-review（round17 草稿，fixed point `578722e`）通过。裁决要点：超限语义以 ADR 0008 字面为准（代码从"基于已有事实收尾"改为 FALLBACK）；修复形态取"请求字段 + 防御分支"，拒绝全量派发（违反 ADR 0008/0040 串行预算，也与票 39 的 ≤2 步 Plan 语义冲突）。

**Status:** ready-for-agent（2026-09-19 建）

- [ ] `FallbackReason` 新增 `TOOL_ROUNDS_EXHAUSTED`（第 9 位），`userMessage()` 话术定稿：先说明已基于已查到的事实、再告知转人工，不复述计划、不承诺未执行的动作；复用 ADR 0009 落库与队列反查路径
- [ ] `AgentStateMachine.runModelPath`：轮次用尽分支（现 304-308 行）从"基于已有事实收尾"改为 `fallback(...)`；`shoppilot_tool_round_exhausted_total` 计数保留；`AgentState` 从态取 `TOOL_EXEC`（被截断的业务动作所在状态）
- [ ] `OpenAiCompatibleLlmClient` 请求 payload 增加 `parallel_tool_calls: false`
- [ ] `runModelPath` 防御分支：模型仍返回 >1 个 toolCalls 时只派发并只记录第一个——assistant 转录只含被派发的那个 tool_call，与 tool 响应一一配对；不伪造未执行工具的响应；新增 multi-tool 计数器（进 `RuntimeStateMetrics` 既有四组模式）
- [ ] JVM 测试（`GatewayMainPathJvmTest` 或同层新测试类，MockLLM 驱动，无 Docker/Ollama/ES/Qdrant）：
  - 轮次上限：模型永远要工具 → 断言恰好 2 次派发后终止、结果为 FALLBACK、exhausted 计数走动、无第 3 次派发
  - 超限 FALLBACK：fallback 事件带新 reason、工单号可反查
  - 双 toolCalls 防御：转录 assistant 只含 1 个 tool_call 且配对 1 条 tool 响应、multi-tool 计数 +1
  - payload 断言进 `OpenAiCompatibleLlmClientTest`：请求体含 `parallel_tool_calls:false`
- [ ] 变异对照：临时删掉防御分支或超限 fallback 调用，聚焦用例必须变红，恢复后全绿
- [ ] 联动小改：README 已知限制"2 轮上限无 JVM 单测"一条更新；票 11 补 Handoff 注记（缺口由本票关闭）；`docs/EVIDENCE.md` 224 基线行更新为 224+N
- [ ] 全量 `mvnw verify` 绿（224 + 新增）；`verify_eval_judge.py` 40/40；`git diff --check` 干净

**Verify:**

```powershell
.\mvnw.cmd -B -ntp -pl shoppilot-gateway -am "-Dtest=GatewayMainPathJvmTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -B -ntp verify
python scripts\verify_eval_judge.py
git diff --check
```

预期：聚焦用例全绿；全量三模块 224+N 全绿；量具 40/40。

**验收项**

1. 轮次用尽 → SSE `fallback` 事件带 `TOOL_ROUNDS_EXHAUSTED`，工单落库可按号反查（复用票 33 显式转人工用例的断言思路）。
2. 严格 OpenAI 兼容端点视角：转录中任意 assistant 消息的 tool_call 与后续 tool 响应一一配对，无 400 面。
3. 模型一次要多个工具时，业务事实只来自真实派发的那个工具，转录不出现未执行工具的响应。
4. 已知限制照登：`parallel_tool_calls:false` 是请求方约束，个别忽略该字段的宽松端点仍可能返回多调用——防御分支兜底但 multi-tool 计数会暴露它；Ollama 对该字段的容忍度在下次全量活体验收时确认，不在本票 Verify 内。

## Handoff notes

（收口时补）
