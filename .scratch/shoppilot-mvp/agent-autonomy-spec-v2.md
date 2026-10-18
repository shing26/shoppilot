# agent 自主性升级 spec v2：动态规划 + 反思重试（重锚定稿）

> 状态：**定稿（2026-10-19）**。取代 v1（[`agent-autonomy-spec.md`](agent-autonomy-spec.md)，六决策原文保留、加换代指针）。
> 本文件同时是 **round35 的 spec**；开轮依据 = [`docs/adr/0069-round35-agent-autonomy-upgrade-reopen-basis.md`](../../docs/adr/0069-round35-agent-autonomy-upgrade-reopen-basis.md)。
> **开轮已获批准（2026-10-19，所有者点头）**：实现票 143-150 已建（`ready-for-agent`，见 §4）；
> 本 spec 与 ADR 0069 自即日起是 round35 的依据，执行按本仓「一次一票」纪律走。
> v1 → v2 的三处换代：① 落点从三服务前结构重锚到当前四服务结构（`Domain` 行业门 / 审批闸 / 空召回出口 / part4-10 评测体系）；
> ② 七张调查票 136-142 的裁定全部吸收（本文即收敛产物）；③ 计划轮廓的失效项换代（「ADR 0052」→0069、「票号 69 起」→143 起、「round23」→round35）。

## 0. 一句话

工具循环 2 → **4 轮**、计划 ≤2 → **≤4 步**（**一个旋钮**：`max-tool-rounds`；步数与轮同源），
读工具遇 **③操作性失败**（`TIMEOUT`/`UNAVAILABLE`）时由系统发起 **一次反思重试**（占轮、按工具合计 1 次、
写工具不反思、不得绕闸），全部动作可枚举可判（`plan[]` 字段 + counter + trace 行）；
能力判据用 **dev 档 30 条新评测集（part10）单独成列**，gold 180 条 / 阈值 / 分母 / `judge()` **零触碰**。

## 1. 六决策重锚（v1 §1 原文 → 结论）

| # | v1 原文要点（2026-10-01） | 重锚结论（2026-10-19） | 依据 |
| --- | --- | --- | --- |
| 1 | 靶子：动态规划 + 反思重试进 scope；跨会话记忆、知识反向沉淀继续登记不动 | 不变；两者留 map 135 的 Out of scope，round19 登记第 3/8 项触发线不变 | 135 |
| 2 | 证明档位：机制层 JVM 桩 0 token；dev 档约 30 条新集（不重跑 180 gold）；日预算按实测抬、不废 ADR 0012 | 维持 + 实测修正：part10 / `kind: autonomy` / 30 条 = 15-10-5；单跑 ≈ **29 万 token**（每条 2.0–11.5k、均值 ≈9.6k）；预算 260000 → **600000** | 140 |
| 3 | 自主度：2→4 轮、≤2→≤4 步、进可配置面；超限 `TOOL_ROUNDS_EXHAUSTED`；覆盖 ADR 0008/0036 | 维持 4/4、**只动一个数**（见 §2.1）；三账覆盖依据见 ADR 0069 §三 | 136 |
| 4 | 反思边界：安全闸四类不可重试；反思占轮；同工具同型一次；第二次原语义收口 | 闸清单重列为**三段式 6 闸**（见 §2.3）；反思只在工具循环内、只作用于③ | 137、138 |
| 5 | 失败三分法（gold 零触碰）：①闸/②确定性/③操作性 | 按 `ToolStatus` **7 成员四栏**（见 §2.2）；②类为空、判据保留 | 138 |
| 6 | 分轮：round23 只做 agent 升级；三条真缺陷维持登记 | 轮号 = **round35**；三条真缺陷维持登记（退款读回触发线接近成立，收掉与否独立裁定） | 140 |

## 2. 机制契约

### 2.1 配置面（票 143）

