# 148 自主性评测集 part10 + `eval_autonomy.py`（0 token 自检）

**Status:** implemented（2026-10-19）
**Claimed by:** zcode session 2026-10-19（round35 实现轮：147 之后接 148）

## What to build

建一套**能判动态规划与反思**的新评测集与独立判分模块（单独成列、不给总分、不并 `eval_suites.py`），
默认入口是 0 token 夹具自检（无网关、无模型），CI 折进既有套件步（**仍九步**）。

- `eval/cases-part10-autonomy.jsonl`，`kind: autonomy`，**30 条 = 15 / 10 / 5**：
  - **动态规划 15**：8 条 4 轮链（含「读+读+读+写」收尾型）+ 7 条 2 步链（现形状对照）；
  - **反思 10**：5 触发（读工具③失败 → 换路成功）/ 3 不触发（闸与写工具失败面）/ 2 次数用尽
    （同工具第二次③失败 → `TOOL_UNAVAILABLE` + 工单）；
  - **超限收口 5**：3 读超限 + 2 写挂起超限（两条出口语义不同）。
- `scripts/eval_autonomy.py`：与 `eval_task.py` 同形态的独立载体——默认无参数跑 = 夹具自检（0 token）；
  活体跑批复用 `run_tool_eval.py` 的 HTTP 助手（新增 `--autonomy` 入口）。
- 判据列 = 三态（`plan[]` 的 `reflection` / `attempt` + `shoppilot_reflection_total{outcome}`）
  + 步数（`plan[]` ≤4、超限形态）+ 出口（`fallbackReason ∈ {TOOL_UNAVAILABLE, TOOL_ROUNDS_EXHAUSTED}`）；
  **取证列** `billed_tokens` / `latency_ms` 不进判据、只进明细。
- 成本列读 `shoppilot_llm_tokens_total` 增量（或 Redis 日计数差值），**不读响应 token 字段**。

## Blocked by

[144](144-reflection-branch.md)、[145](145-reflection-observability-and-token-fields.md)
（反思分区与三态判据依赖它们的字段与 counter）。

## 口径

- 依据：[ADR 0069](../../../docs/adr/0069-round35-agent-autonomy-upgrade-reopen-basis.md) §五；140 的裁定是唯一口径。
- **不进 `GOLD_CASE_FILES`**；`build_eval_set.py` / `verify_eval_judge.py` 的 `PARTS` 不动；part10 是 add-only 新文件。
- 用例措辞**必须唯一**（动作类查询不进缓存，同措辞重跑会全额计费，缓存会压低成本读数）。
- CI：把 `python3 scripts/eval_autonomy.py` 折进既有套件步（`&&` 串在 `eval_suites.py` 之后），
  **步数不变（九步）**，不新增一步。

## Verify

```powershell
python scripts/eval_autonomy.py
python scripts/eval_suites.py
git diff --check; git status --short
```

（首条输出 `AUTONOMY SELFCHECK ok=<n>` 且退出码 0；第二条仍绿。）

## Handoff notes

**关键决策**：

1. **集子形状是机器钉住的**（`eval_autonomy.py: validate_cases`）：30 条 = 15（8×4 轮 + 7×2 步）- 10
   （5 触发 / 3 不触发 / 2 用尽）- 5（3 读超限 + 2 写挂起超限）、**每题必须声明三态**、plan/rounds 区必须是 `none`、
   反思区的用尽档出口必须是 `TOOL_UNAVAILABLE`、**全 30 条措辞逐字唯一**（动作类查询不进缓存，同措辞重跑会全额计费
   并把成本读数压低——票 140 的实测事实）。改集子的规模/配比会当场红，必须连判据一起改。
2. **超限形态的步数读 trace 而不是 `plan[]`**：`fallback()` 出口的 `AgentResult` 构造里 `planSteps` 是 `List.of()`
   （plan 只记成功步，失败枪与收口路径都不带走），所以那一档 `plan[]` 恒为空；「确实打了 4 枪」由 trace 的
   `TOOL_EXEC` 行数判（复用 `eval_suites.executed_tools` 这一个解析器，不写第二份）。读超限与写挂起超限**出口相同、
   路径不同**，由 trace 的 `tool-rounds-exhausted write-pending=` 行区分。
3. **故障诱导用 `fault` 字段逐例声明，两种形态**：`persistent`（整例挂着 → 判"用尽"与"写工具不反思"）与
   `window`（**只烧第一枪**：watcher 线程盯 `shoppilot_tool_unavailable_total`，抬头即清零，反思重试落在干净窗口里）。
   之所以要"窗口"这么绕：**biz-mock 的故障面只有全局 `delayMs`/`failRate`，没有按工具或一次性的开关**
   （代码核实 `FaultInjector` + `/api/admin/fault`），不"只烧一枪"的话重试必然也失败，5 条触发档就退化成用尽档的重复。
   **诱导立不起来（窗口没抬头 / ops 调用失败）→ 记 `induction=window-missed|fault-set-failed` 且三态记未观测**
   （`None`，既不是 PASS 也不是 FAIL），本仓"未达成照登"的同一口径。18 条不注入（靠演示固定单的业务状态确定性复现
   两处闸：跨店单号 → `NOT_FOUND`、未发货单查物流 → `STATE_NOT_ALLOWED`）。
