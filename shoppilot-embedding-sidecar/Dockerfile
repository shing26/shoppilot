# 基础镜像走 daocloud 镜像源：docker.io 直连 500（spike 容器预演踩坑，2026-11-11 登记）
FROM docker.m.daocloud.io/library/python:3.11-slim

WORKDIR /app
COPY requirements.txt /app/
# 容器内 pip 官方源可达（清华源反而 403），不换 index
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY app.py download_model.py /app/

ENV HF_ENDPOINT=https://hf-mirror.com \
    SIDECAR_MODEL_DIR=/app/models \
    SIDECAR_HOST=0.0.0.0 \
    SIDECAR_PORT=8110 \
    SPIKE_THREADS=8

# 模型权重（568 MB）在构建期下载进镜像层，不进 git；本机已有 spike 权重时可在 compose
# 里挂卷覆盖（见 README「部署」），跳过这次下载。
RUN python download_model.py

EXPOSE 8110
CMD ["python", "app.py"]