- **一个旋钮**：`shoppilot.agent.max-tool-rounds`（`GatewayProperties.Agent.maxToolRounds`，`@Min(1)` 保留）
  `2 → 4`；默认值进 yml：`${SHOPPILOT_AGENT_MAX_TOOL_ROUNDS:4}`（ADR 0012 家法：要改的东西走环境变量，env 只作调档）。
- **计划步数没有独立的数**：当前结构里「步」与「轮」同源（每轮执行一个工具调用，
  `plan_steps_total{steps=rounds}`），ADR 0036 的「同一条边界的两种表述」照旧成立——**不设第二个校验面**。
- **落地四件**：yml 占位符；`ConfigValidationTest` 占位符钉死清单登记（开面必须登记、不绕门禁）；
  `.env.example` 注释登记一行；`GatewayMainPathJvmTest` 两个 `TOOL_ROUNDS_EXHAUSTED` 用例按默认 4 重钉
  （stub 5 回复 / `times(4)` 派发 / `times(5)` 规划；语义不变）。
- **全档统一 4、不降档**；local 3B 的 `plansteps` 更红**照登、判据不动**（round20 家法）。
- 超限照旧：预算检查后模型仍要工具 → `TOOL_ROUNDS_EXHAUSTED` 落工单（ADR 0008 出口语义不变）。

### 2.2 失败三分法与反思规则（票 144）

**7 成员四栏**（`ToolStatus`，票 137 代码核实：7 成员含 `UNAVAILABLE`）：

| 成员 | 类别 | 现行为 | 反思 |
| --- | --- | --- | --- |
| `OK` | ⓪非失败 | 结果回填，循环继续 | 不 |
| `NOT_FOUND` | ①闸（归属拒绝） | `failedStep` → `plan-aborted` → 模型组织人话 | 不 |
| `STATE_NOT_ALLOWED` | ①闸（终态拒绝） | 同上 | 不 |
| `PENDING_APPROVAL` | ⓪非失败终态（受理） | 受理即收尾（不打第二跳模型） | 不 |
| `IDEMPOTENT_REPLAY` | ①闸（幂等回放） | 回放结果回填，循环继续（不算失败） | 不（且不重执行） |
| `TIMEOUT` | ③操作性 | 现为 `degraded()` → 立即 FALLBACK 落工单 | **是（新）** |
| `UNAVAILABLE` | ③操作性（含熔断打开/锁忙/连接失败/格式异常/序列化） | 同上 | **是（新）** |

**②类为空**（判据保留）：当前没有「非闸的确定性业务拒绝」成员；未来新增 `ToolStatus` 时按本表补栏。

**反思规则六条**（可判定；JVM 用例钉死，变异对照两条）：

1. **触发**：读工具返回 `TIMEOUT`/`UNAVAILABLE`，且该工具③类反思次数 < 1，且还有轮预算。
2. **动作**：失败事实回填 messages + 一条失败上下文说明 → `continue` 进下一轮（模型带失败上下文重规划）——
   **占** 4 轮预算、不加轮次；**失败的那一枪不进 `stepResults`/`plan[]`**（沿用现语义）。
3. **上限**：键 = `ToolName`，同一工具任何③类失败**合计 1 次**；第二次（无论哪种）**按原语义收口** =
   `degraded()` 分支的 fallback（`TOOL_UNAVAILABLE` + 落工单）。更严、更简单、更难绕。
4. **写工具不反思**：`applyRefund`/`modifyDeliveryAddress` 的③失败照旧 fallback + 工单
   （结果不明时自动重试资金动作风险大于收益；改参重试绕开两层幂等，不赌）。
5. **不触发**：①闸与⓪照旧；模型输出层错误（未知工具、计划表达式拒收）照旧 fail-closed 兜底；
   LLM 失败（`LLM_TIMEOUT`/`LLM_CIRCUIT_OPEN`/`LLM_BUDGET_EXCEEDED`）不反思、映射照旧。
6. **不得绕闸**：反思后的重试仍过全部闸（编造拦截/幂等/审批/归属/终态/行业门）——闸判定先于反思；
   重试撞闸 → 走该闸的原出口、不再反思。

