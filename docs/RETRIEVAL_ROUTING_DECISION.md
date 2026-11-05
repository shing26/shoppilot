# 检索集与模型路由：round48 的决策与未决项

> 票 206 / ADR 0083。**本文只做决策与登记，不改生产默认值、不改阈值、不改语料。**
> 依据：`docs/retrieval-quality-metrics.md`（round26 的 10 条实测）、`docs/retrieval-comparison.md`（16 条）、
> `docs/STAGE3_MODEL_ROUTING.md`（180 gold 的离线路由表，2026-10-27）。
> 本轮新增的检索集：`eval/retrieval-cases.json`（120 条），结构由 `scripts/verify_retrieval_cases.py` 守。

## 一、检索集：为什么是 120 条，以及它测不出什么

`docs/retrieval-quality-metrics.md` 的结论只有一句是硬的：**10 条上三臂 hit@5 都饱和**，
所以「稠密更好 / 混合更好」都测不出来。它自己写明了缺什么——「人工标注的百条量级」。
本轮把这块补上：

| 项 | 取值 |
| --- | --- |
| 规模 | **120 条**（规格要求 100–200） |
| 覆盖 | **37 / 37** 份 `knowledge/*.md`，每份 2–3 条 |
| 分片 | dev 78 条 / holdout 42 条（35.0%，要求 25%–35%） |
| 意图分布 | FRESH 26 / PROMO 26 / RETURN 26 / SHIPPING 23 / HOTEL 19 |
| 租户 | 平台级文档用 T001 发起；店铺级文档只能用归属店（T002 死蟹、T004 酒店、T001 会员日） |
| 标注 | `expect_doc` 由作者按文档**标题与小节标题**拟定，**未看任何一路检索结果** |

**为什么这不算循环标注**：既有的 10 条是「先看两路结果不同，再挑查询」，对本轮要判的
单/双引擎问题天然有利。这 120 条按文档主题反推用户会怎么问，标注与排序无关；
`verify_retrieval_cases.py` 还专门禁止查询原样抄标题或小节标题，避免把「复制粘贴」当召回。

**结构校验（0 token，已进 CI）**：

```bash
python scripts/verify_retrieval_cases.py
```

它守六件事：规模与 id/query 唯一、`expect_doc` 真实存在、意图与文档主题一致、
店铺级文档的租户自洽、每份文档 ≥2 条且 dev 里至少有 1 条、查询不是标题的复制。

**录制（需要活体栈：网关 8082 + ES + Qdrant）**：

```bash
python scripts/record_retrieval_fixture.py --cases eval/retrieval-cases.json --out eval/retrieval-fixture-<日期>.json
```

`--cases` 把 `expect_doc` / `split` / `tenant` 一并写进夹具；**默认不带该参数时仍录原来那 10 条**，
因此既有夹具与 `RETRIEVAL_FIXTURE_SHA256` 哈希钉零变化。录完夹具后，
`RetrievalQualityMetricsTest` 会自动发现「自标注夹具」并把三臂的 hit@5 / MRR@5 打到构建日志；
它**不会**改写 `docs/retrieval-quality-metrics.md`——那份表仍对应 10 条老夹具。

### 这套数据测不出什么（先写，避免事后解释）

1. 它测「该文件在不在 top-5」，不测「答案对不对」，也不测跨文档综合；
2. 标注由一人完成、未盲化，同义改写的查询仍可能偏向稠密；
3. 120 条能做「两臂差 ≥2 条命中」这种量级的判断，**做不了显著性检验**；
4. 语料只有 37 份，规模远小于真实店铺知识库，排序结论不能直接外推到生产分布。

### 首次录制（2026-11-05，活体栈）

夹具已录，落点 `eval/retrieval-fixture-20261105.json`（sha256 见下）。录制环境：
本机 `scripts/up.ps1 -Profile local` 起栈（网关 8082 + ES 19200 + Qdrant 16333 + 本机
`bge-m3`），知识库 `kb_epoch=6`、37 篇 / 111 块，语料 sha256
`e37286406bc77f0b31273457693b33f5ae7b95daddd7ca2ad5df2ba3a4c58ee5`。

```bash
python scripts/record_retrieval_fixture.py --cases eval/retrieval-cases.json \
  --out eval/retrieval-fixture-20261105.json
```

录制器对每条用例连发两次探针并强制三项干净前提（两路非空且不降级、未走意图兜底、
两次序一致）；120 条全部通过。夹具 sha256
`8c160439225fae5390dcbb386586dd3a73baa5d93477d496213bf9be3d4e5de1`。

**三臂读数**（同一把尺子：三臂都取 top-5；`expect_doc` 由生产切分器映射回源文件）：

| 分片 | 条数 | BM25 hit@5 / MRR@5 | 稠密 hit@5 / MRR@5 | 混合 hit@5 / MRR@5 |
| --- | --- | --- | --- | --- |
| 全部 | 120 | 119/120 / 0.911 | 120/120 / 0.992 | 120/120 / 0.976 |
| dev | 78 | 77/78 / 0.893 | 78/78 / 0.994 | 78/78 / 0.963 |
| **holdout** | **42** | 42/42 / 0.946 | **42/42 / 0.988** | **42/42 / 1.000** |

