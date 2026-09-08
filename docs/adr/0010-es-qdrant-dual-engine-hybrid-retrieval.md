# 双引擎混合检索：ES 倒排 + Qdrant 稠密 + RRF 融合

Context: 考虑过砍掉 Elasticsearch 以省 512M 内存、改走 Qdrant 单引擎 hybrid（dense + sparse）。但 Ollama 的 embedding 接口只暴露 dense 向量，拿不到 bge-m3 的 lexical sparse weights；补齐词权重要么引入 Python FlagEmbedding sidecar（torch CPU 常驻 1.5G 以上，比 ES 更贵），要么 Java 侧自研 BM25 权重（jieba-analysis + 自建 IDF 表，冲刺期不值得）。决定：保留双引擎，Qdrant dense 1024 维召回 top-20，ES BM25 召回 top-20，RRF（k=60）融合取 top-5 注入 Prompt。

Considered Options: Qdrant 单引擎 dense+sparse（sparse 来源缺失，替代路径成本更高）；仅 dense + payload keyword 过滤（放弃术语精确匹配，"七天无理由"与"7天无理由"、"定金膨胀"这类政策术语召回会糊）。

Consequences:
- 一致性策略：不做运行时双写。离线入库脚本以 `ruleId` 同时作为 ES `_id` 与 Qdrant point id，重跑即幂等 upsert，因此不存在一致性窗口。
- ES 仅在 RETRIEVE 路径被访问，缓存命中路径不经过它；但 perf 压测含 15% 长尾 RAG 流量，压测期间 ES 必须在线。
- 政策语料仅 30 篇文档，ES 索引进不到什么优化，别在这上面花时间。
