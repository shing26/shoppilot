# 票 212：embedding sidecar 落地——未命中路径切精简向量服务（TTFT 达标路径）

Round: 49（spec：[round49-spec-ttft-embedding-review.md](../round49-spec-ttft-embedding-review.md)）
ADR: 0085（含 spike 附录）　Status: ready-for-agent　Blocked by: **所有者确认 ADR 0085 裁定（Proposed → Accepted）**

## What to build

裁定形态 = A（精简 sidecar 保 bge-m3），spike 已过线（`eval/results/spike-embedding-sidecar-20261111.txt`）：

1. **sidecar 进程**：FastAPI + ONNX int8（`Xenova/bge-m3` `model_int8.onnx`）+ CLS 池化，
   **固定 8 线程**（spike 实测默认 16 线程同机争抢劣化 2.4x）；模型权重与依赖进 Docker 镜像
   （568 MB 权重 + ~1.2 GB RSS 运行账，按实测数记，ADR 0058 换代指针写明理由）。
   **容器形态已在 spike 预演通过**（`eval/results/spike-embedding-sidecar-20261111.txt` 容器段）：
   python:3.11-slim + volume 挂模型 = 579 MB 镜像 + 1.178 GiB 运行内存，宿主→容器 HTTP
   短 novel P50 35.5 ms 过线；两个实现坑照登——基础镜像走 `docker.m.daocloud.io`
   （docker.io 直连 500）、容器内绑 `0.0.0.0` 而非 127.0.0.1（否则端口映射 Empty reply）。
2. **网关切换**：`EmbeddingClient` 未命中路径的查询向量化指向 sidecar（HTTP）；
   T1 质心、L2 写回等非关键路径调用是否同切由实现时按延迟读数定，票面留痕。
3. **指标分账不变式**：`shoppilot_embedding_latency_seconds{result=remote}` 口径保留
   （仍叫 remote，指向变了），票 47 的逐桶计数不变式进用例；不新增第四桶。
4. **L2 工作点显式验收项**：int8 hit@5 已 120/120 保序（spike 附录），但绝对余弦会轻移——
   L2 0.95 工作点与极性守卫阈值带**修前/修后两组读数**判定；有漂移才重标定，且重标定是
   显式验收项不是顺手调；无漂移则原值不动。
5. **接线**：`docker-compose.yml`（full profile）与 `scripts/up.ps1` 各加 sidecar 服务定义；
   sidecar 不可达时行为 = 沿用现 Ollama 路径（降级有账，不静默换实现）。

## 边界

- 不改 TTFT `<500 ms` 判据、Mock 300 ms 夹具、gold、`judge()`、降级枚举、分母。
- 不改 `knowledge/` 语料；不重入库；不推进纪元（向量同模型同维，Qdrant/ES 库原样可用——
  这是保 bge-m3 形态的最大收益）。
- 不动 ADR 0001/0011/0058 原文，换代指针在收口时由票 213 登记。
- Allowed paths / Verification commands 在开工前按验收链路冻结进 `plans/212-*.md`。

## Verify

```bash
bash ./mvnw -B -ntp verify                 # 782 tests / 131 suites 基线，新增代码配用例
python scripts/ttft_attribution.py         # 修前/修后：embedding 段 355.7 → ≤84 ms，TTFT 均值 ≤500
python scripts/probe_embedding_latency.py --samples 10   # Ollama 口径仍在（对照）
python scripts/run_sse_ttft.py             # 未命中 TTFT sweep 判据读数
git diff --check && git status --short
```

容器档活体：compose full profile 起 sidecar，未命中首答走 sidecar，`cacheLayer=NONE`
场景 TTFT 实测进 `loadtest/results/`。

## 验收项

- 未命中路径 embedding 段 ≤84 ms（服务端计时），TTFT 判据 sweep 达成或如实照登。
- 分账三桶不变式用例绿；`result=remote` 计数在 sidecar 路径上增长。
- L2 0.95 工作点/极性守卫修前修后读数留档，处置（原值/重标定）有记录。
- sidecar 降级路径（进程不可达 → Ollama 回落）有用例或活体证据。
- ADR 0058 换代指针理由 + RSS 实测记账。

## Handoff notes

（收口时补：关键决策、验证落点、三个现场追问。）
