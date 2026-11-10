# plans/212 — embedding sidecar 落地（ADR 0085 裁定 A，已 Accepted）

冻结时间：2026-11-11　冻结者：本会话（裁决方）
实现方：独立 worktree subagent（生成与裁决分离，验收链路硬规则 1）

## Allowed paths

实现只允许改动以下路径（新增目录以 `shoppilot-embedding-sidecar/` 为根）：

- `shoppilot-embedding-sidecar/**`（新增：app.py、requirements.txt、Dockerfile、.dockerignore、README.md）
- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/knowledge/EmbeddingClient.java`
- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/config/GatewayProperties.java`（Embedding record 加字段）
- `shoppilot-gateway/src/main/resources/application.yml`（embedding 段加配置，含各 profile 注释性示例）
- `shoppilot-gateway/src/test/java/**`（EmbeddingClient 路由/降级/不变式用例）
- `docker-compose.yml`（新增 embedding-sidecar 服务，full profile）
- `scripts/up.ps1`（本机档接线）
- `.gitignore`（如需忽略本机模型目录）
- `docs/EVIDENCE.md`、`docs/STATUS.md`（仅 gen_status.py 重生成）、`README.md`（仅指标名计数同步，如新增指标）
- `.scratch/shoppilot-mvp/issues/212-embedding-sidecar-implementation.md`、
  `.scratch/shoppilot-mvp/README.md`（票 212 行）、`loadtest/results/`（新产物）

## Acceptance criteria

1. **切换面**：新增 `shoppilot.embedding.query-base-url`（env `SHOPPILOT_EMBEDDING_SIDECAR_URL`，默认空 =
   行为与现状逐字节一致）；非空时 `EmbeddingClient.embed()`（运行期：HybridRetriever / CacheService /
   TriageEngine 三处调用点）打 sidecar，`embedWarmup()`（IngestRunner / StartupInitializer /
   T1CentroidLayer）仍打原 `base-url`。
2. **协议**：sidecar 实现 Ollama 兼容 `POST /api/embed`（`{"model","input"}` → `{"embeddings":[[...]]}`），
   `EmbeddingClient.request()` 的解析逻辑零改动；1024 维校验保留。
3. **降级有账**：sidecar 运行期调用失败（连接拒绝/超时/非 2xx/维度错）→ 回落原 `base-url` 重试一次，
   成功即返回；失败计数进新计数器 `shoppilot_embedding_fallback_total`；回落也失败才抛既有异常。
   `shoppilot_embedding_calls_total` 的三桶标签与 `shoppilot_embedding_latency_seconds` 分法**不变**。
4. **sidecar 服务**：`shoppilot-embedding-sidecar/`，FastAPI + onnxruntime（CPU）+ `Xenova/bge-m3`
   `model_int8.onnx`（构建时经 hf-mirror 下载，**权重不进 git**），CLS 池化 + L2 归一化，
   intra_op 固定 8 线程（env 可覆盖），监听 `0.0.0.0`（容器）。
5. **compose 接线**：`docker-compose.yml` full profile 新增 `embedding-sidecar` 服务（healthcheck +
   模型 volume/构建下载），gateway 的 `SHOPPILOT_EMBEDDING_SIDECAR_URL` 指向它；
   `scripts/up.ps1` 本机档有明确行为（起容器或文档化跳过），`check-ps-syntax.ps1` 通过。
6. **JVM 用例**：782 基线不降；新增至少 4 条——(a) 配置 sidecar 后 embed() 打 sidecar URL、
   (b) sidecar 失败回落 Ollama 且 fallback 计数 +1、(c) embedWarmup() 不受 sidecar 配置影响、
   (d) 三桶计数不变式既有断言仍绿；gateway 覆盖率棘轮 ≥62.79% 不掉。
7. **活体读数（修后）**：perf 档本地网关 + sidecar 运行，`python scripts/ttft_attribution.py`
   的 embedding 段（`shoppilot_embedding_latency_seconds{result=remote}` 均值）**≤ 84 ms**，
   服务端 TTFT 均值 ≤ 500 ms；修前读数 = `loadtest/results/ttft-attribution-20261111-020944-1conn.csv`
   （771.5 ms / embedding 355.7）。产物落 `loadtest/results/ttft-attribution-<date>-sidecar-*`。
   若读数不达标：**如实照登**，不得为绿改判据。
8. **L2 工作点显式验收项**：`run_sse_ttft` 的 hit 层分布（l1/l2/flight/none）修前修后各留一份读数；
   int8 查询向量 × fp16 缓存向量的跨运行时自相似 ~0.987 > 0.95（spike 已测），如边界漂移
   只记录不调阈值——阈值改动属新裁决，不在本票。
9. ADR 0085 附录可追加实测段落（不改 Context/Decision/Considered Options 原文）。

## Verification commands

```bash
bash ./mvnw -B -ntp verify                      # 782 tests / 131 suites 基线 + 新用例，覆盖率棘轮不降
python -I scripts/verify_doc_truth.py           # 文档真值（含新指标名登记）
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
python scripts/ttft_attribution.py              # 修后活体（perf 档 + sidecar）
python scripts/run_sse_ttft.py                  # hit 层分布修后读数
git diff --check && git status --short
```

## Forbidden

- 改 TTFT `<500 ms` 判据、Mock 首字 300 ms 夹具、gold、`judge()`、降级枚举、指标分母；
- 改 `shoppilot_embedding_calls_total` 既有三桶标签、`shoppilot_embedding_latency_seconds` 分法；
- 改 `knowledge/` 语料、重入库、推进纪元、改 Qdrant/ES 数据；
- 改 L2 0.95 工作点 / 极性守卫阈值（读数漂移只记录）；
- 改 ADR 0001/0011/0058 原文（换代指针属票 213）；
- 动 `frontend-*`、`shoppilot-biz-mock`、`shoppilot-ticket`、`shoppilot-tool-api` 生产代码；
- 把 568 MB 模型权重或 `.venv-spike/`、`.scratch/spike-sidecar/model/` 提交进 git；
- 改判据/阈值/夹具/gold 让第 7 条变绿（未达标照登是合法出口）。
