# 140 评测集与预算

**Label:** wayfinder:grilling（第一步是 AFK 实测）　**Status:** implemented（决策票 + AFK 实测三轮，零代码改动）　（map：[135](135-wayfinder-agent-autonomy-map.md)）

**Claimed by:** zcode wayfinder session 2026-10-18（第六张：140）

## Question

新评测集的**编号、规模、判据列、预算抬幅**怎么定：集名与编号（part9 已被酒店占用）、条数与分区
（动态规划/反思/超限收口）、judge 新列是否单独成列、dev 预算抬到多少（试跑实测，不拍脑袋）、
是否进 `GOLD_CASE_FILES`。

## 已核实的事实（不必重查）

- `eval/cases-part9-hotel.jsonl` 已被 round32 占用（`kind: hotel`，**不并入 180 条 gold**）→ 新集从 **part10** 起。
- `scripts/eval_suites.py` 按 `kind` 分发判据、自带 0 token 夹具；`plan` kind 的判据是前缀匹配 +
  `plan-aborted` / `plan-rejected` 形态断言（见 [138](138-failure-triage-reclass.md)）。
- ADR 0012 的日预算闸门当前值 `SHOPPILOT_LLM_DAILY_TOKEN_BUDGET=260000`（`.env`）；spec 预估新集 10–20 万 token
  （每条 3–6k），**按实测抬**、不废除闸门本身。
- 先例：part9 的套件带逐条节流（0.25s，≈4 QPS）避免客户级限流（round33 实测 429）。

## 口径

- **第一步是 AFK 实测**：小样试跑量出「每条平均 token / 时长 / 触发限流情况」，读数进 resolution；
  裁定（规模/预算/列）是 HITL。
- 判据红线：180 条 gold / 阈值 / 分母零触碰；新集单独成列（ADR 0050 先例），不进 gold 文件集。

## Blocked by

[136](136-autonomy-budget-and-steps.md)（4 步链决定用例形态）、[138](138-failure-triage-reclass.md)（三分法决定「超限收口」用例）。

## Verify

```powershell
git diff --check && git status --short
```

## Handoff notes

（解决时补：实测读数 + 集编号/规模/列/预算 + 试跑命令）

## Resolution（2026-10-18，AFK 实测三轮已执行 + HITL 裁定未获答按建议值记录；决策票，零代码改动）

**会话记录**：本票的 HITL 裁定部分无人在场回答，按 137/138/139 先例**逐条标「建议值」**，修正入口在
[142](142-round-scoping-and-adr-0069.md)。与前三票不同的是：**票面要求的 AFK 实测真的跑了**
（三轮探针 + 两条定向读数），下面每个数字都带命令与口径，不是推算。

**一句话**：新集 = `part10`（kind `autonomy`，30 条 = 15/10/5），新模块 + 单独成列、不进 `GOLD_CASE_FILES`；
**实测单跑 ≈ 29 万 token**（spec 预估的 10–20 万低估了近一倍）→ **日预算建议 260000 → 600000**（闸门本体不动）；
评测的成本读数必须改读 `shoppilot_llm_tokens_total` 增量，**不能读响应里的 token 字段**（三处缺口，见下）。

### 一、实测环境与命令（可复跑）

- **环境**：网关 `-Profile dev`（qwen-plus @ DashScope）+ biz-mock 在本机活体；`SHOPPILOT_AGENT_MAX_TOOL_ROUNDS=4`
  临时写进 `.env` 量 4 轮形态（量完**按字节还原**，sha256 与量前一致：`1902db9d…`）；中间件用本机已有
  Redis/ES/Qdrant；Ollama 不参与 dev 档。**量后现场已复原**：网关回 `local` 档、biz-mock 停（与开工前一致）。
- **命令**：三轮探针脚本（临时文件，未入仓）逐条 `POST /api/v1/support/chat`，每条前 `POST /ops/demo/reset`，
  逐条 0.25s 节流；工具失败一条用 `PUT /ops/fault {"failRate":1.0}`（量完归零）。
- **计费口径**：每条前后各读一次 `/api/v1/support/ops/circuit` 的 `tokensUsedToday`（Redis
  `shoppilot:llm:tokens:<day>`），差值即该请求真实计费；进程外交叉核对 `shoppilot_llm_tokens_total{mode,source}`。
- **规模**：主读数三轮 27 条 + 定向 3 条 + 首轮因 biz-mock 未起而无效的 15 条 = **45 条 chat 请求，
  0 × 429、0 × 非 200**（注：那轮无效读数里 13 条工具用例全部降级为 `TOOL_UNAVAILABLE`，是 biz-mock 没起的环境红、
  不是被测行为，故整轮作废——它的 31.6k token 计进本票实付）；本票实付 **176,117 token**
  （当日 Redis 日计数，与 `shoppilot_llm_tokens_total` 交叉核对一致）——**这笔钱记在票里**。

