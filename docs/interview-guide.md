# ShopPilot 八站面试掌握路线

这份路线把三分钟作品集扩展到可追问、可复现的八站知识。它不是第二份指标文档：项目结论先看 [portfolio-interview.md](portfolio-interview.md)，证据入口只看 [EVIDENCE.md](EVIDENCE.md)，本页负责安排学习顺序、主动回忆和掌握门。

## Cold-Read 基线

2026-09-17 由 fresh-context reviewer 只读 [portfolio-interview.md](portfolio-interview.md) 做独立冷读。结果判为 **PASS as a three-minute introduction**：四项工程问题、三项核心取舍、三个正向数字、三条红值和一个冷缓存踩坑都能被没有上下文的人复述。

但 cold-read 不是八站掌握证明。它留下五个要专门教的缺口：

1. “最难取舍”没有排名；课程固定把“缓存放在意图之后”作为第一难，因为它主动牺牲命中率换正确性。
2. 三个正向数字要作为一组记忆，并各自带模式、并发、作用域。
3. 三条红值必须成组带归因和限制，不能只报数字。
4. `93.3%` 是旧 gold 实测，`95.6%` 是新判据离线重算；承诺项仍按分意图门槛判未达成。
5. 作品集是入口不是独立证据；每个数字都要回到 `README.md` 与 `docs/EVIDENCE.md`。

## 怎样走完整条路线

### 第 0 遍：三分钟入口

只读 [portfolio-interview.md](portfolio-interview.md)，不看别的文档，口述下面五项：

- 项目解决哪四个工程问题。
- 主链路的起点、缓存位置、Agent 终点。
- 三个最关键取舍。
- 三个正向数字与一条踩坑。
- 三条未达标指标为什么仍然保留。

如果五项中任何一项说不完整，先不要进入源码站。

### 第 1 遍：八站第一轮

按请求生命周期顺序走 [八站速查卡](interview-mastery/reference/eight-station-map.html)。每站用同一动作：

1. 先读 `Decision`，不看答案，自己说 60 秒。
2. 打开本页表中的 `First read`，找到决策对应的类或脚本。
3. 跑 `Reproduce` 中至少一条命令；解释输出，不背输出。
4. 回答 `Three questions` 后再展开 HTML 答案核对。
5. 说出一条 `Failure symptom` 和一条 `Evidence link`。

第一轮的目标是沿线走通，不追求一次背熟。每站控制在 15-25 分钟，八站分多次完成比一次塞满更利于长期保持。

### 第 2 遍：乱序追问

让当前 agent 随机抽 2-3 站，按“问题 -> 决策 -> 取舍 -> 证据 -> 限制”提问。回答时使用下面的三段式：

```text
我先给结论：
为什么这样做：
证据与边界：
```

如果答案只有技术栈、没有取舍，或只有数字、没有判据，视为未掌握。

### 第 3 遍：模拟面试

由另一个人只拿 [portfolio-interview.md](portfolio-interview.md) 提问 20 分钟。结束后把所有问题写入 [interview-feedback.md](interview-feedback.md)。只有同一缺口被不同面试官至少问到两次、且一天内可补，才按 [ADR 0031](adr/0031-interview-feedback-is-the-sixth-reopen-trigger.md) 讨论重开工作。

## 八站地图

| # | Station | First read | Lesson | 掌握输出 |
| --- | --- | --- | --- | --- |
| 1 | 入口与身份 | `identity/AuthFilter.java`、`JwtService.java`、`TenantContext.java`、`config/DevDefaultsPolicy.java` | [第 1 课](interview-mastery/lessons/0001-entry-and-identity.html) | 说明身份授权根、MDC 异步传播和 dev 默认值边界 |
| 2 | 意图三级级联 | `triage/TriageEngine.java`、`T0RuleLayer.java`、`T1CentroidLayer.java` | [第 2 课](interview-mastery/lessons/0002-intent-cascade.html) | 解释 T0/T1/T2、向量复用、动作优先和 UNKNOWN fail-closed |
| 3 | 两级缓存与穿透合并 | `cache/CacheService.java`、`L1Cache.java`、`L2SemanticCache.java`、`PolarityGuard.java`、`SingleFlight.java` | [第 3 课](interview-mastery/lessons/0003-two-level-cache.html) | 说明缓存后置的代价、四层串号防线和写回资格 |
| 4 | 检索与入库 | `knowledge/HybridRetriever.java`、`EmbeddingClient.java`、`ingest/MarkdownChunker.java` | [第 4 课](interview-mastery/lessons/0004-retrieval-and-ingest.html) | 解释双引擎、RRF、幂等入库、失败与空结果区别 |
| 5 | Agent 编排与工具 | `agent/AgentStateMachine.java`、`ToolDispatcher.java`、`shoppilot-tool-api/.../tool/` | [第 5 课](interview-mastery/lessons/0005-agent-and-tools.html) | 说明 10 状态、2 轮上限、槽位追问和写操作幂等 |
| 6 | 业务中台与隔离 | `bizmock/service/BizMockService.java`、`repo/`、`domain/` | [第 6 课](interview-mastery/lessons/0006-biz-mock-and-isolation.html) | 说明独立进程、行级租户隔离、DB 兜底和业务状态前置 |
| 7 | 降级、限流、可观测 | `agent/FallbackService.java`、`ratelimit/RateLimitService.java`、`config/RuntimeStateMetrics.java` | [第 7 课](interview-mastery/lessons/0007-fallback-ratelimit-observability.html) | 区分 readiness/deps，讲双维限流与可查工单 |
| 8 | 量化与门禁 | `scripts/run-acceptance.ps1`、`run_loadtest.py`、`build_loadtest_report.py`、`docs/EVIDENCE.md` | [第 8 课](interview-mastery/lessons/0008-quantification-and-gates.html) | 说明报告生成、CI 边界、红值保留和指标历史 |

