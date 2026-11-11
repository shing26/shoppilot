# -*- coding: utf-8 -*-
"""bge-m3 embedding sidecar（ADR 0085 选项 A / 票 212）。

常驻模型的轻量向量服务：FastAPI + onnxruntime CPU（Xenova/bge-m3 int8）+ CLS 池化 + L2 归一化。
对外实现 Ollama 兼容的 ``POST /api/embed``（body ``{"model","input"}`` → 返回
``{"embeddings":[[...]]}``），网关 ``EmbeddingClient.request()`` 的解析因此零改动。

形态依据（spike 实测，``eval/results/spike-embedding-sidecar-20261111.txt``）：
- 延迟被 Ollama 服务层开销主导而不是模型算力，换掉这层后单条短句 novel P50 ~35 ms（门槛 ≤84 ms）；
- intra_op 线程固定 8（默认 16 线程与同机 Docker/Ollama 争抢劣化 ~2.4x），env ``SPIKE_THREADS`` 可覆盖；
- 容器内必须绑 0.0.0.0：Docker 端口发布是宿主→容器的 NAT，只听回环没人能连上。

运行期降级由网关负责（sidecar 失败回落 Ollama 一次并计数），本服务自身无状态、可随时重启。
"""
import os
from pathlib import Path

import numpy as np
import onnxruntime as ort
from fastapi import FastAPI
from pydantic import BaseModel
from tokenizers import Tokenizer

MODEL_DIR = Path(os.environ.get("SIDECAR_MODEL_DIR", "/app/models"))
THREADS = int(os.environ.get("SPIKE_THREADS", "8"))
HOST = os.environ.get("SIDECAR_HOST", "0.0.0.0")
PORT = int(os.environ.get("SIDECAR_PORT", "8110"))

_model_path = MODEL_DIR / "onnx" / "model_int8.onnx"
if not _model_path.exists():
    raise SystemExit(f"模型权重缺失：{_model_path}（构建期经 download_model.py 下载，或挂卷到 {MODEL_DIR}）")

_session_opts = ort.SessionOptions()
_session_opts.intra_op_num_threads = THREADS
_session = ort.InferenceSession(str(_model_path), _session_opts, providers=["CPUExecutionProvider"])
_tokenizer = Tokenizer.from_file(str(MODEL_DIR / "tokenizer.json"))

app = FastAPI(title="shoppilot-embedding-sidecar", docs_url=None, redoc_url=None)


class EmbedBody(BaseModel):
    # Ollama /api/embed 的形态：input 可以是单条字符串，也可以是批量数组；model 仅回显用途，不校验
    model: str | None = None
    input: str | list[str]


def embed_one(text: str) -> list[float]:
    """bge-m3 官方用法：最后一个隐藏层的 CLS 向量 + L2 归一化，输出 1024 维。

    与 spike（spike_forward_common.py）同一套前向，int8 量化抽检 120/120 与 fp16 保序
    （同候选池 dense hit@5），Qdrant/ES 既有库原样可用，不重入库。
    """
    encoded = _tokenizer.encode(text)
    ids = np.array([encoded.ids], dtype=np.int64)
    mask = np.array([encoded.attention_mask], dtype=np.int64)
    output = _session.run(None, {"input_ids": ids, "attention_mask": mask})[0]
    vector = output[0, 0]
    return (vector / np.linalg.norm(vector)).tolist()


@app.get("/health")
def health():
    return {"status": "ok", "threads": THREADS, "model_dir": str(MODEL_DIR)}


@app.post("/api/embed")
def api_embed(body: EmbedBody):
    inputs = [body.input] if isinstance(body.input, str) else body.input
    return {"embeddings": [embed_one(text) for text in inputs]}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=HOST, port=PORT, log_level="warning")
