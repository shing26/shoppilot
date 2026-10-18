# 136 自主度数字重锚：4 轮 / 4 步是否维持

**Label:** wayfinder:grilling　**Status:** implemented（决策票：裁定 + 登记，零代码改动）　（map：[135](135-wayfinder-agent-autonomy-map.md)）

**Claimed by:** zcode wayfinder session 2026-10-18

## Question

工具循环 **2 → 4** 轮、计划 **≤2 → ≤4** 步这两个数字，在当前结构下**维持 4/4**，还是调整
（如 3/3、或轮与步分开取值）？以及它们如何进可配置面（env 变量名、默认值、与 ADR 0012 家法一致）。

## 已核实的事实（不必重查）

- ADR 0008：工具循环硬上限 2；ADR 0036：Plan ≤2，且**步骤数不是独立校验，就是工具循环的上界**——
  覆盖这两个数字需要新 ADR 写覆盖依据 + 旧 ADR 原文不动加换代指针（ADR 0043 先例）。
- 配置面：`GatewayProperties.Agent(maxToolRounds, maxSlotAsks, sessionTtl, historyTurns)`；
  `PlanExpression` 支持 `{steps[i].result.<field>}` 引用前步结果。
- 空召回确定性出口（ADR 0068）在**工具循环之前**，不占轮次；审批闸 `PENDING_APPROVAL` 不是失败。
- local 3B 出不了两步链（`plansteps` 步 0/7，已登记）；4 步只会更红——**照登，判据不动**（round20 家法）。
- gold 的 plan 判据是**前缀匹配**（`eval_suites.py:103` `tools[:len(expect["plan"])] == expect["plan"]`）——
  计划变长不破，中途插重试会破。

## 口径

- 本票是**决策票**：产出数字、env 名与默认值、以及「为什么是这个数」的理由；不写实现代码。
- 决策要考虑：超限照旧落 `TOOL_ROUNDS_EXHAUSTED` 工单（ADR 0008 的出口语义不变）。

## Blocked by

无（frontier）。

## Verify

```powershell
git diff --check && git status --short
```

## Resolution（2026-10-18，grilling 四问 + 所有者确认；决策票，零代码改动）

**① 数字：维持 4 轮 / 4 步——只动一个数。** `shoppilot.agent.max-tool-rounds` 2 → **4**；计划步数没有独立的数，
自动成为 ≤4：当前结构里「步」与「轮」同源（`AgentStateMachine` 每轮执行一个工具调用，`plan_steps_total{steps=rounds}`），
ADR 0036 的「同一边界的两种表述」照旧成立，**不引入第二个校验面**（分离派被否：与 ADR 0036 直接相撞且无产品理由）。

**② 可配置面：`${SHOPPILOT_AGENT_MAX_TOOL_ROUNDS:4}`。** yml 默认即新行为（JVM / 活体 / 矩阵 / 评测四面一致）；
env 只作调档手段（ADR 0012 家法：要改的东西走环境变量）。落地四件：yml 占位符；
`ConfigValidationTest.CONFIG_PLACEHOLDERS` 清单登记（`applicationPlaceholderSetIsPinned` 逐字钉死——开面必须登记、不绕门禁，
同 `SHOPPILOT_TICKET_URL` 先例）；`@Min(1)` 保留；`.env.example` 照 `SHOPPILOT_LLM_DAILY_TOKEN_BUDGET` 先例注释登记一行。

**③ 覆盖依据（交 142 落 ADR 0069）：三账结构。**

- **能力账**：4 = 三步链（ADR 0008 里被截断的「查订单→查物流→改地址」）+ 1 反思余量
  （spec 决策 4 要求反思占轮预算，不留格它就没位置）；
- **安全账**：安全职责已由每步守卫（归属 / 溯源 `isUntrustedOrderNo` / 幂等 / 审批闸）与循环外确定性出口
  （限流 / 角色拒 / 空召回 ADR 0068）承担，与循环长度解耦——ADR 0036「长链 = 用户诉求不聚焦」的担忧由槽位追问与这些出口接管；
- **有界性账**：4 仍是硬上限，超限照旧 `TOOL_ROUNDS_EXHAUSTED` 落工单，最坏模型调用有界
  （≤4 工具轮 + 预算检查 1 次 + 纠偏 1 次）——ADR 0008 的「可预算」论证格式沿用、数字换代；
- **归类：所有者政策覆盖**（round23 共识 2026-10-01），照 ADR 0045 写法一条条写明依据，不伪装成事实性修正；
  ADR 0008/0036 原文不动，加换代指针。

**④ 档位：全档统一 4。** 不给 local 单独降档（gateway 现无 profile yml；降档会引入本仓首个档位差异，且与「照登」约定重复）；
local 3B 的 `plansteps` 更红、耗时增长按 map 常设约束**照登、判据不动**。

### 下游影响（各票 / 实现轮对照）

- **139（观测面）**：`shoppilot_plan_steps_total` 的 `steps` 标签值域将出现 `"3"/"4"`（现为 1/2/aborted/rejected）。
- **141（提示词）**：预算说明从「2 轮」换为 4 轮 / 4 步。
- **140（评测集）**：超限收口分区按 4 轮设计；dev 预算按实测抬。
- **138（失败三分法）**：反思占预算后与「轮尽」的交互在票内钉死。
- **142（ADR 0069）**：三账落稿 + 0008/0036 换代指针。
- **实现轮**：`GatewayMainPathJvmTest` 两个 `TOOL_ROUNDS_EXHAUSTED` 用例按默认 2 写死（stub 3 回复 / `times(2)` 派发 /
  `times(3)` 规划），配置换代时同步——语义（超限 → 落工单）不变，改的是被钉的数字；
  `verify-plan.ps1` 读 `steps="2"` 不受影响（两步链仍记 2）。

## Handoff notes

**关键决策**：见 Resolution ①②③④。一句话：**只动一个数**——`max-tool-rounds` 2 → 4（计划步数随之 ≤4），
默认进 yml、env 可覆盖；覆盖依据按三账写、归类所有者政策覆盖；全档统一，不降档。

**验证落点**：决策票，文档级验证 = `git diff --check` + `git status --short`。数字的机器落点留给实现轮：
`ConfigValidationTest` 占位符清单（新增即红）、默认值 4 的 JVM 断言、超限用例按 4 轮重钉；gold / 前缀判据零触碰。

**三个现场追问**：

1. 「4 轮 / 4 步」是不是两个旋钮？——不是，一个：`maxToolRounds`；`plan[]` 步数与之同源。
   谁要单独限计划长度，是新决策，回 142 记录，不得在实现里私加校验。
2. local 档为什么也吃 4、`plansteps` 更红怎么办？——全档统一是所有者裁定；local 3B 能力上限照登（round20 家法），判据一字不动。
3. 4 格预算里反思怎么分？——本票只定总数；反思的触发条件、同工具同型一次上限、与轮尽的交互由 138 钉死，实现轮不得自创分配。
