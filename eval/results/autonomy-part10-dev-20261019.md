# part10 自主性评测 · dev 档活体跑（票 149，2026-10-19）

**归因**：`promptVersion = v1.2.0`（票 147；与 v1.1.0 的读数不可混比）、`max-tool-rounds = 4`（票 143）、
反思分支 + 观测面（票 144/145）、写守卫行业门（票 146）。代码 = HEAD `5831262`（round35 机制层 143-148 收口后的那一版）。
**档位**：dev（qwen-plus @ DashScope），日预算 `SHOPPILOT_LLM_DAILY_TOKEN_BUDGET=600000`（票 149 抬幅；ADR 0012 闸门本体保留）。

**读数口径**：计费 token 走 `ops/circuit.tokensUsedToday` 增量（= Redis `shoppilot:llm:tokens:<day>`，
与 `shoppilot_llm_tokens_total` 同源），**不读响应 token 字段**（R12 三处缺口；票 145 已回填，但评测列不依赖它）。
逐条明细见同目录 `tool-eval-<stamp>-dev-autonomy.csv`（`billed_tokens` / `latency_ms` / `induction` / `state` 都在里面）。

## 一、这一轮为什么跑了两次

| 轮次 | 落点明细 | 计费 token | 墙钟 | 判定 | 处置 |
| --- | --- | --- | --- | --- | --- |
| run#1 | `tool-eval-20261019-084356-dev-autonomy.csv` | **245,452** | 238 s | 18 红 / 130 判 | **作废**（见下） |
| run#2 | `tool-eval-20261019-085417-dev-autonomy.csv` | **222,276** | 211 s | 18 红 / 130 判 | **本票登记读数** |

run#1 作废的**唯一理由**：**工单服务（`:8092`）当时没起**——网关的降级/超限路径要经它落工单，
于是 5 条超限用例的 `ticket_ok` 全红（`AUT-RD-01..05`）。这一条按票 149 的口径属于「环境红不进读数」，
不是机制读数。修法是启动 `shoppilot-ticket` 后重跑（run#2 里 RD 区 **5/5 全绿**，反证 run#1 的红确系环境）。
run#1 仍然保留为**方差参照**：它的 PLAN 区 15/15 全绿，而 run#2 同区 5 条红——同一个集、同一版代码，
两次跑的分歧点见 §四。

当日额度：两次跑批 + 预检冒烟共 **470,037 / 600,000**（run#1 245,452 + run#2 222,276 + 冒烟 2,309；
另有一次手工复现探针被**请求级幂等回放**吃掉，计费 0）。

## 二、run#2 读数（本票登记）

- **30 条全跑完**，无 429、无非 200、无 `error` 行（`grep -c ERROR`=0）。
- **计费 token 222,276**（逐条均值 7,409；最小 3,071 / 最大 12,571；CSV 合计与计数器增量逐条吻合）。
- **时长**：墙钟 **211 s**（08:50:47→08:54:17，含逐条 0.25 s 节流与逐条前置复位）；请求耗时合计 199 s，单条最大 12.2 s。
- **三态**：**触发 7 / 不触发 23 / 次数用尽 0 / 未观测 0**。
- **判定**：130 条断言，**红 18**，未观测 5。

| 分区 | 用例 | 红（用例） | token |
| --- | --- | --- | --- |
| `plan`（动态规划 15 = 8×4 轮链 + 7×2 步链） | 15 | 5 | 120,023 |
| `reflection`（反思 10 = 5 触发 / 3 不触发 / 2 用尽） | 10 | 7 | 50,656 |
| `rounds`（超限 5 = 3 读 + 2 写挂起） | 5 | 0 | 51,597 |

## 三、18 条红按成因分三簇（每簇都有证据）

**① PLAN 区的 5 条是模型方差，不是机制缺陷（证据 = 两次跑的分歧）**：
`AUT-PLAN-01/06/10/14/15` 期望 4 轮链（或 2 步链），run#2 里模型只打了 1 枪就收尾（`steps=1`）。
同一集、同一版代码，**run#1 这 5 条全绿**——所以它量到的是 dev 档模型的采样波动，不是 4 轮机制坏了。
（8 条 4 轮链在两次跑里共 16 次机会，只有 5 次没走完；`rounds` 区的 5 条「打满 4 枪」在 run#2 **全绿**。）

**② 反思档 4 条（`AUT-REFL-02..05`）：机制触发、模型不跟进**：
窗口故障确实立起来了（`induction=window`），反思计数也确实 +1（`state=triggered`），
但模型拿到失败上下文后**没有再打一枪**（`rounds=1`、`plan[]` 为空，回答是「系统暂时无法查询…」）。
即：**反思是系统行为（已实现、可观测），"换一种做法再试一次"是模型行为（dev 档不跟）**。
5 条触发档里只有 `AUT-REFL-01` 走完「失败→重试→成功」（`rounds=2`、`steps=1`）。