4. **成本列读 `ops/circuit` 的 `tokensUsedToday` 增量**（= Redis `shoppilot:llm:tokens:<day>`，与
   `shoppilot_llm_tokens_total` 同源），**不读响应 token 字段**（R12 三处缺口）。`billed_tokens`/`latency_ms`
   只进明细、**不参与判定**。
5. **与既有评测面的关系**：不进 `GOLD_CASE_FILES`、`build_eval_set.py`/`verify_eval_judge.py` 的 `PARTS` 不动、
   **`eval_suites.py` 一字未改**（只 import 它的 `PACE_SECONDS` 与 trace 解析器）；CI 把
   `python3 scripts/eval_autonomy.py` 用 `&&` 串进**既有 suite 步**——**仍九步**（一步做三件事，步数是成本口径）。

**验证落点**：

- `python scripts/eval_autonomy.py` → **`AUTONOMY SELFCHECK ok=15`**（15 条夹具 + 30 条用例的结构/分区/唯一性校验），exit 0。
- `python scripts/eval_suites.py` → `SUITE SELFCHECK ok=30`；`python scripts/eval_task.py` → `ok=14`；
  `python scripts/verify_onepager.py` → `failures=0`；`python scripts/verify_eval_judge.py` → **40/40**
  （EOL 基线 17 个文件不动，新增的 part10 不在那份清单里）。
- `python scripts/run_tool_eval.py --autonomy` 的**预检链**实跑：`SCORER SELFCHECK ok=16` → `AUTONOMY SELFCHECK ok=15`
  → 之后才发第一个请求（与 `--suite`/`--task` 同形；无网关时停在这里）。
- **活体链路用假 runner 走通了一遍**（一次性夹具，**不入库**、明细落仓库外）：30 条全判、5 条窗口诱导
  `induction=window` 全中、跑完故障面清零、返回码 0。这一趟顺带抓到并修掉两处**只有活体路径才会暴露**的问题：
  ① **窗口竞态**——第一枪打在响应尾巴上时 watcher 还没轮到下一次轮询，`__exit__` 补一次终检（漏判会把"立起来了"记成没立）；
  ② 明细路径不在仓内时 `relative_to` 抛异常，**会把一次烧 29 万 token 的跑批报告吞掉**（已改成防御式显示）。
- **变异对照（负例实测为真）**：把两条措辞改重复 → 1 条红；把一条 4 轮链改成 2 步 → 2 条红（分区配比失配）；
  三态写成 `fired` → 2 条红；超限不声明 `writePending` → 1 条红。

**三个现场追问**：

1. 为什么超限那一档不判 `plan[]`？——见决策 2：收口路径的 `AgentResult` 不带走 plan，拿它判步数会恒判 0；
   用 trace 枪数既判得出"确实打满 4 枪"，又顺带机器背书了"计划步数与工具轮同源"这条口径。
2. 为什么要发明"窗口"这种诱导？——因为**诱导面不存在**：故障注入是全局的（代码核实），不"只烧一枪"就没有
   "③失败之后换路成功"这个形态可观测，而那正是票 140 定的 5 条触发档的全部内容；窗口立不起来时记**未观测**
   而不是判红，是因为那时"没触发"的成因在环境而不在被测行为（判红等于把环境账记到机制上）。
3. 这套集子会不会动 gold / 判据面？——不会：`GOLD_CASE_FILES` 不含它、`PARTS` 只扫 part1-3、
   `eval_suites.py` 未改、`judge()` 未改；它**单独成列、不给总分**（ADR 0050），CI 仍是九步。

**活体入口的复跑配方**（票据 149 用）：

```powershell
# 前提：biz-mock(:8091) 与网关(:8082) 已起、dev 档（qwen-plus @ DashScope）、
#       .env 的 SHOPPILOT_LLM_DAILY_TOKEN_BUDGET 按票 149 抬到 600000（ADR 0012 闸门本体保留）
python scripts/run_tool_eval.py --autonomy --base http://127.0.0.1:8082 --ops-token dev-ops-token
```

- 跑前自动 `ops/demo/reset` 复位演示固定单（写类用例会改 90001 的退款受理与 90004 的地址，不复位第二遍就不是在测模型）。
- 逐条 0.25s 节流（≈4 QPS，低于默认 `customer-qps=5`）；8 条注入故障（5 窗口 + 3 持久）、18 条不注入。
- 明细落 `eval/results/tool-eval-<stamp>-dev-autonomy.csv`（`billed_tokens`/`latency_ms`/`induction`/`state` 都在里面）。
- **读数归因 `promptVersion=v1.2.0`**（与 v1.1.0 不可混比）；详细读数与 EVIDENCE 行是**票 149** 的交付。
