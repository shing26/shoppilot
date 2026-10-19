# 149 dev 活体跑 part10 一次 + 读数登记（预算 600k 落地）

**Status:** implemented（2026-10-19）

**Claimed by:** zcode implement session 2026-10-19（round35 实现轮：预算 600k 落地 + dev 活体跑 part10 全量一次 + EVIDENCE 行）

## What to build

用 dev 档（qwen-plus）把 part10 全量跑一次，把真实读数写进仓库；同时把日预算闸门的**值**抬到实测覆盖得了的水平
（闸门本体保留）。

- `.env`：`SHOPPILOT_LLM_DAILY_TOKEN_BUDGET` 260000 → **600000**；`.env.example` 注释同步
  （`.env` 不入库，只改 `.env.example` 并说明）。
- dev 档活体跑 part10 **全量 30 条**，逐条 0.25s 节流（≈4 QPS < `customer-qps=5`），每条前复位演示数据。
- 读数三件：**计费 token**（`shoppilot_llm_tokens_total` 增量 / Redis `shoppilot:llm:tokens:<day>` 差值）、
  **时长**、**三态与出口**（触发 / 不触发 / 用尽；`TOOL_UNAVAILABLE` 与 `TOOL_ROUNDS_EXHAUSTED`）。
- 明细与报告落 `eval/results/`（add-only），`docs/EVIDENCE.md` 增 part10 行（夹具、判据来源、实测单价、预算值）。
- 所有读数标注 **promptVersion = v1.2.0**（与 v1.1.0 不可混比）。

## Blocked by

[147](147-prompt-v1-2-0.md)、[148](148-autonomy-eval-set-and-module.md)（归因必须与 v1.2.0 同轮；集与判分模块先就位）。

## 口径

- 依据：[ADR 0069](../../../docs/adr/0069-round35-agent-autonomy-upgrade-reopen-basis.md) §五；140 的 AFK 实测是预算依据
  （单跑 ≈ **29 万 token**、30 条、均值 ≈9.6k；600k = 一次全跑 + 一次复跑）。
- **花真钱**：执行前确认 `.env` 的 dev key 与当日额度；若只批「单跑 + 重试」口径则预算取 **400000**（ADR 0069 记录）。
- 成本读数**不读响应 token 字段**（R12 的三处缺口即便已在 145 修，评测列仍以指标增量为准，口径单一）。
- gold 180 / 阈值 / 分母零触碰；本票不重跑 gold（U7 是独立决策）。
- 实测口径可复跑：命令、端点、节流、复位与计费算法逐条写进 Handoff。

## Verify

```powershell
# 预检：网关 dev 档与 biz-mock 已起、额度可用（不要把 biz-mock 没起的一轮当真读数）
python scripts/eval_autonomy.py            # 0 token 自检先绿
# 活体跑批（入口按票 148 落地后的实际参数）
python scripts/run_tool_eval.py --autonomy
git diff --check; git status --short
```

## Handoff notes

**关键决策**：

1. **跑了两轮，只有 run#2 是登记读数**。run#1（`tool-eval-20261019-084356-dev-autonomy.csv`，245,452 token）
   作废的唯一理由是**工单服务（`:8092`）当时没起**——降级/超限路径要经它落工单，于是 5 条超限用例的 `ticket_ok` 全红。
   这属于本票自己写的「环境红不进读数」，不是机制读数；补起工单服务后 run#2 的 RD 区 **5/5 绿**，反证 run#1 那 5 条红确是环境。
   run#1 仍作为**方差参照**保留（它的 PLAN 区 15/15 绿，run#2 同区 5 条红）。
2. **读数一律按计费口径**：`ops/circuit.tokensUsedToday` 增量（= Redis `shoppilot:llm:tokens:<day>`，与
   `shoppilot_llm_tokens_total` 同源），**不读响应 token 字段**（R12 三处缺口；票 145 虽已回填，评测列仍不依赖它）。
3. **在 148 的模块上修了三处"只有活体才暴露"的**（0 判据改动：只动传输与复位）：
   ① 运维面 ops 调用必须**同时**带 bearer 与 `X-Ops-Token`（首跑实测只带后者 → `401 missing bearer token`，假 runner 不跑真链路所以看不见）；
   ② `/ops/fault` 的写是 **PUT**，而共享 HTTP 助手只会 POST/GET（405）→ 给 `run_tool_eval.http_json` 加可选 `method=`（不给保持原行为）；
   ③ 按票面 `What to build` 落**逐条前置复位**（跑批前一次 + 每条前一次；实测 0.03 s/次、不打模型、不进成本列；失败只警告不掀翻整批）。
4. **不改判据、不改集子、不改阈值**：18 条红全部按成因登记（三簇），没有为了变绿动过 `eval_autonomy.py` 的判分；
   `GOLD_CASE_FILES`/`PARTS`/`eval_suites.py`/`judge()` 零触碰，gold 180 未重跑。

**读数表（run#2，归因 promptVersion = v1.2.0 / max-tool-rounds=4 / HEAD `5831262`）**：

