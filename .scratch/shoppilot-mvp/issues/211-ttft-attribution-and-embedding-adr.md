# 票 211：TTFT 归因刷新与 embedding 方案评审（ADR 0085）

Round: 49（spec：[round49-spec-ttft-embedding-review.md](../round49-spec-ttft-embedding-review.md)）
ADR: 0085（本票产出）　Status: ready-for-agent　Blocked by: 无

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

（收口时补：关键决策、验证落点、三个现场追问。）
