# round22 规格：RAG 的机器背书

> **状态（2026-09-28）**：**round22 已开轮**（ADR 0048 = 轮范围与逐票依据，ADR 0049/0050/0051 = 逐特性契约与取舍）。
> 票 **64-68 开放**，范围与口径见本 spec §2/§3/§4；**ticket 文件在领取时按仓库形制拆出**
> （与 round21 同例：spec 即其 Why 与 How）。
>
> 产生方式：承接 ADR 0046 的 Consequences（round21 只做「闭环最后一公里」，机器背书那一格登记给本轮）
> → 沿 round21 spec §3 里已写好的候选 → 本次开轮用 `/grill-with-docs` 走决策树 → 四份 ADR + 本 spec。
> **§4 是本次开轮的裁定记录**：第 1 项开轮时由所有者确认，第 2-6 项先按 `best judgment` 落成默认值、
> **收口后已由所有者确认「五项全照默认」**（2026-09-28），故现均为有据的裁定。

**基线**：round21 收口落点（`HEAD` = round21 收口那一笔），JVM `5 + 29 + 290 = 324` 绿；
覆盖率 gateway 59.75% / biz-mock 79.30% / tool-api 47.95%（门槛 54.0/76.0/40.0）；指标名 **53**；
全量矩阵 23 步 606 s / 21 绿 2 红（`feedback`、`plansteps` 两条登记红）。

---

## 1. 这一轮不再补「闭环」，补的是「谁在机器上守着这条链」

round21 补上了闭环的最后一公里（退款闸门 + 幂等重放）。本轮补另一格：**最强的子系统进 CI 覆盖为 0**。

三条取证（逐条对仓核实，见 ADR 0048）：

- `eval_suites.py` 的 24 条夹具四个 kind 都不读 `ruleIds`/RRF 序 → **改坏融合，没有一条断言会红**；
- 唯一的 RAG 质量证据 `retrieval-comparison.md` 的 16 条查询 `dense` 与 `hybrid` 名次全同 → 对 RRF 回归判别力为零；
- 评测面四列都是单维请求质量，**没有一列是端到端终局** → 「四列全绿而任务没办成」这种形态抓不到。

这三条都不是「事实错误/回归/崩溃」，也没命中 ADR 0030 的全部五条触发线；本轮按 **ADR 0048 的逐票依据**分两类处置（见 §2）。

---

## 2. 票号、依据与顺序

| # | 票 | 依据 | Blocked by | ≤1 天 |
|---|---|---|---|---|
| 64 | 检索融合 0 token 录放回归门 | **政策越过**（**不是**触发已到，见下） | — | ✅ 1d |
| 65 | task-level 判据最小形态 | **触发已到**（round19 登记第 4 项） | — | ✅ 1d |
| 66 | token 计量加 `source` 标签 | **政策越过** | 57（已收口） | ✅ 0.5d |
| 67 | 告警最小集 + `promtool test rules` | **触发已到**（ADR 0030 第 5 条） | — | ✅ 1d |
| 68 | round22 收口 | — | 64-67 | ✅ 0.5d |

**小计 ≈ 4 人日。** 建议顺序 **65 → 67 → 64 → 66**（先把不需要新基建、也不需要新夹具的两票做掉；66 最弱放最后，必要时可无损删）。

> **票 64 属政策越过的特别说明**：`round19-spec-trust-observability.md:132` 的触发原文是「出现可离线复跑的录制/回放路径」，而**建这条路径就是票 64 本身** —— 引自己当依据是循环论证，收口时不许讲成「登记第 5 项的触发已成立」。

---

## 3. 逐票规格

### 票 65 — task-level 判据最小形态（依据 ④：触发已到）

**要解决什么**：四列全绿而任务没办成。判据面只看三个既有字段（`plan[]` / `context.ruleIds` / `fallbackReason`），零新增后端代码、零新增探针。

```
kind_task == "action"   → plan 里 target tool 的 status == OK 且无 fallbackReason
kind_task == "policy"   → context.ruleIds 非空 且无 fallbackReason
kind_task == "escalate" → fallbackReason == USER_REQUESTED
plan 缺失（离线明细无该字段） → None = 未观测
```

**聚合**：单独报 `task_done` 一列，**不给总分、不并入四列任何百分比**；`unverifiable` 条数与比率一起报。

**落点**：`scripts/eval_task.py`（`eval_suites.py` 形态）+ `eval/cases-part8-task.jsonl`（add-only）+ CI 一个 0 token 步。**不并进 `eval_suites.py`**（`round19-spec-…:131` 逐字要求新模块；也保护现成 24 条夹具所在文件）。`part8` 不进 `GOLD_CASE_FILES`，故 rescore 差异集合不变。

