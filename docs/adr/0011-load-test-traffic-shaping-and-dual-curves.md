# 压测流量塑形：L1 主导与 L2 主导两条曲线分开报

Context: bge-m3 向量化是 CPU-bound，在 i5-12500H（12C16T）上单条约 20-60ms，Ollama 单实例吞吐上限约 200-400 条/秒。若按任务书把 70% 热点全压给语义缓存，QPS 天花板就是 300 左右，与 `QPS >= 1200` 差 4 倍，且瓶颈不在虚拟线程、Redis 或编排代码。决定：报两条曲线——L1 主导模型（热点拆为约 55% 文本重复走精确哈希 + 约 15% 口语改写走语义缓存）与 L2 主导模型，各自给出拐点与天花板；简历数字取 L1 主导曲线，并如实标注"L2 路径受本地 embedding 吞吐限制，生产需独立 embedding 服务或批量向量化"。

Considered Options: perf 模式连 embedding 一起 mock（数字最好看但无任何真实检索成本，被追问即塌）；只保留真 bge-m3（最诚实但只能报 300 QPS）。

Consequences:
- 端点分维度：同步 `POST /api/v1/support/chat` 承载 QPS/TP99 压测；流式 `/chat/stream` 单独测 500 并发长连接下的 TTFT 与内存占用。QPS 与长连接是两个维度，混测无意义。
- 工具为 Locust（Python 3.11.9 已就绪），SSE 侧用自定义 client 消费流仅记录首字节时延。
- 必做对比实验：同一流量模型下 `spring.threads.virtual.enabled=false` + 200 平台线程池 vs 开启虚拟线程两组数据。虚拟线程收益只在 IO 等待密集处成立，没有这组对比，"开启 Virtual Threads"就只是简历上的空词。
- 精确命中路径不做向量化是本决策成立的前提，见 ADR 0003 的路由后缓存顺序。
