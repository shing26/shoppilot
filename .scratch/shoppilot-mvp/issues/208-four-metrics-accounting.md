# 票 208：四项未达标指标的复测、改判与边界登记

Round: 48 补口（spec：[round48-spec-quality-closure.md](../round48-spec-quality-closure.md)）
ADR: 0083　Status: implemented　Blocked by: 无（所有者 2026-11-05 指令：按 TUNING_PLAN 降模型依赖、修红点、重点攻四项指标）

## 背景

所有者点名四项：**缓存率、吞吐、TTFT、退款意图准确率**。本票只做三件事：

1. 用可复跑的实验把每项测清楚，能改判的改判；
2. 不能改判的，把**结构性上限**用产物说死，不再反复“再压一次试试”；
3. 过程中发现的测量污染与启动缺陷，修掉并登记。

不改 gold / 阈值 / `judge()` / 降级枚举 / 指标分母；不改产品行为；不扩验收面。

## 结论先行

| 指标 | 判据 | 本票结论 | 依据 |
| --- | --- | --- | --- |
| 吞吐 | ≥1200 QPS，错误率<0.1% | **达成（复测口径）**：1468 QPS@800、1351 QPS@1200，0 失败 | 轻量发压器；同机同模型 Locust 只有 1015@800，差额是客户端先饱和 |
| 缓存率 | ≥80% | **不可达（结构性）**：任务书 mix80 模型的可缓存占比就是 78.1%，准入内命中率 100% | ADR 0003 只准入政策意图；80% 要求分母里 ≥80% 可缓存 |
| 未命中 TTFT | <500 ms | **不可达（固定项）**：300 Mock + 253–311 embedding + 15 检索 + 104 编排 ≥ 672 ms | 本机 bge-m3 探针；ADR 0011 的生产解法是 embedding 独立批处理 |
| 退款意图 | 分意图 ≥95% | **代码已修、全量读数过期**：定向 18/18；全量 dev 读数早于票 168；local 3B 9/18 是模型不调工具 | `tool-eval-20261027-110143-dev-refund-168b`、`…-114424-local-stage3-local180b` |

## What to build

### 1. 吞吐：把“发压器上限”和“网关上限”拆开

新增：

- `loadtest/AsyncLoadGen.java`：JDK 21 单文件源码启动，`HttpClient` + 虚拟线程；流量池与
  `loadtest/locustfile.py` 逐字相同（hot / para / action / long），支持 `l1|l2|mix80|biz`。
- `scripts/run_async_loadtest.py`：与 `run_loadtest.py` 同一组计数器差值口径，落
  `loadtest/results/ladder-*.csv` 与 `env-*.json`；环境记录带 CPU/内存/commit。

实测（perf 档、mix80、每档 45 s、warmup 5 s）：

| 并发 | 轻量发压器 QPS | P50 / P99 ms | Locust 4 进程 QPS | 总拦截率 | 错误 |
| --- | --- | --- | --- | --- | --- |
| 400 | **1478.00** | 192 / 1710 | 1013.52（历史） | 78.09% | 0 |
| 800 | **1468.44** | 464 / 1975 | **1015.21**（同日复测） | 78.16% | 0 |
| 1200 | **1351.29** | 815 / 2302 | 947.43（历史） | 77.98% | 0 |

同一个网关、同一段时间、同一个流量模型：Locust 1015 @800，轻量发压器 1468 @800。
**差额不在网关，在发压侧**——Locust 的 4 个 gevent 进程与网关抢同一台 16 核机器的 CPU。
历史结论“1200 未达到”是**同机发压器共同上限**，不是网关单独的上限；判据本身没有改。

边界：单次本地复测；宿主上还跑着另一项目的 `nexus-*` 容器（含 2.7 GB 的 nexus-ollama），
CPU 均值 84–92%，不是在空闲机上取的最好值；独立发压节点复核仍留作生产侧动作。

### 2. 缓存率：上限就是可缓存占比

同日 perf 复测（mix80）：总拦截率 **77.69%–78.17%**，而
`l1_delta` 与 `admitted_delta` **逐档相等**（interception_admitted = 1.000）——
**每一个被准入缓存的请求都命中了**。历史产物 `ladder-mix80-perf-20260908-233023-final.csv`
同样是 admitted=1.0、总拦截率 78.07%。