### 二、读数表（dev / qwen-plus / 0.25s 节流；「计费」= Redis 增量）

| 形态（实测样例） | 响应上报 | **计费 tokens** | 时长 | 备注 |
| --- | ---: | ---: | ---: | --- |
| 1 工具（90003 发货+物流） | 4,508+187 | **5,148** | 6.1s | 单轮基线 |
| 2 步（90002 状态+物流） | 6,793+322 | **7,562** | 9.5s | 两轮 |
| 4 轮（90002/90003 双单核对） | 2,695+116 | **11,338** | 8.1s | 同措辞跑两次：11,338 / 11,341；**上报只含最后一跳** |
| 超限收口（5 步） | 0 | **11,489** | 7.3s | `TOOL_ROUNDS_EXHAUSTED` |
| 工具失败（failRate=1.0，现行为） | 0 | **2,021** | 2.2s | 失败一跳 + 兜底；反思版再加 1 轮 |
| 写受理（`PENDING_APPROVAL`） | 0 | **2,025** | 2.0s | applyRefund 受理即收尾 |
| 政策冷（退货运费 / 延时退房） | 1,677+141 | **2,250** | 4.8s | 纯咨询 |
| 政策热（L2 命中） | 0 | **433** | 0.7–1.9s | 只剩入口层一跳 |
| 转人工 | 0 | 未逐条量 | 0.7s | 与超限同为短路径，不入预算投影 |

**三条账目事实（新发现，都是实测 + 代码核实）**：

1. **响应里的 `promptTokens`/`completionTokens` 不是全量，三处缺口**：① 走「预算检查后收尾」分支时
   （`AgentStateMachine.java:505-511`，赋值在 `:509-510`；条件含 `answeredByBudgetCheck=true`）只报最后一跳——4 轮 / 超限两类
   恰好全落在这一支（2,811 vs 计费 11,338）；② `fallback()` 与 `pendingApprovalAnswer()` 路径根本不回填（报 0，
   实测 2.0k）；③ 缓存命中报 0，但入口层实付 0.43k。→ **新集的成本列只能读指标增量**；响应字段的缺口登记
   **R12**（见 `registered-debt.md`），触发条件 = 实现轮动观测面（139 的落地清单）时顺手补。
2. **每请求另有约 0.43–0.45k 的入口层调用**（政策热 433、各正常请求「计费 − 上报」≈ 442–453，
   `shoppilot_llm_calls_total` 同步 +1）；**缓存命中也要付**。
3. **每轮工具轮的边际 ≈ 1.9–2.4k token**（(11,338−7,562)/2 ≈ 1,888；(7,562−5,148)/1 ≈ 2,414）——
   反思「占一轮预算」的代价按此计；动作类查询**不进缓存**（同措辞重跑 cacheLayer=NONE，全额计费），
   所以新集必须用唯一措辞，成本不会被缓存压低。

### 三、裁定（除注明外为建议值）

**① 编号与命名**：`eval/cases-part10-autonomy.jsonl`；`kind: autonomy`；套件名 `autonomy`。
**新模块 `scripts/eval_autonomy.py`**（ADR 0050 形态，同 `eval_task.py` 的独立载体），**不并进 `eval_suites.py`**
——那份是 CI 的断言载体（round19 登记第 4 项逐字要求新模块）且现成夹具 24+ 条；0 token 夹具自检**折进
CI 既有套件步**（round32 一页纸先例：不加步数，CI 仍九步）。

**② 规模与分区：30 条 = 15 / 10 / 5**（spec 决策 2 的规模维持，按实测定价）：

- **动态规划 15 条**：8 条 4 轮链（含「读+读+读+写」收尾型）+ 7 条 2 步链（现形状对照）；
- **反思 10 条**：5 触发（读工具 ③失败 → 换路成功）/ 3 不触发（闸与写工具失败面）/ 2 次数用尽
  （同工具第二次 ③失败 → `TOOL_UNAVAILABLE` + 工单）；
- **超限收口 5 条**：3 读超限 + 2 写挂起超限（两条出口语义不同，[136](136-autonomy-budget-and-steps.md)/[138](138-failure-triage-reclass.md) 已定）。

**单跑投影**（按上表均价值）：15 条 ≈ 14.4 万（8×11.3k + 7×7.6k）+ 10 条 ≈ 8.8 万（5×9.9k + 3×7.6k + 2×8.0k，
含反思轮边际）+ 5 条 ≈ 5.8 万（×11.5k）= **≈ 29 万 token**。**spec 的「10–20 万（每条 3–6k）」按实测修正为
「每条 2.0–11.5k、均值 ≈ 9.6k」**。