**挂点**：`AgentStateMachine` 的 `degraded()` 分支（:395）；网关派生（:488）与槽位续办（:714）同规则
（都只可能是读工具）。

### 2.3 安全闸与循环外出口（反思的边界，票 137）

- **闸 6 条**（反思不得触碰；重试或换路即绕闸）：归属拒绝（`NOT_FOUND`）／编造拦截
  （`isUntrustedOrderNo`，调用前，不是 `ToolStatus`）／幂等回放（`IDEMPOTENT_REPLAY`）／
  终态拒绝（`STATE_NOT_ALLOWED`）／审批闸（`PENDING_APPROVAL`「终态受理」）／行业门
  （非电商工具集为空，「前置裁剪」——工具集不因反思放宽）。
- **循环外 6 出口**（结构性够不到；登记为边界）：限流 `RATE_LIMITED`／情绪门 `EMOTION_ESCALATION`／
  显式转人工 `USER_REQUESTED`／请求级回放（ADR 0046 票 58）／空召回 `INTENT_UNRESOLVED`（ADR 0068）／
  角色拒 `role.denied`（**面外**：运维面 `OpsController`，不在买家对话链路）。
- **非闸**：`ShopRuleIndex` 探针等前置类不逐条列；判据一句话：**不是拒绝、不是终态出口 → 不进闸清单**。
- **写动作守卫（票 41 纠偏）与反思划界**：纠偏治「没做」（上限 1 次、**不占轮**）；反思治「做了但失败」
  （**占轮**）；互不替代、可同轮先后出现；两者都不放宽闸。轮尽写未办照旧 `TOOL_ROUNDS_EXHAUSTED`。

### 2.4 观测面与公开口径（票 145）

| 载体 | 变化 | 内容 |
| --- | --- | --- |
| `AgentResult.PlanStep` | **只增字段** | `attempt`（int，1-based，按工具出现次数）+ `reflection`（bool）；**失败的那一枪不进 `plan[]`**（否则 `eval_task.py` 的 `next(tool==target)` 会把反思后成功判成「没办成」） |
| 指标 | **+1 个名字** | `shoppilot_reflection_total{outcome="triggered"\|"exhausted"}`；指标名 55/57 → **56/58**（README:402 + EVIDENCE 同步） |
| trace | 新增**小写头**行 | `reflection=<tool> attempt=<n>`（挂 `AgentState.PLAN`；gold 的 `executed_tools` 只收大写无空格的头，写错会把工具序列判红） |
| SSE | **不加新事件** | 失败与重试两次 `tool_executing`/`tool_result` 自然可见；机器判据走 `done` 帧 `plan[]` |
| 响应 token 字段 | **R12 回填** | 预算检查分支复用 `sum(roundReplies)+final`；fallback/审批路径回填已发生轮次；缓存命中路径含入口层 |

**公开口径零变动**：`FallbackReason` **不 +1**（反思不是降级出口，用尽仍走 `TOOL_UNAVAILABLE`）；
README「枚举 10 / 降级 9」/ EVIDENCE / architecture-faq 零同步；降级工单描述不加反思线索。
唯一动的公开数字是指标名计数（+1）与 `plan_steps_total` 值域说明（`3`/`4` 将出现）。

**三态判据**：触发 = `plan[]` 有 `reflection=true` 的步或 counter `triggered` 增量 ≥1；
不触发 = 闸/⓪/模型输出层错误/LLM 失败/写工具③失败的回合两者皆无；次数用尽 = counter `exhausted`
+1 且该回合 `fallbackReason=TOOL_UNAVAILABLE`。

## 3. 评测与预算（票 148/149）

- **集**：`eval/cases-part10-autonomy.jsonl`，`kind: autonomy`，**30 条 = 15（动态规划：8 条 4 轮链 + 7 条 2 步链）
  - 10（反思：5 触发 / 3 不触发 / 2 次数用尽）- 5（超限：3 读 + 2 写挂起）**；
  动作类查询不进缓存 → **必须用唯一措辞**（成本不被缓存压低）。