**Verify**：`python scripts/eval_task.py`（夹具自检 0 token）；`python scripts/verify_eval_judge.py`（40/40）；`.\mvnw.cmd -B -ntp verify`。

**验收项**：① 反例 A（四列绿、任务红）与反例 B（工具列红、任务绿）各有用例；② `plan` 缺失记 `None` 而非 `False`，且 `unverifiable` 计数与比率都报；③ 不动 gold、不动 rescore 差异集合（仍恰 4 条）。

---

### 票 67 — 告警最小集 + `promtool test rules`（依据 ④：触发已到）

**要解决什么**：本仓零告警规则；而 ADR 0024 排除它的理由（「证不了该响时会不会响」）被 `promtool test rules` 直接推翻 —— 见 ADR 0051。

**落点**：告警规则文件（4 条，引用真实指标名）+ `promtool` 规则测试（合成序列，断言该响时响、不该响时不响）+ CI 新一步（`curl` 固定版本二进制 + sha256 校验）。

**4 条规则**：`shoppilot_dependency_up == 0`；`shoppilot_circuit_state == 1`；`rate(tool_timeout)+rate(tool_unavailable) > 0`；`rate(fallback)/rate(requests) > 0.2`。

**Verify**：`promtool test rules <rules> <test>`（0 token，离线）；`promtool check rules`。

**验收项**：① 每条规则都有「该响」与「不该响」两侧断言；② 规则引用的是**真实存在的指标名**（改名即红）；③ **不得声称生产会响**（README/EVIDENCE 措辞守住）。

**真实代价（写进 ADR 0051）**：往刻意零依赖的 CI 子集里引入一个需下载的二进制，局限在一个步骤内、可 pin 可审。

---

### 票 64 — 检索融合 0 token 录放回归门（依据 ③：政策越过）

**要解决什么**：给「双路召回 → RRF 融合 → 取 top-5」装确定性回归门，覆盖那条**改坏没人会红**的链。

**诚实分界**：它守的是**排序流水线的确定性与四个常数、语料 sha、夹具完整性**，**不覆盖**活体 hit@5 准确率，**因此不闭合 round19 登记第 5 项**（ADR 0049 的 Consequences 已写明；收口时不许讲成「活体数字进 CI 了」）。

**落点**：
- 层一（JVM，唯一 owner）：`RetrievalFusionReplayTest` —— 由夹具的 `dense`/`lexical` 重算融合，断言 top-K 前缀 == `expected_fused_topk`；四个常数从**生产 `application.yml`** 绑定；`corpus_sha256` 现算相等。为此 `rrf()`（`HybridRetriever:198`）改**包级可见**（零行为改动）。
- 层二（Python，不复算）：`scripts/retrieval_gate.py` 只做夹具 schema、`expected ⊆ dense ∪ lexical`、sha 与 CI pin 校验。
- 夹具：`eval/retrieval-fixture-<date>.json`（append-only 家族），录制用现成 `/ops/retrieval` 探针（`OpsController.java:268`，一次调用同时返回 `denseTop`/`lexicalTop`/`fusedTop`）；pin `llm_mode`/`kb_epoch`/`corpus_sha256`/四个常数。**查询集必须新增能造分歧的 case**（跨店近重复条款、同主题相邻块），原 16 条对融合是 no-op。
- CI：插在「套件夹具 / task 判据」后、「覆盖率棘轮」前 —— **收口时实测是第 6 步 / 共 8 步**（起草时按「现五步 → 共 6 步」算，漏了同一轮票 65 与票 67 各加的那一步）；`ci-subset.yml` 加 `env: RETRIEVAL_FIXTURE_SHA256`（哈希钉）。
- 并入 `CODE_MAP.md:91` 已挂的债：`MarkdownChunkerTest` + `HybridRetriever` 融合用例同交付，**不单独开补测票**。

**Verify**：`.\mvnw.cmd -B -ntp verify`（含新用例）；`python scripts/retrieval_gate.py`；CI 第 5 步。

**验收项**：① 只改 `expected` 必红（重算式断言）；② 喂打乱后的输入必报 mismatch（反证夹具）；③ 改 `application.yml` 的任一常数必红、改任一 `knowledge/*.md` 必红（sha）；④ 不新增任何 main 类。

---

### 票 66 — token 计量加 `source` 标签（依据 ③：政策越过）