**③ judge 列：单独成列，不给总分、不并入任何现有列**（ADR 0050 家法）。列面 = 三态判据
（[139](139-reflection-observability-and-enum.md)：`reflection`/`attempt` 字段 + `shoppilot_reflection_total{outcome}`）
+ 步数（`plan[]` ≤4、超限形态）+ 出口（`fallbackReason` ∈ `TOOL_UNAVAILABLE`/`TOOL_ROUNDS_EXHAUSTED`）
+ **两条取证列 `billed_tokens` / `latency_ms`**（不进判据，只进明细）。

**④ 预算：`SHOPPILOT_LLM_DAILY_TOKEN_BUDGET` 260000 → 600000**（`.env` 值 + `.env.example` 注释同步；
**ADR 0012 的闸门本体保留**）。依据：实测单跑 ≈ 29 万 → **一次全跑 + 一次全量复跑 = 58 万**（评测调参至少要跑两遍）；
且与台账里已登记的「gold 全量 ≈ 40–60 万」同一个数——U7 的**额度侧前置**由此成立（是否重跑 gold 仍是独立决策）。
若 142 只批「单跑 + 重试」口径，则退到 **400,000**（29 万 + 11 万重试）。

**⑤ 不进 `GOLD_CASE_FILES`**（该常量只含 part1-3；`build_eval_set.py` 与 `verify_eval_judge.py` 的 `PARTS`
同样只扫 part1-3，**都不动**）。part10 是 add-only 新文件，收口审计 B7 对新增文件放行。

**⑥ 限流与节奏**：沿用 `PACE_SECONDS=0.25`（≈4 QPS < 默认 `customer-qps=5`）；实测 45 条 0 × 429、
0 × 非 200；30 条单跑墙钟 ≈ 4–5 分钟，不构成资源压力。

### 四、给 142 / 实现轮的输入

- **ADR 0069 落地清单**加四项：`cases-part10` + `eval_autonomy.py`（含 0 token 夹具 + CI 折步）、
  预算 600000、**R12（token 口径缺口）**。
- 实现轮必须先落 [136](136-autonomy-budget-and-steps.md)/[139](139-reflection-observability-and-enum.md) 的配置与观测面
  （`max-tool-rounds=4`、`reflection` 字段与 counter），否则反思分区无判据可写；读数归因必须与
  [141](141-prompt-v1-2-0.md) 的 v1.2.0 同轮（与 v1.1.0 不可混比）。
- 两条 JVM 用例按默认 2 写死（136 已列）与 `GatewayMainPathJvmTest` 同步；gold 前缀判据零触碰。

## Handoff notes

**关键决策**：见 Resolution 三节。一句话：**part10 / `kind: autonomy` / 30 条（15-10-5）/ 单独成列（ADR 0050）/
不进 `GOLD_CASE_FILES` / 预算 600,000（闸门保留）**；实测单跑 ≈ **29 万 token**，平均每条约 9.6k——
spec 的 10–20 万预估按实测修正。

**验证落点**：本票是决策票，文档级验证 = `git diff --check` + `git status --short`；新增登记 = `registered-debt.md` R12
与 tracker/map 的收口行。机器落点留给实现轮：part10 夹具 + `eval_autonomy.py` 自检（0 token）+ 三态判据的
JVM/counter 断言 + 明细里的 `billed_tokens`（读 `shoppilot_llm_tokens_total` 增量，不读响应字段）；
`docs/EVIDENCE.md` 的 part10 行（夹具、判据来源、每类实测单价与预算值）**随 part10 落地时一并登记**，
本票不动它（没有公开数字换代）。
**实测账**：本票花掉 dev 额度 **176,117 token**（当日计数），probe 脚本为临时文件未入仓；
复跑配方（端点、节流、计费口径、失败注入）已逐条写在 Resolution 一节。

**三个现场追问**：

1. **预算为什么是 60 万不是 40 万？**——实测单跑 29 万，评测调参至少要跑两遍 → 58 万；这个数同时等于台账里
   「gold 全量 ≥60 万」。只批单跑口径就退 40 万（Resolution ③④ 已写死两个档的换算依据）。
2. **为什么新集既不进 `eval_suites.py` 也不进 gold？**——前者是 CI 断言载体（round19 登记第 4 项逐字要求新模块），
   后者是分母红线；两部分开正好让「新列不并总分」在物理上无法违反。
3. **实测读数的可复跑性与它的债？**——配方在 Resolution 一节；两个未闭合的洞如实记着：R12（响应 token 字段
   三处缺口）与「转人工路径未逐条量计费」。二者都不影响预算结论（前者使旧读偏低、后者是最短路径）。
