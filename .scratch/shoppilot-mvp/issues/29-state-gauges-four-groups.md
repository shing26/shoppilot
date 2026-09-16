# 29 — 状态进指标面：四组「值随状态变」的读数 + 熔断迁移计数器

**What to build:** 运行时状态从「只有 HTTP 出口一个读数」搬到指标面：熔断器、依赖健康、dev 默认值、写回池饱和，四组各成 gauge/counter。Prometheus 侧与票 23 的灯、票 21 的观测面字段从此说同一件事。票面写老实话：**本轮不写告警规则，只做完前置**（告警栈到位即回到射程，见 ADR 0030 第 5 条）。

**Blocked by:** 27 — 写回池停机 seam 与饱和计数（第四组的队列深度与代跑计数由该 seam 暴露）

**Status:** implemented（`RuntimeStateMetricsTest` 5/5；transition 变异反证已打；round14 共用门禁 `logs/acceptance-run-20260916-095520.log` 17/17、543s，三模块 3+12+206=221、审计 95 项全绿）

- [x] 自注册，不引入 `resilience4j-micrometer` 桥（本仓只依赖 circuitbreaker 本体）；命名统一 `shoppilot_*`
- [x] 组一：`shoppilot_circuit_state` gauge（0/1/2）**必配** `shoppilot_circuit_transition_total{from,to}`——瞬时值会漏掉两次抓取之间的闪断，两者成对才算一组
- [x] 组二：`shoppilot_dependency_up{dependency}`，与 `deps` 健康组同源，不另造第二套判定
- [x] 组三：`shoppilot_dev_defaults_in_use`，与观测面既有字段同源
- [x] 组四：`shoppilot_writeback_queue_depth` gauge，并把票 27 的 `dropped`/`caller_runs` 计数在指标面挂出
- [x] 每组一条「值随状态变、不随请求变」用例；熔断组另断「闪断只有 transition 计数器看得见，抓取窗口的 state 读数全程正常」
- [x] 变异反证：摘掉 transition 计数器后，闪断用例必判错（实跑得 `-1.0`，恢复后回绿）
- [x] README 指标计数的换代归票 30 统一执行，本票只在指标段挂一行「新增名清单见 grep 现算」的指针
- [x] 零额度；本票不新增审计项，审计项数仍为 95；判据、阈值不动

**Verify:** JVM 单测面四组用例 + 变异反证 -> `MeterRegistry` 断言读数 -> 门禁全绿。

## Handoff notes

**关键决策**

- 熔断器提成 `CircuitBreakerConfiguration` 的 bean，`BizMockClient` 只消费它；状态 gauge 与迁移 counter 由 `RuntimeStateMetrics` 在同一对象上自注册，未引 `resilience4j-micrometer` 桥。
- `shoppilot_dependency_up` 动态取 `deps` 健康组成员，再读 `HealthEndpoint.healthForPath("deps", dependency)`；成员、状态判定与票 23 的灯都来自同一份配置和端点。
- `shoppilot_dev_defaults_in_use` 只回答“是否有任一处仍在吃默认值”，取观测面同一个 `DevDefaultsPolicy.devDefaultsInUse()`；写回队列直接读 `WriteBackPool.queueDepth()`，dropped / caller_runs 沿用票 27 已注册计数。
- `deps` 组缺失时启动失败：指标面要求四组都在，静默漏一组比配置面直接判红更难查。

**你需要能当场回答的三个追问**

1. 为什么不用 `resilience4j-micrometer` 桥？——本仓只需要 circuitbreaker 本体，桥会额外引入一整层自动命名与标签规则；这里 gauge/counter 各自只有一条清晰语义。
2. 为什么只报 `shoppilot_circuit_state` 不够？——两次 Prometheus 抓取之间可以 OPEN 后立刻 CLOSED，瞬时 gauge 看不见；`transition_total{from,to}` 专门保留这类闪断。
3. 依赖指标为什么不直接调 Qdrant / ES？——那会复制 `DependencyHealthConfiguration` 的判定；这里直接读 `deps` 健康组的端点读数，灯与指标天然说同一件事。
