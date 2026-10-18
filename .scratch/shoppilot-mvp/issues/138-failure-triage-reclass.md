# 138 失败三分法按当前 `ToolStatus` 全量重列

**Label:** wayfinder:grilling　**Status:** implemented（决策票：映射表 + 可判定规则，零代码改动）　（map：[135](135-wayfinder-agent-autonomy-map.md)）

**Claimed by:** zcode wayfinder session 2026-10-18（第二张：138）

## Question

spec 决策 5 的失败三分法（①安全闸→原出口 / ②确定性业务失败→照旧中止 / ③操作性失败→才反思换路）
要按当前 `ToolStatus` **全量重列每一格**：六个成员各归哪一类，边界用什么 JVM 用例钉死（含变异对照）。

## 已核实的事实（不必重查）

- `ToolStatus` 当前 6 成员：`OK / NOT_FOUND / STATE_NOT_ALLOWED / PENDING_APPROVAL / IDEMPOTENT_REPLAY / TIMEOUT`
  （`shoppilot-tool-api/.../view/ToolStatus.java`）。
- gold 的 plan 判据是**前缀匹配**（`eval_suites.py:103`）：`PLN-ABORT-*` 的形态靠 `abort_ok`
  （details 前缀 `plan-aborted`）与 `rejected_ok`（`plan-rejected`）——**②类照旧中止是保住这两条的键**。
- spec 决策 5 的原文要求：分类边界漂移会打 gold → JVM 钉死三分法每一格（变异对照）。
- 与之相邻、必须在票内划界的既有语义：写动作守卫（票 41 的纠偏一次）、空召回出口（循环外）、
  审批闸（受理非失败）——它们的清单来自 [137](137-safety-gates-non-retryable.md)。

## 口径

- 本票是**决策票**：产出「成员 → 类别 → 出口/中止/反思」的映射表 + 每格要钉的 JVM 用例名（草）。
- 「反思最多一次、同工具同型失败第二次按原语义收口」（spec 决策 4）在本票写成可判定的规则（触发/不触发/次数上限），
  不许判「看起来聪明」。

## Blocked by

[137](137-safety-gates-non-retryable.md)（安全闸清单先定，才能归类）。

## Verify

```powershell
git diff --check && git status --short
```

## Resolution（2026-10-18，grilling 三问均未获回答——全部按本票建议值记录并逐条标「建议」；决策票，零代码改动）

**会话记录**：三问（映射表结构 / 反思次数上限 / 写工具是否可反思）均未获回答；按建议值记录，
所有者可在 [142](142-round-scoping-and-adr-0069.md) 落 ADR 0069 前修正（137 的先例：未获答项同样按建议值记录并标明）。

**判据一句话**：反思只在工具循环内、只作用于**③操作性失败**；①闸走原出口、②照旧中止、⓪非失败不参与。

**事实修正（票面「6 成员」过时）**：`ToolStatus` 实为 **7 成员**（含 `UNAVAILABLE`，见 [137](137-safety-gates-non-retryable.md)）；
另更正一处：gold 里 `PLN-ABORT-01`（NOT_FOUND）是**严格中止**（`abortAfterStep0: true`），
`PLN-ABORT-02`（STATE_NOT_ALLOWED）是 `mayAbortAfterStep0`（判据记为**未观测**，`abort_ok = None`）。

### 一、映射表（7 成员全量；类别栏 = 建议值）

| 成员 | 类别 | 现行为 | 反思 | 钉的 JVM 用例（草） |
| --- | --- | --- | --- | --- |
| `OK` | ⓪非失败 | 结果回填，循环继续 | 不 | （既有「工具循环」用例覆盖） |
| `NOT_FOUND` | ①闸（归属拒绝） | `failedStep` → `plan-aborted` → 模型组织人话 | 不 | `notFoundAbortsPlanWithoutReflection` |
| `STATE_NOT_ALLOWED` | ①闸（终态拒绝） | 同上 | 不 | `stateNotAllowedAbortsPlanWithoutReflection` |
| `PENDING_APPROVAL` | ⓪非失败终态（受理） | 受理即收尾（不打第二跳模型） | 不 | `pendingApprovalNeverReflects`（既有受理用例加断言） |
| `IDEMPOTENT_REPLAY` | ①闸（幂等回放） | 回放结果回填，循环继续（不算失败） | 不（且不重执行） | `idempotentReplayContinuesWithoutReflection` |
| `TIMEOUT` | ③操作性 | `degraded()` → 立即 FALLBACK 落工单 | **是（新）** | `operationalTimeoutReflectsOnceThenSucceeds` |
| `UNAVAILABLE` | ③操作性（含熔断打开/锁忙/连接失败/格式异常/序列化） | 同上 | **是（新）** | `secondOperationalFailureOfSameToolFallsBack` |