| 项 | 读数 |
| --- | --- |
| 用例 | 30 条全跑完（无 429、无 error 行） |
| 计费 token | **222,276**（均值 7,409、最小 3,071、最大 12,571；CSV 合计与计数器增量吻合） |
| 时长 | 墙钟 **211 s**（08:50:47→08:54:17）；请求耗时合计 199 s、单条最大 12.2 s |
| 三态 | **触发 7 / 不触发 23 / 用尽 0 / 未观测 0** |
| 判定 | 130 条断言 → 红 18 / 未观测 5 |
| 分区 | `plan` 15 条 5 红 120,023 token；`reflection` 10 条 7 红 50,656；`rounds` 5 条 **0 红** 51,597 |
| 出口 | 超限档 `TOOL_ROUNDS_EXHAUSTED` + 落工单 **5/5 成立**；`TOOL_UNAVAILABLE` 那两档本轮**未观测**（见红簇③） |
| 当日额度 | **470,037 / 600,000**（run#1 245,452 + run#2 222,276 + 冒烟 2,309；另一次手工探针被请求级回放吃掉、计费 0） |

**18 条红三簇（成因，附证据）**：

- **① PLAN 区 5 条 = 模型方差**（`AUT-PLAN-01/06/10/14/15`）：期望 4 轮链/2 步链，run#2 只打了 1 枪就收尾；
  **同样的 5 条在 run#1 全绿**（同集、同版代码）→ 量到的是 dev 模型采样波动，不是 4 轮机制坏了
  （`rounds` 区「打满 4 枪」在 run#2 反而 5/5 绿）。
- **② 反思触发档 4 条 = 机制触发、模型不跟进**（`AUT-REFL-02..05`）：窗口故障立住了（`induction=window`）、
  反思计数 +1（`state=triggered`），但模型拿到失败上下文后没再打一枪（`rounds=1`、`plan[]` 空，回答「系统暂时无法查询…」）。
  5 条里只有 `AUT-REFL-01` 走完「失败→重试→成功」。**反思是系统行为（已实现、可观测）；"换一种做法再试一次"是模型行为（dev 不跟）**。
- **③ 用尽档 2 条 + 写工具档 1 条**：`AUT-REFL-09/10` 到不了「同一工具第二次③失败」——模型要么直接收尾、要么换工具再试，
  而 prompt 第 4 条教的正是「换工具或换参数」，与该档要求的**原样重试**方向相反（**登记为口径张力**，`state_exhausted=0` 的成因；
  计数接线本身有 JVM 用例钉着）。`AUT-REFL-08` 被**幂等回放**挡住：同批更早的 `AUT-PLAN-05/11` 已用同一单同一参数提交过退款，
  派生 token 只看 `tenantId|customerId|tool|参数`、与措辞无关，而复位只清 biz-mock 的库、不清 Redis 回放索引（6 h TTL，
  round21 票 61 同源陷阱）；手工 trace 原文 `TOOL_EXEC APPLY_REFUND=IDEMPOTENT_REPLAY modelArgs={"orderNo":"90001"}`。
  **这两条都是集子隔离问题，登记给 148 面**（修法：写类用例换不同单号，或演示复位连回放索引一起清）。

**与 140 的预估差**：140 的 AFK 外推是**单跑 ≈29 万 token**（均值 ≈9.6k）；实测 **22.2 万**（均值 7.4k，低 23%），
run#1 24.1 万（低 17%）。成因两条：反思档与「模型提前收尾」的用例比设计少打 1~3 枪（<4k 的用例共 10 条）；
4 轮链满配时单条 12~12.6k，与预估 11.5k 上界一致。**600k 预算够「一次全跑 + 一次复跑」**（实测两跑 46.8 万）。

**复跑配方**：

```powershell
# 前提：三个服务起齐（网关 profile=dev → qwen-plus @ DashScope；biz-mock:8091；**工单服务:8092**）
# 本机没有 pwsh 7，scripts/start-*.ps1 在 PS 5.1 下会挂在 JDK 探测上 → 读 .env 注入环境后分离进程拉 java -jar
# .env 的 SHOPPILOT_LLM_DAILY_TOKEN_BUDGET=600000（闸门本体保留；.env.example 注释已同步）
python scripts/eval_autonomy.py                          # 0 token 自检：AUTONOMY SELFCHECK ok=15
python scripts/run_tool_eval.py --autonomy --base http://127.0.0.1:8082 --ops-token dev-ops-token
# 明细 eval/results/tool-eval-<stamp>-dev-autonomy.csv；读数按 ops/circuit.tokensUsedToday 增量算
```

**三个现场追问**：

1. **为什么 PLAN 区的红不算机制缺陷？**——因为同样的 5 条在 run#1 全绿（同集同代码，只差采样），
   而「打满 4 枪」的 `rounds` 区两次跑都稳。把单跑波动读成机制回归，就会得出「4 轮机制坏了」这个与证据相反的结论。
2. **为什么「用尽」档在 dev 上观测不到，仍不判红？**——判据没问题，是**可达性**问题：它要求模型把同一个工具**原样**再试一次，
   而提示词第 4 条教的是「换一种做法」。要这一档可观测，得改集子（用 JVM 桩承载）或改提示词口径——
   两条都超出本票（149 不改判据、不改集子、不改提示词），所以按「未观测/口径张力」登记。
3. **run#1 为什么不留着当读数而作废？**——因为票面把「环境红不进读数」写死了：工单服务没起，5 条 `ticket_ok`
   的红是被测环境不具备，不是被测行为不达标；留作读数会把「我漏起了一个服务」记成「超限路径不落工单」。
   作废不等于删除：明细与报告都留着，且 run#1 的 PLAN 区读数被本票当作方差证据引用。