- **模块**：`scripts/eval_autonomy.py`（ADR 0050 形态：**单独成列、不给总分**），不并 `eval_suites.py`
  （那是 CI 断言载体）；0 token 夹具自检**折进 CI 既有套件步**（仍九步）；不进 `GOLD_CASE_FILES`，
  `build_eval_set.py`/`verify_eval_judge.py` 的 `PARTS` 不动。
- **判据列**：三态（§2.4）+ 步数（`plan[]` ≤4、超限形态）+ 出口（`fallbackReason ∈ {TOOL_UNAVAILABLE,
  TOOL_ROUNDS_EXHAUSTED}`）+ 取证列 `billed_tokens`/`latency_ms`（不进判据，只进明细）。
- **成本读法**：`shoppilot_llm_tokens_total` 增量（或 Redis `shoppilot:llm:tokens:<day>` 差值），
  **不读响应 token 字段**（R12 三处缺口；该缺口本身在票 145 修）。
- **预算**：`SHOPPILOT_LLM_DAILY_TOKEN_BUDGET` 260000 → **600000**（`.env` + `.env.example` 注释；
  ADR 0012 闸门本体保留）。实测依据：单跑 ≈ 29 万（票 140，实付 176,117 token 的探针）；
  600k = 一次全跑 + 一次复跑；若只批「单跑 + 重试」口径则退 400000。
- **节奏**：`PACE_SECONDS=0.25`（≈4 QPS < 默认 `customer-qps=5`）；30 条单跑墙钟 ≈ 4-5 分钟。
- **归因**：读数一律标 **v1.2.0**，与 v1.1.0 不可混比（ADR 0037 第 5 条 + EVIDENCE 纪律）。

## 4. 拆票表（实现票 143-150；票面由 `/to-tickets` 建）

| 票 | 一句话 | What（要点） | Verify | Blocked by | 依据 |
| --- | --- | --- | --- | --- | --- |
| **143** | 配置换代：4 轮 | yml 占位符 `${SHOPPILOT_AGENT_MAX_TOOL_ROUNDS:4}`；`ConfigValidationTest` 占位符清单登记；`.env.example` 注释；两个 `TOOL_ROUNDS_EXHAUSTED` 用例按默认 4 重钉；默认值 4 的 JVM 断言 | `mvnw verify`；G6 常数只随新鲜活体读数换代（中途不动） | — | 政策覆盖（ADR 0069 §三） |
| **144** | 反思分支 | `degraded()`（:395）+ 网关派生（:488）+ 槽位续办（:714）三处同规则；失败事实回填 + 失败上下文；按工具合计 1 次；写工具照旧 fallback；闸判定先于反思 | JVM：138 的 7 格用例名 + `reflectionRetryHittingGateAbortsWithoutSecondReflection`；**变异对照两条**（抽反思分支 → 红；闸判定挪后 → 红） | 143 | 政策覆盖（138） |
| **145** | 观测面 + R12 | `PlanStep` 增 `attempt`/`reflection`；counter `reflection_total{outcome}`；trace 小写行；**R12 三处 token 缺口回填**；README:402 55/57→56/58 + EVIDENCE | JVM（反思回合 `reflection=true`/`attempt=2`；counter 两条）+ `eval_task.py` 夹具回归（失败枪不进 `plan[]`）+ 文档换代 | 144 | 政策覆盖（139）+ R12 触发 |
| **146** | 写守卫补行业门（R11） | `expectedWriteTool`/纠偏条件加 `domain == Domain.ECOMMERCE`（或按 `toolsFor` 非空判）+ HOTEL 级 JVM 用例 | JVM + 变异对照（去掉 domain 门 → 红） | —（建议排 144 后，同文件） | ADR 0031:10 事实性修正 |
| **147** | 提示词 v1.2.0 | 新文件 `v1.2.0.md`（**第 4 条尾句扩写**：「如果系统随后给了失败上下文，换一种做法（换工具或换参数）再试一次，仍然失败就照实说明并建议转人工。」）+ `meta.json`；**四处钉子同步**（`PromptCatalogTest` 版本/11 条/首尾锚点、`GatewayMainPathJvmTest:501`、`ChannelFlowJvmTest:83/99`）；旧版保留 | JVM；**预算数字不进提示词**（第 9 条原文不动） | — | 政策覆盖（141） |
| **148** | part10 集与模块（0 token） | §3 的集、模块、判据列、夹具自检；CI 折步（**仍九步**）；`GOLD_CASE_FILES`/`PARTS` 零改动 | 0 token 自检实跑 + CI 实跑；gold 面零触碰 | 144、145 | 政策覆盖（140） |
| **149** | dev 活体跑一次 + 登记 | 预算 260000 → 600000（`.env` + `.env.example`）；dev（qwen-plus）按 0.25s 节流跑 part10 全量一次；读数（计费 token/时长/三态）落 `eval/results/` + `docs/EVIDENCE.md` 行；归因 **v1.2.0** | 报告实读（不允许只写投影）；实测单跑 ≈29 万 | 147、148 | 政策覆盖（140） |
| **150** | round35 收口 | JVM verify + 审计实跑 + 叙事换代（README agent 段 / architecture-faq / DELIVERY / CHANGELOG，按既有收尾流程）+ tracker 索引 + `registered-debt.md`（R11/R12 挪「已关闭」）+ EVIDENCE 核对 | `mvnw verify` + 审计读数 + `git diff --check` | 143-149 | 本仓收尾流程 |

