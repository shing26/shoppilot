# 29 — 状态进指标面：四组「值随状态变」的读数 + 熔断迁移计数器

**What to build:** 运行时状态从「只有 HTTP 出口一个读数」搬到指标面：熔断器、依赖健康、dev 默认值、写回池饱和，四组各成 gauge/counter。Prometheus 侧与票 23 的灯、票 21 的观测面字段从此说同一件事。票面写老实话：**本轮不写告警规则，只做完前置**（告警栈到位即回到射程，见 ADR 0030 第 5 条）。

**Blocked by:** 27 — 写回池停机 seam 与饱和计数（第四组的队列深度与代跑计数由该 seam 暴露）

**Status:** ready-for-agent

- [ ] 自注册，不引入 `resilience4j-micrometer` 桥（本仓只依赖 circuitbreaker 本体）；命名统一 `shoppilot_*`
- [ ] 组一：`shoppilot_circuit_state` gauge（0/1/2）**必配** `shoppilot_circuit_transition_total{from,to}`——瞬时值会漏掉两次抓取之间的闪断，两者成对才算一组
- [ ] 组二：`shoppilot_dependency_up{dependency}`，与 `deps` 健康组同源，不另造第二套判定
- [ ] 组三：`shoppilot_dev_defaults_in_use`，与观测面既有字段同源
- [ ] 组四：`shoppilot_writeback_queue_depth` gauge，并把票 27 的 `dropped`/`caller_runs` 计数在指标面挂出
- [ ] 每组一条「值随状态变、不随请求变」用例；熔断组另断「闪断只有 transition 计数器看得见，抓取窗口的 state 读数全程正常」
- [ ] 变异反证：摘掉 transition 计数器后，闪断用例必判错
- [ ] README 指标计数的换代归票 30 统一执行，本票只在指标段挂一行「新增名清单见 grep 现算」的指针
- [ ] 零额度；审计项数保持 95；判据、阈值不动

**Verify:** JVM 单测面四组用例 + 变异反证 -> `MeterRegistry` 断言读数 -> 门禁全绿。

