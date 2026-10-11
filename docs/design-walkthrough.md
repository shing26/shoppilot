# 架构走读：沿一次请求的生命周期读八个关键决策

这份走读把三分钟入口扩展到可复现的深度。它不是第二份指标文档：结论先看 [README](../README.md) 的「核心架构与工程验证索引」，证据入口只看 [EVIDENCE.md](EVIDENCE.md)，本页负责按请求生命周期把关键决策、源码落点和复现命令串起来。

每个站点用同一套动作读：

1. 先读 `Decision`，自己先说 60 秒会怎么做。
2. 打开 `First read`，找到决策对应的类或脚本。
3. 跑 `Reproduce` 中至少一条命令，解释输出而不背输出。
4. 说出一条 `Failure symptom` 和一条 `Evidence link`。

## 八站地图

| # | Station | First read | 关键决策与读数 |
| --- | --- | --- | --- |
| 1 | 入口与身份 | `identity/AuthFilter.java`、`JwtService.java`、`TenantContext.java`、`config/DevDefaultsPolicy.java` | 身份只来自已验签 token；MDC 链路号异步传播；dev 默认值在非回环绑定上 fail-fast（ADR 0056/0058/0062） |
| 2 | 意图三级级联 | `triage/TriageEngine.java`、`T0RuleLayer.java`、`T1CentroidLayer.java` | T0 字面规则 / T1 向量质心 / T2 模型，仅不确定时升级；字面「转人工」在 T0 定案，向量服务挂了也转得出去（ADR 0017） |
| 3 | 两级缓存与穿透合并 | `cache/CacheService.java`、`L1Cache.java`、`L2SemanticCache.java`、`PolarityGuard.java`、`SingleFlight.java` | 缓存后置的代价与收益；命中 P99 22 ms；0.95 工作点之外加词法极性守卫（反义对 8/8 不互命中）；冷缓存 35264 → 18 次远程 embedding |
| 4 | 检索与入库 | `knowledge/HybridRetriever.java`、`EmbeddingClient.java`、`ingest/MarkdownChunker.java` | Qdrant dense + ES BM25 双引擎、RRF(k=60) 融合；幂等入库；检索失败与空结果是两件事（ADR 0018 的 60 秒负缓存） |
| 5 | Agent 编排与工具 | `agent/AgentStateMachine.java`、`ToolDispatcher.java`、`shoppilot-tool-api/` | 10 状态显式枚举；工具循环 2 轮硬上限；涉资操作只出草案（PENDING_APPROVAL），放行必须是已验签坐席（ADR 0063） |
| 6 | 业务中台与隔离 | `bizmock/service/BizMockService.java`、`repo/`、`domain/` | 独立进程 + 跨进程内部凭证（ADR 0002）；行级租户隔离；工单统一实体四来源同表（ADR 0055） |
| 7 | 降级、限流、可观测 | `agent/FallbackService.java`、`ratelimit/RateLimitService.java`、`config/RuntimeStateMetrics.java` | 9 种降级 reason 各落可查工单；readiness / deps / 总健康三个运维口径各管一件事（ADR 0026） |
| 8 | 量化与门禁 | `scripts/run-acceptance.ps1`、`run_loadtest.py`、`build_loadtest_report.py`、`docs/EVIDENCE.md` | 压测报告由脚本从产物直读生成；CI 九步与 0 token 门禁；红值保留不改分母 |

## 一段话叙述

1. **定位**：面向电商大促的智能客服与业务网关，单机可复现的运行时原型，不宣称生产部署。
2. **四个问题**：热点不进模型、对话真的落业务、语义缓存不串号、依赖故障可解释降级。
3. **主链路**：鉴权与上下文 → 限流 → 意图级联 → L1/L2 缓存 → Hybrid 检索 → 有界 Agent → 工具或 fallback → SSE / 写回。
4. **三个取舍**：缓存后置；工具循环最多 2 轮；0.95 阈值之外再加极性守卫。
5. **正向数字**：命中路径 P99 22 ms；Token 节约 62.4%；虚拟线程 400-800 并发收益 +64%。每个数字都带模式、并发和作用域。
6. **红值**：拦截率 74-78% 对判据 80%；吞吐 1013 QPS 对判据 1200；未命中 TTFT 690-1499 ms 对判据 500 ms。先讲差距，再讲归因和限制。
7. **踩坑**：冷缓存雪崩把远程 embedding 从 35264 次压到 18 次，靠进程内向量缓存、穿透合并和检索失败语义一起收口。
8. **收口**：数字去 [EVIDENCE.md](EVIDENCE.md)，冻结边界见 [RELEASE.md](../RELEASE.md) 与 [docs/adr/](adr/)。

## 自检清单

读完走读后应当能：

- 不看稿列出 10 个 Agent 状态，并解释 `REPLY` 与 `FALLBACK` 两条终止路径。
- 说明为什么缓存放在意图之后，以及它牺牲了什么、换回了什么。
- 解释 0.95 与极性守卫为什么不是二选一，为什么跨租户隔离不靠阈值。
- 说明 Qdrant/ES 当前 hit@5 打平，为什么仍保留双引擎，且不把未来收益说成已实现。
- 解释 Redis 不可用时限流为什么放行，以及为什么 DB 唯一约束仍要保留。
- 区分 readiness、deps 和总健康三个运维口径。
- 从 `EVIDENCE.md` 找到至少三条红值的原始产物与复现入口。
- 解释编排层为什么自研：唯一 provider 缝在 `LlmClient`，换框架是 1–2 天适配而不是重写（ADR 0032）。

## 当前基线与核对命令

```text
JVM tests: 505（四模块 10 + 67 + 383 + 45）
CI: 九步（构建与 JVM 测试 + 0 token 评测门禁 + 两个前端产物逐字节校验）
ADR range: 0001-0065（0022 有意预留未占用）
```

```powershell
.\mvnw.cmd -B -ntp verify
python scripts\verify_eval_judge.py
python scripts\build_loadtest_report.py --strict
pwsh -NoProfile -File scripts\run-acceptance.ps1
```

活体验收矩阵分 daily / live / full 三档，判据逐字相同、切步骤；逐条证据、原始产物与未达成项见 [EVIDENCE.md](EVIDENCE.md)。