## 5. 顺序与验证分档

1. **机制层（143-146）**：全 JVM 桩、0 token、先钉契约；146 与本轮主线相邻但依据独立（R11）。
2. **提示词（147）**：内容已在 141 裁定，落地即版本三件 + 钉子。
3. **评测集（148）**：0 token 自检进 CI；判据依赖 144/145 的字段与 counter。
4. **dev 活体（149）**：烧真钱的一次；预算抬幅与集编号在 140 实测定死。
5. **收口（150）**：叙事换代按既有流程（map 135 的 Out of scope 已声明：叙事不属 map，属实现轮收口）。

本机资源照 map 常设约束分档：JVM 桩先行；dev 活体按额度与显存分档；**矩阵/全栈活体按清场日裁定，照登**。

## 6. 风险与照登（v1 §3 重锚）

- **local 3B 大概率跑不出 4 步链** → `plansteps` 红可能更红，**判据不动、照登**（round20 家法）。
- **dev 评测烧真钱**：预算以 140 的 AFK 实测为准（不拍脑袋）；单跑 ≈29 万 token 记在 140 票上（实付 176,117）。
- **反思是新的模型方差源** → 判据只判可枚举三态（触发/不触发/次数用尽），**不判「看起来聪明」**。
- **失败分类边界漂移会打现有 gold** → JVM 钉死三分法每一格（变异对照两条）；`PLN-ABORT-*` 原样保住。
- **本机资源**：16G 内存与显存争抢（tracker 2026-09-28 登记项）；part10 一次跑墙钟 4-5 分钟，不构成压力。
- **R12 未闭合期间**：任何按响应 token 字段算成本的地方仍会低报——本 spec 的成本读法已绕开（读指标增量）。

## 7. 开轮前置检查（v1 §4 逐条对）

- [x] 深层架构探讨结束，不推翻本文范围——round23-34 的结构换代已完成，本文即重锚结果（136-142 全部收口）。
- [x] ADR 0069 落稿（覆盖 ADR 0008/0036 的依据写足；既有 ADR 原文一字未改，换代指针落本 spec / ADR 0069 / tracker）。
- [x] `.env` 预算抬幅经试跑实测（票 140 AFK 实测三轮，读数与命令在票内）。
- [x] 拆票走 `/to-tickets`，票号 **143** 起——**2026-10-19 已完成**：票面 143-150 已建（`ready-for-agent`），
  frontier = {143, 146, 147}；本 spec §4 与票面逐条对应。