**③ 用尽档 2 条（`AUT-REFL-09/10`）：到不了"同工具第二次失败"**：
持久故障下模型第一次失败后，要么直接收尾（-09，`rounds=1`），要么换工具再试（-10，`rounds=2`，两次是**不同**工具）。
而「次数用尽」的判据要求**同一个工具第二次③失败**（键 = `ToolName`）——prompt 第 4 条教的是「换一种做法（换工具或换参数）」，
两者方向相反，所以 dev 档上这一档**结构上很难被触发**（`state_exhausted=0` 的成因，不是计数没接线：
`shoppilot_reflection_total{outcome="exhausted"}` 的接线有 JVM 用例钉着）。
**登记为口径张力**：想观测「用尽」，要么模型愿意原样重试同一工具，要么该档改由 JVM 桩承载。

**④ 写工具档 1 条（`AUT-REFL-08`）：被幂等回放挡住，本轮不可观测**：
该用例是「不做任何前置查询、直接给 90001 提交退款」（期望：写工具③失败 → 立即降级 + 落工单）。
实测拿到 `plan[].status=IDEMPOTENT_REPLAY`——**同一批里更早的 `AUT-PLAN-05`/`AUT-PLAN-11` 已经用同一单同一参数提交过退款**，
而派生幂等 token 只看 `tenantId|customerId|tool|参数`，与措辞无关；复位只清 biz-mock 的库，**不清 Redis 回放索引**
（6 h TTL；这正是 round21 票 61 登记过的那个陷阱）。手工复现的 trace 原文：
`TOOL_EXEC APPLY_REFUND=IDEMPOTENT_REPLAY modelArgs={"orderNo":"90001"}`。
→ **集子的隔离缺陷（登记给 148 面）**：写同一单同一参数的用例在单轮里天然相撞；
修法二选一：写类用例换不同单号，或演示复位连 Redis 回放索引一起清。

## 四、与 140 的预估差

- 140 的 AFK 实测外推：**单跑 ≈ 29 万 token**（30 条、均值 ≈9.6k）。
- 实测：run#2 **22.2 万**（均值 7.4k）、run#1 24.1 万（均值 8.0k）——**比预估低 23%~17%**。
- 差异的成因（两条都来自本次读数）：① 反思档与"模型提前收尾"的用例**比设计少打 1~3 枪**，token 随之降低
  （<4k 的用例共 10 条）；② 4 轮链满配时单条 12~12.6k，与预估的 11.5k 上界一致。
- 预算结论：**600k 够「一次全跑 + 一次复跑」**（实测两跑 46.8 万；`dailyTokenBudget` 读数为 600000）。

## 五、本轮在 148 的模块上修的三处（都是"只有活体才暴露"的）

1. **运维面的两个头**：`AuthFilter` 对非静态路径一律要 bearer，`X-Ops-Token` 单独带会被 401
   （首跑实测：`/api/v1/support/ops/circuit` → `401 missing bearer token`）。新增 `_ops_headers()` 统一带两个头。
2. **故障面是 `PUT`**：`/ops/fault` 的写是 `PUT`（`OpsController`），而共享 HTTP 助手只会发 POST/GET →
   405。给 `run_tool_eval.http_json` 加可选 `method=` 参数（不给就保持原行为），`_set_fault` 显式 `method="PUT"`。
3. **逐条前置复位**（票 149 的 `What to build` 口径）：跑批前复位一次之外，**每条前再复位一次演示固定单**
   （实测 0.03 s/次，不打模型、不进成本列；失败只警告不掀翻整批）。

## 六、复跑配方

```powershell
# 0) 起齐三个服务（本机没有 pwsh 7，start-*.ps1 在 PS 5.1 下会挂在 JDK 探测上；用 .env 注入 + 分离进程拉 java -jar）
#    网关 profile=dev（qwen-plus），另需 biz-mock(:8091) 与 **工单服务(:8092)**——这次 run#1 就是漏了后者
# 1) .env: SHOPPILOT_LLM_DAILY_TOKEN_BUDGET=600000
python scripts/eval_autonomy.py                          # 0 token 自检，期望 AUTONOMY SELFCHECK ok=15
python scripts/run_tool_eval.py --autonomy --base http://127.0.0.1:8082 --ops-token dev-ops-token
#    明细落 eval/results/tool-eval-<stamp>-dev-autonomy.csv；读数按 ops/circuit.tokensUsedToday 增量算
```

**边界（照登）**：① 单跑读数带模型方差，PLAN 区两次跑会差 5 条；② 「用尽」档在 dev 上结构性地难触发（§三③）；
③ `AUT-REFL-08` 受同批写用例的幂等回放影响（§三④），要观测它得先解决集子隔离；
④ 本目录是 add-only 证据，历史明细不删。