复现：`./mvnw.cmd -B -ntp -pl shoppilot-gateway -am -Dtest=RetrievalQualityMetricsTest test`
——自标注夹具由测试自动发现，读数打在 surefire 报告里，**不改写**
`docs/retrieval-quality-metrics.md`（那份仍是 10 条老夹具的口径）。

### 这份夹具没进 `retrieval_gate.py`，原因是契约不同

`retrieval_gate.py` 的前提是**单租户 + 一份可见语料 + 用 yml 常数复算融合序**，
所以它要求夹具带一个顶层 `tenant`。120 条集横跨 T001/T002/T004，没有唯一的
`corpus_sha256`——拿它过那道门只能靠伪造顶层租户。已改为：门禁**显式跳过**带
`expect_doc` 的自标注夹具（不再靠文件名排序碰巧选中旧夹具），单租户融合门继续钉
`retrieval-fixture-20260928.json`；120 条集由 `RetrievalQualityMetricsTest` 守结构与读数。

## 二、单引擎还是双引擎：本轮结论是「继续双引擎」，但判据先写死

这里的「双引擎」沿用 `ARCHITECTURE.md` 的定义：

- 稠密：Qdrant + bge-m3 向量；
- 稀疏：Elasticsearch BM25；
- 融合：RRF（`rrf-k` / `dense-top-k` / `lexical-top-k` / `fused-top-k` 从 `application.yml` 现读）。

### 已有证据

| 数据 | hit@5 | MRR@5 | 读法 |
| --- | --- | --- | --- |
| 16 条对比（`retrieval-comparison.md`） | 三臂 16/16 | 未计 | 指标饱和，无判别力 |
| 10 条（`retrieval-quality-metrics.md`） | BM25 10 / 稠密 10 / 混合 10 | 0.875 / 1.000 / 1.000 | 混合不比稠密差，但也没更好 |
| 10 条 top-1 来源 | — | — | 混合与稠密 top-1 相同 10/10；其中 **8 条由 BM25 提供** |

**结论句必须按实测写**：现有数据支持「混合没有更差」，**不支持「混合更好」**。
但 top-1 来源那张表说明另一件事：稀疏路在这 10 条里贡献了 8 个第一名，
去掉 BM25 等于把这块能力直接丢掉，而没有证据说稠密能补上。

### 决策

1. **继续保留双引擎，本轮不移除任何一路。** 依据是「融合从未更差 + 稀疏路是 top-1 的主要来源」，
   而不是「融合更好」。
2. **不把融合说成收益。** 对外文档、汇报与 ticket 里不得写「混合检索提升命中」这种句子，
   除非 120 条 holdout 达到下面的判据。
3. **降级路径不变**：ES 不可用时按既有 `degraded` 语义走稠密单路，不新增静默兜底。

### 预先登记的判定规则（先写，再看结果）

用 120 条的 **holdout 42 条**判定，主指标 MRR@5，副指标 hit@5（三臂同深 top-5）：

| 观测 | 动作 |
| --- | --- |
| `fused` 比 `dense` 高 ≥0.02 MRR 或 ≥2 条 hit@5 | 记为「双引擎有增益」，写进 `retrieval-quality-metrics.md` 的换代版 |
| 两者差在 ±0.02 MRR 且 hit@5 差 ≤1 条 | 维持现状：双引擎「不更差」，不宣称增益 |
| `fused` 比 `dense` 低 ≥0.02 MRR 或 ≥2 条 hit@5 | **开票评估单稠密**：融合没带来命中，却多一次召回与融合计算 |
| `dense` 单独 ≥ `fused` 且 ≥ BM25，且 holdout 上稠密每类都不掉队 | 同上，进入「单稠密 + ES 仅作降级」评估 |

这些阈值是**决策阈值**，不是评测验收线：它们不替换 gold、`judge()`、降级枚举或任何入库指标的分母。
规则先于数据写在这里，是为了避免录完之后照着结果改判据。

### 按预登记规则判定（2026-11-05，holdout 42 条）

| 比较 | holdout | 全部 120 | dev 78 |
| --- | --- | --- | --- |
| `fused` − `dense` MRR@5 | **+0.012** | −0.016 | −0.031 |
| `fused` − `dense` hit@5 | 0 条 | 0 条 | 0 条 |

- **判定集（holdout）落在第二行**：差 +0.012 MRR（< 0.02），hit@5 持平 → **维持双引擎，
  不宣称增益**。判定阈值与判定集都没有事后调整。
- **但 dev 的符号相反**：78 条上混合比稠密低 0.031 MRR，超过预登记的 0.02 阈值。
  这条必须写出来，不能只引用 holdout 的 1.000 就收口：两份分片给出相反方向，
  说明 120 条这个量级**分不清「融合无增益」和「融合偶有损秩」**。全部 120 条上差
  −0.016，仍在阈值内，因此动作仍是「维持」，但它已不是「没有坏处」的证据。