所以 80% 不是“缓存还差 2 个点”，而是分母里另有约 22% 按 ADR 0003 不准入
（10% 业务办理 + 10% 长尾里的动作/升级/未知），要凑到 80% 只能改分母或改准入政策。
ADR 0003 已写“80% 是结果而非目标，低于 80% 可接受，串号 0 次不可协商”；
README 登记为“指标定义与架构决策冲突、裁决挂起（ADR 0030 第 4 条）”。本票不改判，
只把“上限=准入占比”从推断变成产物。

### 3. TTFT：固定项已经超过判据

同日 `python scripts/probe_embedding_latency.py --samples 5`：同句重复 P50 **252.6 ms**、
新问法 P50 **277.3 ms**（报告历史值 311 ms）。按历史归因同构：
300（Mock 首字）+ 253–311（bge-m3）+ 4.8（稠密）+ 5.8（词法）+ 104（编排）=
**672–725 ms**，仍大于 500 ms。

本票**不做**“把 Mock 首字调小”或“把 embedding 挪出统计”的改法。生产侧只有 ADR 0011
写的两条：独立批处理 embedding 服务（GPU 或独立进程 + 请求级 batching），或换更小的
向量模型并重新入库/评测。后者会动检索质量与索引维度，登记为候选票，不在本票执行。

### 4. 退款：代码已修，读数过期；local 的缺口是模型能力

- dev 全量 `tool-eval-20261027-104110-dev-dev-167`：仅 `ACT-RFD-13` 失败（17/18）；
  票 168 的改绑落地后定向 `…-110143-dev-refund-168b` **18/18**。全量表里的 94.4%
  **早于票 168**，不是当前代码的读数。
- local `qwen2.5:3b` 全量 `…-114424-local-stage3-local180b`：ACTION_REFUND **9/18**。
  逐条看：**7 条模型根本没发工具**却在正文里说“已为您提交退款”（ACT-RFD-03/04/05/07/13/14/16）；
  **2 条** `intent_actual=ACTION_ORDER`（RFD-06/18），模型跟着误判意图选了 `queryOrderDetail`。
- 同一两条在 dev 上（`…-dev-167`）`intent_actual` 仍是 ACTION_ORDER，但 qwen-plus 选了
  `applyRefund`，所以通过——差别在模型，不在改绑逻辑。
- 不把改绑扩到 `queryOrderDetail`：`GatewayMainPathJvmTest#writePendingAtBudgetExhaustionFallsBackWithoutAnotherPlanCall`
  明确要求“订单90001申请退款”连跑四轮读工具后转人工；扩面会把这条有意的读后写路径改成
  第一轮写动作，属于产品语义变更，不是修红点。

**结论**：官方 dev 读数要变绿，只能做一次票 168 之后的**全量 180 复跑**（dev 口径，
约 100 万 token，需所有者批准）；local 3B 的 9/18 是“写动作不该交给小模型”的证据，
与 Stage3 路由表把 ACTION_* 留在 dev 的结论一致。

## 顺带修掉的两个缺陷

1. **`start-gateway.ps1 -Profile perf` 静默跑 dev 模式**：`.env` 的
   `SHOPPILOT_LLM_MODE=dev` 是环境变量，Spring 的属性优先级高于 profile 专属
   `application.yml`，于是 `-Profile perf` 只换了 profile，模式仍是 dev。2026-11-05
   实测：第一轮压测实际打了 **qwen-plus，消费 381,709 token**（超过 200,000 默认日预算），
   之后被预算熔断接管，读数全部作废。修法：`start-gateway.ps1` 在传 profile 时把
   `SHOPPILOT_LLM_MODE` 显式设成 profile 里的模式（`perf`/`local`/`dev`/…），与
   `application.yml` 三段 profile 的注释同义。修后用 `/ops/circuit` 验到
   `llmMode=perf`、`llmModel=MockLLM`、`baseUrl=mock://none`。
2. **压测会污染 dev 日预算**：同一个 Redis 键 `shoppilot:llm:tokens:<day>` 被 dev 模式
   真实调用累计；一旦超限，后续请求全部 `LLM_BUDGET_EXCEEDED`，缓存率从 78.1% 掉到
   72.7%。这是测量污染，不是缓存退化。修 1 之后 perf 模式不再记账；
   `docs/loadtest-report.md` 与 EVIDENCE 已写明“跑 perf 前先确认 llmMode=perf”。

## 边界

- 不改 gold / 阈值 / `judge()` / 降级枚举 / 指标分母 / Mock 延迟；
- 不改产品行为、不改 ADR 结论、不启用 Stage3 路由；
- 不把新发压器写成“替代 Locust 的正式口径”：它只回答“客户端是不是先饱和”，
  延迟分桶、派生事件与 SSE 仍以 Locust/`run_sse_ttft.py` 为准；
