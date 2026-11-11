# -*- coding: utf-8 -*-
"""构建期模型下载：Xenova/bge-m3 的 int8 ONNX 与 tokenizer，落到 SIDECAR_MODEL_DIR。

经 HF_ENDPOINT 指到 hf-mirror（Dockerfile 默认）：docker build 的网络环境里官方 HF 常不可达。
下载的 568 MB 权重进镜像层，**不进 git**（.gitignore 已忽略本机的 models/ 目录）。
"""
import os
from pathlib import Path

from huggingface_hub import hf_hub_download

MODEL_DIR = Path(os.environ.get("SIDECAR_MODEL_DIR", "/app/models"))
FILES = ("onnx/model_int8.onnx", "tokenizer.json")

for name in FILES:
    target = MODEL_DIR / name
    if target.exists():
        print(f"已存在，跳过下载：{target}")
        continue
    path = hf_hub_download(repo_id="Xenova/bge-m3", filename=name, local_dir=str(MODEL_DIR))
    print(f"下载完成：{path}")
