# 三态 LLM 依赖与分口径指标声明

Context: 任务书同时承诺了 `未命中 TTFT < 500ms`、`QPS >= 1200（80% 缓存命中）`、`Token 节约率`、`Tool Calling 准确率 >= 95%`，但这些指标对模型依赖的假设互不兼容——1200 QPS 下即使 80% 命中仍有 240 QPS 的真实推理请求，任何云端 API key 与本地 qwen2.5:3b 都无法承载。决定：用 Spring profile 切三态运行模式，`dev` 走真实云端 LLM（OpenAI-compatible base-url 可配）用于功能演示与工具调用准确率评测，`local` 走 Ollama（qwen2.5:3b + bge-m3）用于离线兜底并顺带验证降级链路，`perf` 走 MockLLMClient（固定延迟、可注入超时抖动）只用于 Locust 压测。

Consequences:
- 吞吐与时延类指标（QPS、TP99、缓存拦截率）**只在 perf 模式下测得**，文档与简历中必须标注"衡量的是网关编排层与缓存层，不含模型推理"。
- 质量类指标（Tool Calling 参数抽取准确率、检索命中率）**只在 dev 模式下用真模型 + 标注评测集跑**，与吞吐数字来源不同，分开陈述。
- 混用两组数字会被一个追问拆穿；分开陈述反而更可信。
- Embedding 走本地 bge-m3（原生 1024 维）而非云端 API，因为 L2 语义缓存要求每次提问先向量化，这一步走网络会直接击穿 TP99 < 30ms 的目标。