- 不重置 `shoppilot:llm:tokens:20261105`（381,709 是真实消费记录，不是 Mock 估算）。

## Verify

```bash
python scripts/probe_embedding_latency.py --samples 5
python scripts/run_async_loadtest.py --base http://127.0.0.1:8082 \
    --model mix80 --steps 400,800,1200 --duration 45 --warmup 5
python scripts/run_loadtest.py --model mix80 --profile perf --steps 200,800 --duration 45 --workers 4
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
python scripts/verify_doc_truth.py
python scripts/gen_status.py --check
git diff --check
```

## 验收项

- 三项复测都有产物落在 `loadtest/results/`，CSV 与 env 记录可复跑；
- 吞吐改判只引用新产物，且同时保留 Locust 旧读数与差额归因；
- 缓存率、TTFT 写明“不可达”的结构性理由，不改判据、不换分母；
- 退款只给“代码已修/读数过期/需付费复跑”三句话，不把定向 18/18 写成全量；
- `-Profile perf` 修复后 `/ops/circuit` 显示 `llmMode=perf`，且 perf 压测不再增加
  `tokensUsedToday`。

## Handoff notes

**关键决策**

1. **改判吞吐、不改判缓存与 TTFT**。吞吐的差额已被同日对照实验定位到发压侧
   （Locust 1015 vs 轻量 1468，同网关同时段），属于测量修正；缓存与 TTFT 的差额
   分别在 ADR 0003 的准入政策和 ADR 0011 的 embedding 路径上，改代码或改口径都是假绿。
2. **不扩退款改绑面**。票 168 只改 `queryLogistics` 是有测试依据的：ACTION_REFUND +
   显式退款短语在预算耗尽路径上必须允许四轮读工具；扩到 `queryOrderDetail` 会与
   `writePendingAtBudgetExhaustionFallsBackWithoutAnotherPlanCall` 冲突。
3. **发压器只做交叉验证，不替代 Locust**。轻量器没有 Locust 的分桶与派生事件，
   头条 QPS 可信，延迟分桶仍以既有 SSE/压测脚本为准。
4. **`.env` 不覆盖显式 profile 的模式**。这是启动器的契约修复：调用方说 `-Profile perf`
   就必须是 perf；否则压测会花真钱且读数作废。

**验证落点**

- 轻量：`loadtest/results/ladder-mix80-async-perf-20261105.csv`（400/800/1200 →
  1478.00 / 1468.44 / 1351.29 QPS，0 失败，拦截率 77.98–78.16%）；
  env：`loadtest/results/env-mix80-async-perf-20261105.json`。
- Locust 同日：`loadtest/results/ladder-mix80-perf-20261105-184459.csv`
  （200 → 764.52、800 → **1015.21** QPS，拦截率 78.17% / 77.69%）。
- 作废的 dev 模式读数仍保留在 `…-183110`、`…-183910` 与 `ladder-mix80-async-20261105*.csv`，
  文件名与报告里标明“dev 模式、预算熔断后”，不删，避免把事故洗掉。
- 退款：dev 全量 `tool-eval-20261027-104110-dev-dev-167.csv`（仅 RFD-13 红）、
  定向 `…-110143-dev-refund-168b-summary.csv`（18/18）、local `…-114424-local-stage3-local180b-summary.csv`（9/18）。
- 启动器修复后 `/ops/circuit`：`llmMode=perf`、`llmModel=MockLLM`、
  `llmBaseUrl=mock://none`；`tokensUsedToday` 保持 381,709 未再增长。

**三个现场追问**

1. **为什么 381,709 token 的事故没在开始前发现？** 因为网关日志只打
   “profile is active: perf”，模式是另一层配置；`/ops/circuit` 才暴露 `llmMode`。
   以后跑 perf 前先读一次 `/ops/circuit`，或直接看 `llmModel` 是否为 `MockLLM`。
2. **轻量发压器的 1468 能当生产容量吗？** 不能。它证明的是“这台 16 核机器上的网关
   在 78% 拦截率下能过 1200”，不是生产容量；生产要独立发压节点、真实 embedding 服务
   与真实模型，按 ADR 0011 分层测。
3. **想把缓存率推到 80% 该动哪里？** 先做所有者裁决：要么把判据改成“准入内命中率”
   （当前 100%），要么放开某类只读动作进缓存（会碰 ADR 0003 的 fail-closed 与串号
   防线）。两者都不是本票能自行决定的。
