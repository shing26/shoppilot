# 票 206：检索与模型路由决策（100–200 条检索集、单/双引擎、Stage3 影子路由）

Round: 48（spec：[round48-spec-quality-closure.md](../round48-spec-quality-closure.md)）
ADR: 0083　Status: implemented　Blocked by: 无

## What to build

round48 收口的三项决策，证据与结论写进 `docs/RETRIEVAL_ROUTING_DECISION.md`：

1. **检索集**：把 `docs/retrieval-quality-metrics.md` 自己写明的缺口（「需要人工标注的百条量级」）
   补成一份 100–200 条、`knowledge/` 全文档覆盖、dev/holdout 分片的用例集；
   结构校验进 CI，录制脚本能直接读它。
2. **单/双引擎**：按既有 10 / 16 条实测给结论（不是按设计意图），并**先写死** 120 条 holdout
   的判定规则——避免录完照着结果改判据。
3. **Stage3 影子路由**：给默认模型/默认路由一个明确决定，并把影子模式的设计与转正门槛登记下来。

## 边界

- **不新增产品功能**：不实现影子路由、不改默认模型、不改 `llm.mode`、不改任何 `application.yml` 默认值。
- **不改判据**：不动 gold、阈值、`judge()`、降级枚举、指标分母；不重写
  `docs/retrieval-quality-metrics.md` / `retrieval-comparison.md` / `STAGE3_MODEL_ROUTING.md` 的读数。
- **不改语料与检索参数**：不动 `knowledge/*.md`、`rrf-k`、top-K、embedding 模型。
- 用例集的 `expect_doc` 按文档标题拟定，**不看任何一路检索结果**；不把融合输出当标签。
- 不因本票删 ES / BM25 依赖或代码。

## Verify

```bash
python scripts/verify_retrieval_cases.py
python -m py_compile scripts/record_retrieval_fixture.py
./mvnw.cmd -B -ntp -pl shoppilot-gateway -am test \
  -Dtest=RetrievalQualityMetricsTest -Dsurefire.failIfNoSpecifiedTests=false
git diff --check
```

活体录制（需要网关 8082 + ES + Qdrant，本机不满足）：

```bash
python scripts/record_retrieval_fixture.py --cases eval/retrieval-cases.json --out eval/retrieval-fixture-<日期>.json
```

## 验收项

- `eval/retrieval-cases.json` 120 条，37 份文档全覆盖，holdout 25%–35%；
- `scripts/verify_retrieval_cases.py` 通过并进 CI（并入既有检索融合门那一步，不加步数）；
- `record_retrieval_fixture.py --cases` 能按用例的 intent/tenant 发探针，且**默认仍录原来 10 条**
  （既有夹具与哈希钉零变化）；
- 自标注夹具能被 `RetrievalQualityMetricsTest` 自动识别并算三臂指标，且不改写老报告；
- 决策文档写明三项结论、120 条的判定规则、影子模式设计与转正门槛；
- 未录 120 条夹具的事实与原因如实登记，不用离线读数冒充新证据。

## Handoff notes

**关键决策**

- **检索集按文档主题反推查询**（37 份 × 3 条 + 9 条硬样本 = 120），每条带
  `intent` / `expect_doc` / `tenant` / `split`；店铺级文档只能用归属店发起（T002 死蟹、T004 酒店、
  T001 会员日），这正是「租户错位会被误读成检索差」的常见来源。dev 78 / holdout 42（35.0%）。
- **结构校验与效果测量分开**：`verify_retrieval_cases.py` 只守用例集本身（规模、唯一性、文档存在、
  意图与主题一致、租户自洽、每份 ≥2 条且 dev ≥1 条、查询不抄标题），0 token 进 CI；
  效果要等活体录制，不在 CI 里假装。
- **`--cases` 不改变老口径**：不带参数时仍录硬编码的 10 条，所以
  `eval/retrieval-fixture-20260928.json` 与 `RETRIEVAL_FIXTURE_SHA256` 都不动。这个分支是刻意保留的：
  改默认录制集会让既有融合门与历史读数一起失效。
- **指标计算仍只有一份实现**：`RetrievalQualityMetricsTest` 扩展为「按用例是否自带 `expect_doc`
  分流」——老夹具继续用 `eval/retrieval-labels.json` 并生成入库报告；自标注夹具只把读数打到构建日志，
  不改写报告。规则哈希到源文件的映射仍用生产 `MarkdownChunker`，没有在 Python 里复写一遍。
- **单/双引擎结论是「继续双引擎」，但措辞限定为「没有更差」**：10 条上 hit@5 三臂饱和、
  MRR 稠密 1.000 / 混合 1.000 / BM25 0.875，且混合 top-1 有 8 条由 BM25 提供——删掉稀疏路是
  在没有证据的情况下丢掉这块能力。判定规则（±0.02 MRR 或 ±2 条 hit@5）写在录制之前。
- **Stage3 默认不变、影子不实现**：延迟 3.3s → 10.1s、样本只有 180 gold 单集、本地容量未压测，
  三条边界都未过；影子模式要改装配层与审计字段，属于新功能，ADR 0083 的收口授权不支持。
  设计（独立开关默认关、只记 `routingShadow` 与计数器、关闭时零开销）与转正四门槛已登记。

**验证落点**

- `python scripts/verify_retrieval_cases.py` → **OK：120 条 / 37 份文档全覆盖 / holdout 42 条（35.0%）**；
  split dev 78 / holdout 42；意图 FRESH 26 / PROMO 26 / RETURN 26 / SHIPPING 23 / HOTEL 19。
- `load_cases()` 干跑：all 120（T001 93 / T002 8 / T004 19）、dev 78、holdout 42，`expect_doc` 随行带出。
- `python -m py_compile scripts/record_retrieval_fixture.py` 通过。
- `RetrievalQualityMetricsTest` **3 tests / 0 失败**（单独跑 BUILD SUCCESS，16.9s）；重跑后
  `docs/retrieval-quality-metrics.md` **零改动**（`git status` 无该文件），证明老报告仍由老夹具生成。
- 全量 `./mvnw.cmd -B -ntp verify -Dspring-boot.repackage.skip=true` **BUILD SUCCESS**（2:34），
  四模块 **12 / 92 / 626 / 50 = 780 tests / 131 suites**、0 失败 / 0 跳过，与票 205 基线逐项相同。
- CI：`Retrieval fusion gate` 一步里追加 `verify_retrieval_cases.py`，**步数仍为九步**。
- **未录 120 条夹具**：本机 Docker 不可用、网关 8082 未起，ES/Qdrant 不可达；所以本票不产出新的
  检索指标，只产出「预先登记好的尺子 + 判定规则」。这是本票最大的未达成项，`docs/EVIDENCE.md` 同步登记。

**现场追问**

1. 若下一轮录完夹具后 `RetrievalQualityMetricsTest` 没打印「自标注夹具读数」：先看文件名是否
   以 `retrieval-fixture-` 开头、`cases[*].expect_doc` 是否非空——分流靠这两个条件，不靠文件名日期。
2. 若录制在酒店/生鲜查询上大面积空召回：先确认 `--cases` 用的是用例自带 tenant（T004/T002），
   店铺级文档不会出现在别的租户里；再确认 `intent` 过滤没有把另一主题的块挡掉。
3. 若 holdout 判定落在「融合比稠密低 ≥0.02」：按预登记规则开票评估单稠密，**不要**先调
   `rrf-k` 把它救回来——调参救回来的增益在 holdout 上不可复现。
