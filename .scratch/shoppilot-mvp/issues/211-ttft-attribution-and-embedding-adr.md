# 票 211：TTFT 归因刷新与 embedding 方案评审（ADR 0085）

Round: 49（spec：[round49-spec-ttft-embedding-review.md](../round49-spec-ttft-embedding-review.md)）
ADR: 0085（本票产出）　Status: implemented　Blocked by: 无

## What to build

round49 是评审型开轮：本票**只取证与裁定，不写实现代码**。产出两件东西：

1. **当前真实归因读数**（两口径分列，不许混用）：
   - 服务端指标口径：`shoppilot_embedding_latency_seconds` 三桶（`remote` / `in-process-cache` /
     `singleflight-merge`）+ TTFT/稠密/词法/模型各段计时器，经 `python scripts/ttft_attribution.py` 读取；
   - 外部探针口径：`python scripts/probe_embedding_latency.py --samples 10`（同句重复 P50 + 新问法 P50）。
   - 读数落 `loadtest/results/ttft-attribution-<date>-*`，并登记进 `docs/EVIDENCE.md`（不改旧行数字，
     按 ADR 0021 家法追加新行）。
2. **ADR 0085：四选项裁定**，逐项带实测输入：
   - **A 独立 embedding 服务**：常驻模型单条延迟实测（对照 ADR 0011 的 20–60 ms CPU 口径）+ 网络往返；
     资源账对照 ADR 0058 的「第五 JVM 500 MB」否决记录，推翻需写明理由。
   - **B 换更小向量模型**：候选（Ollama 已有 `nomic-embed-text` 768 维；可补测 `bge-small-zh-v1.5`）
     单条延迟 + 120 条检索集 dense-only 质量抽测（对照 bge-m3 基线）；退化超线即出局。
     触发面如实列出：ADR 0001 换代指针、纪元推进重入库、`eval/retrieval-fixture-20261105.json`
     dense 序重录、L2 0.95 工作点与极性守卫阈值重标定、180 gold 可能漂移（复跑需预算批准）。
   - **C 查询向量预热**：覆盖率账——判据 sweep 分母里预热集（120 条检索集 + gold 问句）能覆盖多少；
     算不出「达标所需覆盖率」的证据，就只能按「部分改善」登记，不得写成达标路径。
   - **D 维持红值照登**：现状归因账已齐，作为合法选项参与裁定。
   - 被否决选项照录：云端 embedding API（ADR 0001 已否决）、改宽判据（纪律禁令）。
   - 允许组合裁定（如 C+B、C+A），但每个组合的判据面影响必须逐项列出。

## 边界

- **不动实现代码**：`shoppilot-gateway/`、`frontend-*`、`scripts/` 的生产逻辑零改动；
  探针/归因脚本如需修 bug 只允许修「读数错误」且单独列明。
- 不改 TTFT `<500 ms` 判据、Mock 首字 300 ms 夹具、gold、`judge()`、降级枚举、分母。
- 不改 `knowledge/` 语料；不重入库；不推进纪元（那是选项 B 裁定通过后票 212 的事）。
- ADR 只新增 0085；现有 ADR（0001/0011/0058）原文不动，需要换代指针的留到票 212。
- 付费 token（dev 评测）本票一律不花；候选模型实测走本地 Ollama。

## Verify

```bash
python scripts/ttft_attribution.py            # 两口径分列的归因报告
python scripts/probe_embedding_latency.py --samples 10
bash ./mvnw -B -ntp verify                    # 782 tests / 131 suites 不变
git diff --check && git status --short
```

选项 B 的检索抽测（若执行）：`python scripts/retrieval_compare.py`（或等效脚本）跑 120 条检索集，
dense-only 对比 bge-m3 基线，结果落 `eval/results/` 并登记 EVIDENCE。

## 验收项

- 归因读数两口径分列、带日期落 `loadtest/results/`，EVIDENCE 追加行（不改旧行）。
- ADR 0085 成文：Context / Decision / Considered Options（四选项 + 被否决项）/ Consequences，
  每个选项带本票实测数字，不接受「凭旧读数裁定」。
- 覆盖率账（选项 C）有明确算式与结论：能达标 / 只能部分改善 / 覆盖率不足出局。
- 裁定结论明确到票 212 的形态（含组合），`Blocked by` 关系成立。
- JVM 用例数不变（782）；`git diff --check` 干净。

## Handoff notes

（2026-11-11 收口）

**关键决策**