**要解决什么**：`shoppilot_llm_tokens_total` 的三个来源计量方法不同（`MockLlmClient` 估算、两个真模型客户端用 provider 真值）却共用一个名字。模式在部署期固定，所以同序列内不混方法 —— 缺的是**口径声明的机器可读性**。

**落点**：给现有计数器加 `source=provider|estimate` 标签（**名字不变 → 指标名计数不变**），照票 47 的 `result` 标签先例；**Mock（perf 档）记 `estimate`、Ollama（local 档）与云端（dev 档）记 `provider`**。

**红线**：不改 `TokenBudget` 的放行语义。**本票是本轮最弱的一票，可无损删。**

**Verify**：`.\mvnw.cmd -B -ntp verify`；`python scripts/check_coverage.py`。

**验收项**：① dev 与 perf 两档的 `source` 取值可分辨；② 指标名计数不变（现算仍为 53）；③ 预算放行行为逐字不变（`TokenBudgetTest` 原样通过）。

---

### 票 68 — round22 收口

spec 登记节、`docs/EVIDENCE.md` 新读数、tracker round 表与票索引、`docs/CODE_MAP.md`（`knowledge` 与 CI 步：五步 → 八步）、
**指标名重算并按仓库家法写换代指针**、收口审计 `ROUND_FP`/`G6_EXPECT` 换代。

**注意**：收口时 `G6_EXPECT` 要按**现场 surefire 总数**重算，且必须跑**一次带 build 的矩阵**（审计 G6 读 `logs/acceptance/{build,unit}.log`；`-SkipBuild` 会让它读到旧日志而红 —— round21 踩过）。

---

## 4. 本次开轮的裁定记录

| # | 决策点 | 裁定 | 来源 |
|---|---|---|---|
| 1 | 开轮范围 | **五票一次开完 64-68** | **所有者已确认** |
| 2 | ADR 结构 | **4 份**：0048 轮范围 + 0049 检索录放门 + 0050 task 判据 + 0051 告警；66 不够 ADR 标准（难反悔/会奇怪/有取舍三条不全），折进 0048 | 我的默认（**待确认**） |
| 3 | promtool 引入方式 | **CI 新步骤 `curl` 固定版本 + sha256 校验**；不用容器镜像（把 docker pull 引进 0 依赖子集）、不入库二进制 | 我的默认（**待确认**） |
| 4 | 告警规则条数 | **4 条**（依赖 / 熔断 / 业务调用失败 / 降级率） | 我的默认（**待确认**） |
| 5 | 票 66 去留 | **保留**（最弱，可无损删；若工期紧第一个砍） | 我的默认（**待确认**） |
| 6 | 实施顺序 | **65 → 67 → 64 → 66 → 68** | 我的默认（**待确认**） |
| 7 | 票 64 的诚实分界 | **不闭合 round19 登记第 5 项**；只守排序确定性与常数 | 沿用 round21 spec，非新决策 |
| 8 | 票 65 的聚合 | **单独一列、不给总分、不并入四列** | 沿用 round21 spec；ADR 0050 立契 |

> **第 2-6 项已由所有者确认（2026-09-28，收口后补）：五项全照默认。** 起草时它们是
> `/grill-with-docs` 里连续两次未收到答复后按 `best judgment` 先定的默认值，本轮据此实现并收口；
> 确认之后它们就是**有据的裁定**，不再挂"待确认"。**改其中任何一项仍只需改本表 + 对应 ADR，
> 不动 §3 的逐票落点。**

---

## 5. 冻结线与依据

- **票 65 / 67**：**触发已到**（round19 登记第 4 项 / ADR 0030 第 5 条），依据逐字引在 ADR 0048。
- **票 64 / 66**：**政策越过**，由 ADR 0048 以所有者开轮授权承载；票 64 不许自引触发条件。
- 本轮**不改任何判据/阈值/gold/分母**；不为放行自己收窄门禁（round20 `F1c` 的教训）。
- ADR 0008 / 0009 / 0024（除第 5 条触发线达成外）/ 0030（除第 5 条）/ 0036 的结论一字不动。

---

## 6. 明确不做（登记不执行，各挂触发条件）