**②类为空（判据保留，建议）**：spec 决策 5 的两个例子（`NOT_FOUND`/`STATE_NOT_ALLOWED`）已由 137 重锚为①闸——
出口相同（照旧中止），理由以安全语义为准；本仓当前没有「非闸的确定性业务拒绝」成员，**不为它造成员**，
判据留给未来成员（新增 `ToolStatus` 时按此表补栏）。

### 二、反思的可判定规则（触发 / 不触发 / 次数上限；除注明外为建议值）

- **触发**：工具调用返回 `TIMEOUT` / `UNAVAILABLE`，且**是读工具**，且该工具的③类反思次数 < 1，且还有轮预算。
- **动作**：把失败事实回填 messages + 一条失败上下文说明 → `continue` 进下一轮（模型带失败上下文重规划）——
  **占用 4 轮预算、不加轮次**（[136](136-autonomy-budget-and-steps.md)）；失败的那一枪不进 `stepResults`/`plan[]`（沿用现语义）。
- **次数上限（建议：按工具合计）**：键 = `ToolName`；同一工具的任何③类失败**合计上限 1 次**
  （「同型」读作「同属③类」）。第二次——无论 TIMEOUT 还是 UNAVAILABLE——**按原语义收口** =
  `degraded()` 分支的 fallback（`TOOL_UNAVAILABLE` + 落工单）。更严、更简单、更难绕。
- **写工具不反思（建议）**：`applyRefund`/`modifyDeliveryAddress` 的③失败照旧 fallback + 工单。
  理由：结果不明时自动重试资金动作风险大于收益；**改参重试绕开两层幂等**（派生 token 含参数，新参 = 新动作）。
  同参重试虽被两层幂等兜住（确定性派生 token + biz-mock token 记录），但反思不保证同参——不赌。
- **不触发**：①闸与⓪照旧；**模型输出层错误**（未知工具、计划表达式拒收）照旧 fail-closed 兜底、不反思；
  **LLM 失败**（`LLM_TIMEOUT`/`LLM_CIRCUIT_OPEN`/`LLM_BUDGET_EXCEEDED`）不反思、映射照旧。
- **反思不得绕闸**：反思后的重试仍过全部闸（编造拦截/幂等/审批/归属/终态/行业门）——闸判定先于反思，
  反思不改变任何闸的判定；反思重试撞闸 → 走该闸的原出口、不再反思。
- **挂点**：`AgentStateMachine` 的 `degraded()` 分支（:395）；网关派生（:488）与槽位续办（:714）同规则（都只可能是读工具）。
- **变异对照（用例必须真能红）**：抽掉 :395 的反思分支 → `operationalTimeoutReflectsOnceThenSucceeds` 红；
  把闸判定挪到反思之后 → `reflectionRetryHittingGateAbortsWithoutSecondReflection` 红。

### 三、给 [140](140-eval-suite-and-dev-budget.md) 的输入

- 新评测集要能判**三态**：触发 / 不触发 / 次数用尽——可枚举标记（载体由 [139](139-reflection-observability-and-enum.md) 定）。
- 超限收口分区按 4 轮 + 反思占轮设计（136 已定）。
- gold 面：①/②照旧中止是 `PLN-ABORT-*` 的键；反思只在③——**live gold 重跑预期零影响**（③不在 gold 计划链里）。

### 四、边界说明（相邻机制）

- 写动作守卫（票 41）：纠偏治「没做」、不占轮；反思治「做了但失败」、占轮——见 137 第四节。
- 空召回出口（循环外）、审批闸（非失败终态）：本票不碰。
- `TOOL_ROUNDS_EXHAUSTED`：轮尽收口语义不变（136）；反思重试计入轮预算，「反思后轮尽」仍走同一出口。

## Handoff notes

**关键决策**：见 Resolution 一、二节。一句话：**7 成员四栏**（①闸 3 / ②空 / ③操作性 2 / ⓪非失败 2）+
反思规则六条（触发 / 动作 / 按工具合计 1 次 / 写工具不反思 / 不触发面 / 不得绕闸）。**三问均未获答，
全部按建议值记录**——修正入口在 142。

**验证落点**：决策票，文档级验证 = `git diff --check` + `git status --short`。机器落点留给实现轮：
Resolution 一节每行的 JVM 用例名（草）即用例清单；变异对照两条见二节；gold / 判据面零触碰。

**三个现场追问**：

1. 反思重试失败的那一枪算不算「第二次」？——算。计数键是 `ToolName`，不看 status：同一工具第二次③类失败
   （无论哪种）一律收口。跨工具不累计（各自独立）。
2. 反思会不会打乱 plan 的前缀判据？——不会：失败枪不进 `plan[]`（沿用现语义），反思重试成功的那一枪照常进；
   且①/②类不反思，`PLN-ABORT-*` 的 `plan-aborted` 形态原样保住。
3. 反思与「写动作轮次内没办成」（票 41 守卫）会不会打架？——不会：守卫管的是「没做」（不占轮、上限 1 次），
   反思管的是「做了但失败」（占轮）；写工具不反思，两者不重叠。