- **裁定（草案，待所有者确认）**：主选 **A 精简 embedding sidecar 保 bge-m3**，spike 门槛 = 单条新问法 P50 ≤ 84 ms，过线立实施票（即票 212 的形态）、不过线回落 D；**B 出局**（nomic-embed-text 质量 98/120 = 81.7%，且合格中文小模型需引入第三方模型 + 重入库 + 夹具重录 + L2/极性重标定 + gold 付费复跑，判据面变更面最大）；**C 只能按部分改善登记**（预热集对判据 sweep 7 条政策问句覆盖 0/7 夹具 + 3/7 gold，不构成达标路径，登记为后续产品增强候选）；**D 兜底**。ADR 0085 Status: Proposed，所有者确认前不动任何实现代码。
- 归因关键事实：bge-m3 延迟与文本长度无关（7 字 novel P50 388.3 ms ≈ 32 字 384.9 ms，补测落产物追加段）→ **Ollama 服务开销主导，非模型算力**，这是 A 可行的依据；历史探针 252.6/277.3 ms（2026-10-22）与本次 335-406 ms 的差异属机器负载漂移，历轮都远超 84 ms 预算。
- 被否决项照录：云端 embedding API（ADR 0001 已否决）、改宽判据（纪律禁令）；组合裁定评估过 C+B / C+A，均被覆盖率账否掉达标资格，C 单独作为产品增强登记。

**验证落点**（全部 0 付费 token，perf 档本地网关 + 本机 Ollama）

- 归因（服务端指标口径）：`python scripts/ttft_attribution.py`，54 次强制未命中、单连接串行 → 服务端 TTFT 均值 771.5 ms = Mock 300.0 + 新问句向量化 355.7 + 稠密 6.8 + 词法 40.0 + 编排余量 69.0；产物 `loadtest/results/ttft-attribution-20261111-020944-1conn.csv` + 客户端口径 `loadtest/results/sse-ttft-20261111-020944-1-attrib.csv`。
- 探针（外部口径）：`python scripts/probe_embedding_latency.py --samples 10` → bge-m3 同句 379.8 / 新问法 335.0 ms；nomic 69.7 / 74.3 ms（768 维）；同日复验跑 382.0/406.0 与 76.5/73.3 同量级；bge-m3 7 字（388.3 ms）≈ 32 字（384.9 ms）→ 长度无关、Ollama 服务开销主导（复验与补测落在产物追加段）；产物 `loadtest/results/probe-embedding-20261111.txt`。
- 候选模型质量：dense hit@5 同候选池对比 → bge-m3 120/120（夹具 dense 序基线）vs nomic **98/120 = 81.7%**，丢失全在 fresh 池内细粒度混淆、候选独有命中 0；产物 `eval/results/retrieval-model-compare-20261111-nomic.txt`（脚本 `.scratch/probe-nomic-retrieval.py`，模型间对比口径，不判网关达标）。
- 预热覆盖率账：判据 sweep 7 条政策问句 × 预热集（120 夹具问句 + gold/评测问句集并集 351 条，归一化整串相等）精确匹配 0/7 + 3/7，宽松包含口径同读数；算式与逐条明细 `eval/results/preheat-coverage-20261111.txt`。
- 验证轮复算全部对上：归因 CSV 算术自洽（300+355.7+6.8+40.0 = 702.5，771.5−702.5 = 69.0）、nomic 抽测 120/120 vs 98/120 与产物一致、覆盖率账 0/7+3/7 复现；探针两处引用数字按磁盘产物更正（初稿误引了未落盘的控制台读数 371.9/377.0、64.8/70.3）。
- `bash ./mvnw -B -ntp verify` 四模块 **782 tests / 131 suites** 不变（本票零实现代码）；`git diff --check` 干净。
- EVIDENCE 已按追加法登记（不改旧行）；tracker README 票 211 行与 round49 行同步。

**边界与残余**

- 本票零实现代码、零语料/判据/gold/分母改动；ADR 现有 0001/0011/0058 原文未动，换代指针留票 212。
- ADR 0085 的 spike 数字（ONNX CPU 前向真实耗时）尚未实测——那是票 212 的第一格，回填 ADR 附录后再立实施票。

**三个现场追问**

1. 若 sidecar spike 过线（≤84 ms），第五进程的内存账（预估 0.6–1 GB）是否接受推翻 ADR 0058 的记账口径？
2. sidecar 落地后，T1 质心与 L2 写回等非关键路径的 bge-m3 调用继续走 Ollama，还是全部切 sidecar（切换面影响运维复杂度）？
3. 选项 C 的「高频问句秒答」产品增强，是否要现在立一张不占 round49 的储备票？
