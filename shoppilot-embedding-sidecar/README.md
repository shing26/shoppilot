# shoppilot-embedding-sidecar

bge-m3 精简向量服务（ADR 0085 选项 A / 票 212）：FastAPI + onnxruntime CPU +
`Xenova/bge-m3` `onnx/model_int8.onnx`（int8，568 MB），CLS 池化 + L2 归一化，输出 1024 维。

spike 门槛实测：短句 novel HTTP P50 **34.7 ms**（宿主）/ **35.5 ms**（容器），判据预算 84 ms；
对照 Ollama 同模型 335–406 ms——延迟由 Ollama 服务层开销主导，换掉这层即过线。
完整读数与负载窗口对照见 `eval/results/spike-embedding-sidecar-20261111.txt`。

## 协议

Ollama 兼容，网关 `EmbeddingClient.request()` 解析零改动：

```
POST /api/embed
{"model": "bge-m3", "input": "生鲜坏了怎么赔"}
→ 200 {"embeddings": [[1024 floats]]}
```

`input` 也可以是字符串数组（批量），返回等长的 embeddings 数组。
另有 `GET /health`（compose healthcheck 用）：`{"status":"ok","threads":8,"model_dir":...}`。

## 运行期接线（网关侧）

`shoppilot.embedding.query-base-url`（env `SHOPPILOT_EMBEDDING_SIDECAR_URL`）非空时，
网关 `embed()`（运行期查询向量化）先打本服务；失败（连接拒绝/超时/非 2xx/维度错）回落
原 `base-url`（Ollama）重试一次，回落事件记 `shoppilot_embedding_fallback_total`。
`embedWarmup()`（预热/入库/T1 质心）**不走 sidecar**，永远打 Ollama。

默认（env 不设）行为与没有 sidecar 的现状完全一致。

## 部署

### compose 容器档（full profile）

```powershell
docker compose --profile full up -d --build embedding-sidecar
```

模型在构建期经 hf-mirror 下载进镜像层（`HF_ENDPOINT` 可覆盖）。本机已有 spike 下载的
权重（`.scratch/spike-sidecar/model/`）时，可挂卷跳过下载：

```yaml
    volumes:
      - ./.scratch/spike-sidecar/model:/app/models:ro
```

端口发布到宿主回环 `127.0.0.1:${SHOPPILOT_SIDECAR_PORT:-8110}:8110`（本机 JVM 档也用它）。
`scripts/up.ps1` 本机档会在 Docker 可用时自动起这个容器并给网关进程导出
`SHOPPILOT_EMBEDDING_SIDECAR_URL=http://127.0.0.1:8110`；Docker 不可用则打印跳过说明，
网关走 Ollama（既有回落路径）。

### 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `SIDECAR_MODEL_DIR` | `/app/models` | 模型目录（须含 `onnx/model_int8.onnx` 与 `tokenizer.json`） |
| `SPIKE_THREADS` | `8` | onnxruntime intra_op 线程数（spike 实测 8 最优；16 与同机负载争抢劣化 ~2.4x） |
| `SIDECAR_HOST` / `SIDECAR_PORT` | `0.0.0.0` / `8110` | 监听地址；容器内必须 0.0.0.0（绑 127.0.0.1 时端口映射 Empty reply） |
| `HF_ENDPOINT` | `https://hf-mirror.com` | 构建期模型下载源（仅 Dockerfile 构建时生效） |

## 资源账

镜像 ~579 MB（python:3.11-slim + 依赖）+ 权重层 568 MB；运行 RSS 实测 **~1.2 GB**
（容器 1.178 GiB，与宿主进程一致）——compose `mem_limit` 1536m 按实测留余量。
ADR 0058「第五 JVM 500 MB」的否决记录针对工单域 JVM，本形态的推翻记账按此实测数进票 212。