| 项 | 理由 / 触发条件 |
|---|---|
| **接真实 Prometheus/Alertmanager 实例** | ADR 0030 第 5 条只闭了「否決自动失效」那一半；「接上实例」那一半触发条件保持原样 |
| **活体 hit@5 准确率进 CI** | round19 登记第 5 项；票 64 只覆盖排序确定性，**不闭合它** |
| **rerank / L2 阈值 0.95 / 知识纪元物理 purge** | 禁区，一个字都不改 |
| **`feedback` / `plansteps` 两条活体红、拦截率 74% vs 80% 裁决** | 判据一字不改，按登记保留 |
| **输入侧上下文裁剪、知识反向沉淀自动咬合、批量向量化、工单回流、`REFUNDED` 终态** | 沿用 round19 登记节，触发条件不变（见 round21 spec §7） |

---

## 7. 每票 Handoff 必须回答的三个现场追问（预备）

1. **票 64 守什么、不守什么？**（答：守排序流水线的确定性与四个常数、语料 sha、夹具完整性；不守活体 hit@5，因此不闭合 round19 登记第 5 项）
2. **票 65 为什么单独一列而不给总分？**（答：四列是单维请求质量、`task_done` 是端到端终局；混进同一分母会让「四列全绿而任务没办成」这个它要抓的形态重新隐身）
3. **票 67 为什么算「触发已到」而不是「政策越过」？**（答：`promtool test rules` 证的正是 ADR 0024 说「证不了」的「该响时会不会响」，且满足那份 ADR 的总筛子（0 token、不依赖一次性活体读数），故排除理由不再成立；它与票 64/66 的政策越过性质不同，不许混为一谈）

---

## 8. 收口登记（2026-09-28）

**读数**：JVM `5 + 29 + 304 = 338` 绿（gateway 290 → 304）；覆盖率 gateway **62.79%** /
biz-mock 79.30% / tool-api 47.95%（门槛 54.0/76.0/40.0 未动）；**指标名 53 不变**
（票 66 只加标签不加名 —— 本轮**没有**换代指针）；CI 从五步 → **八步**
（+`Task-level scorer selfcheck`、+`Retrieval fusion gate`、+`Alert rules unit test`）；
本机矩阵从 23 步 → **25 步**（+`task`/`funnel`），落点 `logs/acceptance-run-20260928-134721.log`，
**786 s、23 绿 / 2 红**（红 = `feedback`、`plansteps`，两条登记项）。

**本轮新增能力（三格）**：① 检索融合有了确定性回归门（夹具 10 条、**10/10 在 top-5 上 dense≠fused**，
原 16 条查询是 0/16）；② 「任务有没有办成」第一次可机器判定（`task_done` 单独成列、不给总分）；
③ 告警规则第一次可被证明「该响时响、不该响时不响」（4 条规则 × 两侧断言；变异对照实测为真）。

**未达成（照登，不摘红）**：
- **不闭合 round19 登记第 5 项**（活体 hit@5 进 CI）—— 票 64 只覆盖排序确定性那一层；
- ~~票 65 的活体正例读数未取到~~ —— **已补**（2026-09-28 晚，显存空出来后 `--task` 得 `task_done 3 / 没办成 0 / 未观测 0`；更早那次 `1/2/0` 是环境红，两次并列供着）；
- **gold 180 条活体重跑未做**，且是「**条件不成立**」：`.env` 有云端 key，但日预算 `260000` < 180 条所需的约 40-60 万 —— 不越 ADR 0012 的预算闸门；放开 = 把 `SHOPPILOT_LLM_DAILY_TOKEN_BUDGET` 抬到 ≥60 万或分两天跑；
- **票 67 的 CI 步未在干净 runner 实跑**，且按发布方 `sha256sums.txt` 校验（本机到发布 CDN 不可达，
  拿不到 hash 值就不猜）；票 67 那一步**也不在本机矩阵里**（矩阵里的 `task`/`funnel` 量的是脚本侧）；
- **spec §4 第 2-6 项已确认**（2026-09-28，收口后补：所有者「五项全照默认」）—— 起草时它们是我的 `best judgment` 默认值，本轮据此实现并收口；确认后即为**有据的裁定**。

**判据面**：本轮**一条判据、一个阈值、一份 gold、一个分母都没动**；`part8` 不进
`GOLD_CASE_FILES`，离线 rescore 差异仍恰好 4 条。

**收口审计读数**：**PASS 86 / FAIL 1 / SKIP 8（共 95 项）**。唯一一红照登不摘：`F1c`（round20 那次历史本机日志
被删、不可逆，判据未动未豁免）。**20 个提交推送后 `A1` 转绿**；`G6`（surefire `5 + 29 + 304 = 338`）、
`E5`/`E5b`（问答库 238/69）、`A3`（本轮零额度 `tokensUsedToday=0 mode=local budget=260000`）均绿。