- **不改默认值的理由没有变**：稀疏路在既有 10 条里贡献 8 个 top-1；新集 BM25 只丢 1 条
  dev 用例（119/120），三臂 hit@5 的差距是 1 条。在只有一份本地录制、单人非盲标注、
  无显著性检验的情况下，删 ES 得不到支持。
- **下一轮若要动单稠密，必须先重录一份全新 holdout**（当前 holdout 已经用掉），
  并按同一套阈值判定；不能在已经看过的 42 条上反复调参再报告。

### 本轮没做的事

- 不改 embedding 模型、不改 `rrf-k`、不改 top-K；
- 不因为 10 条数据就删 ES 依赖或删 BM25 代码；
- 不把上面任何一行写进 `docs/retrieval-quality-metrics.md`（那份仍是 10 条的历史读数）。

## 三、Stage3 按意图路由：默认不变，影子观测已落地（只记账）

`docs/STAGE3_MODEL_ROUTING.md` 的离线路由表（180 gold，2026-10-27）：

| 组 | 建议 | 质量 | 代价 |
| --- | --- | --- | --- |
| ESCALATE | 确定性出口，无模型 | 100% vs 100% | 无 |
| POLICY_* + UNKNOWN | local `qwen2.5:3b` | 六组 100% vs 100% | 平均延迟 3.3s → 10.1s，最慢 15.4s |
| ACTION_* | dev `qwen-plus` | local 仅 50–94.4% | 保留云端 token |

收益：政策组 126 条省 **214,616 token / 180 条 = 当前 dev 总量的 40.2%**。

### 决策

1. **生产默认模型与默认 LLM 模式本轮不变**，按意图路由**默认关闭**。
   理由不是「收益是假的」，而是三项边界都还没过：
   - 延迟 3.3s → 10.1s，最慢 15.4s，产品 SLA 未确认；
   - 质量证据只有 180 gold 单集（UX30 30/30 只是旁证），没有线上分布；
   - 本地 3B 在 CPU 上单并发，并发容量没压测。
2. **影子观测已实现，默认关闭（round48 补口）。** 开关 `shoppilot.routing.shadow`（环境变量
   `SHOPPILOT_ROUTING_SHADOW`）默认 false；开启后只在**模型路径**记一条
   `shoppilot_routing_shadow_total{intent_class,suggested,actual,agree}`，不改变调用的模型、
   提示词、工具面与 SSE 事件。**原设计里的审计字段没有实现**：那会改审计契约，
   而计数器已经能回答「建议路由与实际模式是否一致」，代价更小；这条取舍如实记在这里。

### 影子观测的实现（round48 补口，默认关闭）

- 开关：`shoppilot.routing.shadow`，**默认 false**，与真正的路由开关分开（目前也没有真正的路由开关）；
- 每轮在既有意图判定之后、进入模型路径时，按路由表算出「若开启会走哪个模型」，
  **不改变实际调用的模型**；缓存命中与转人工不记（它们不选模型）；
- 落点：`shoppilot_routing_shadow_total` 一个计数器，标签 `intent_class`（policy/action/unknown）、
  `suggested`（local/dev/none）、`actual`（当前 `shoppilot.llm.mode`）、`agree`；
  不新增日志正文、不改审计契约、不改 SSE 事件；
- 一致性：ESCALATE 不需要模型，记 `suggested=none` 且一律 `agree=true`；其余按
  实际模式与建议是否相同判；demo/perf/replay 不算真实模型路由，一律记不一致；
- 关闭时零开销：不建计数器、不做意图映射。

### 影子转正的门槛（先写）

1. 影子日志覆盖 **≥200 轮真实政策流量**，且两类意图都有样本；
2. 用同一套 180 gold + UX30 复跑，policy 组 local 质量仍在 100%（不低于 dev 2 个百分点）；
3. 产品所有者确认政策链路可接受 10–15s 的 p95；
4. 本地容量压测通过（并发下不排队到超时）；
5. 四条都满足才开票改默认路由；任一条不满足就维持默认，影子继续记录。

## 四、这套决策的边界

- **检索侧的新读数是 2026-11-05 那一次本地录制**：120 条、单机、单次、单人非盲标注，
  环境是 `kb_epoch=6` 的 37 篇语料；它不是生产分布，也不构成显著性证据。路由侧仍只有
  2026-10-27 的离线表。
- **不修改任何既有文档的读数**：`retrieval-quality-metrics.md`、`retrieval-comparison.md`、
  `STAGE3_MODEL_ROUTING.md` 均保持原样，本文只引用；新数字只落在本文件与
  `docs/EVIDENCE.md`。
- **夹具是 append-only 的**：`eval/retrieval-fixture-20260928.json` 与它的 CI 哈希钉不变，
  新集单独一份文件；两份夹具的契约不同（单租户融合门 / 多租户自标注质量集），
  不要合并。
- **单稠密评估需要新 holdout**：当前 holdout 已用于本次判定，不能在同一批 42 条上
  调参后重新宣称结论；重录要单独开票（需要活体 ES/Qdrant），按 frontier 规则领取。