## 三分钟总叙述

练习时按固定骨架，不按类名清单开场：

1. **定位**：面向电商大促的智能客服与业务网关，单机可复现，目标是面试作品，不是生产部署。
2. **四个问题**：热点不进模型、对话真的落业务、语义缓存不串号、依赖故障可解释降级。
3. **主链路**：鉴权与上下文 -> 限流 -> 意图级联 -> L1/L2 缓存 -> Hybrid 检索 -> 有界 Agent -> 工具或 fallback -> SSE/写回。
4. **三个取舍**：缓存后置；工具循环最多 2 轮；0.95 阈值之外再加极性守卫。
5. **正向数字**：命中路径 P99 22 ms；Token 节约 62.4%；虚拟线程 400-800 并发收益 +64%。每个数字都带模式、并发和作用域。
6. **红值**：拦截率 74-78% 对判据 80%；吞吐 1013 QPS 对判据 1200；未命中 TTFT 690-1499 ms 对判据 500 ms。先讲差距，再讲归因和限制。
7. **踩坑**：冷缓存雪崩把远程 embedding 从 35264 次压到 18 次，靠进程内向量缓存、穿透合并和检索失败语义一起收口。
8. **收口**：作品集是入口，数字去 [EVIDENCE.md](EVIDENCE.md)，冻结边界见 [RELEASE.md](../RELEASE.md) 和 ADR 0030/0031。

## 掌握门

通过八站路线至少要满足下面全部条件：

- 能不看稿列出 10 个 Agent 状态，并解释 `REPLY` 与 `FALLBACK` 两条终止路径。
- 能说明为什么缓存放在意图之后，以及它牺牲了什么、换回了什么。
- 能解释 0.95 与极性守卫为什么不是二选一，为什么跨租户隔离不靠阈值。
- 能说明 Qdrant/ES 当前 hit@5 打平，为什么仍保留双引擎，且不把未来收益说成已实现。
- 能说明工具 2 轮上限覆盖和截断的链式形态。
- 能解释 Redis 不可用时限流为什么放行，以及为什么 DB 唯一约束仍要保留。
- 能区分 readiness、deps 和总健康三个运维口径。
- 能从 `EVIDENCE.md` 找到至少三条红值的原始产物与复现入口。
- 能把 `93.3%` 与 `95.6%` 分开讲，并解释分意图承诺项仍未达成。
- 能说出 CI 只覆盖 build + 221 JVM tests，不等于全量 17 步活体验收。

## 当前固定基线

```text
JVM tests: 221
Acceptance audit: 95 checks, 0 FAIL
CI: build + 221 JVM tests
ADR range: 0001-0031
```

常用核对命令：

```powershell
.\mvnw.cmd -B -ntp verify
python scripts\verify_eval_judge.py
python scripts\build_loadtest_report.py --strict
python .scratch\shoppilot-mvp\round3-closeout-audit.py
pwsh -NoProfile -File scripts\run-acceptance.ps1
```

## 如何让 Agent 带练

可以直接说：

```text
用站 3 的三道题考我，一次只问一道；我答完再给证据和漏掉的边界。
```

也可以要求更强约束：

```text
随机抽两站做 20 分钟面试。先质疑我的取舍，再让我给源码、命令和限制；不要替我总结。
```

教学状态保存在 [docs/interview-mastery/](interview-mastery/)：mission、resources、notes、learning records 和八份自包含课程都在那里。课程是复习入口，源码与证据仍是最终裁判。
