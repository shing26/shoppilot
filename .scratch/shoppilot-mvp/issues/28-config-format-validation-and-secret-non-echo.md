# 28 — 配置校验只管格式与范围，必填性继续归绑定地址

**What to build:** 配置写错在启动时就判错并点名是哪个字段，而不是等某次请求炸出间接错误。分工是这条票的全部难点：`@Validated` 只接管格式与范围；「必填」继续由 ADR 0029 的绑定地址规则独占——必填是绑定地址的性质，不是字段的性质。加一条：校验器自己不许成为泄密出口。

**Blocked by:** None — can start immediately

**Status:** implemented（`ConfigValidationTest` 22/22；完整 JVM 面 3+12+206=221 绿；回环默认配置真实启动到 `Started GatewayApplication`；round14 共用门禁 `logs/acceptance-run-20260916-095520.log` 17/17、543s，审计 95 项全绿）

- [x] 校验范围照单执行：端口、duration > 0、阈值 0-1、温度 0-2、轮次 ≥1；每一处判错各一条用例，报错点名到字段
- [x] 不给「dev 默认值合法」的字段加任何必填类注解；回环绑定下带默认值启动必须仍然成功（ADR 0020 干净克隆判据的起栈形态不许被校验器打死）
- [x] 用例断言：校验失败的报错信息不回显三处密钥与运维令牌的任何当前值
- [x] 「必填清单」只做机器可读的一格集合断言：配置文件里 `${SHOPPILOT_*}` 占位符集合 == 清单；不写人读的必填文档
- [x] 零额度；审计项数保持 95；判据、阈值不动

**Verify:** JVM 单测面 -> 回环默认配置复跑一次启动断言 -> 门禁全绿。

## Handoff notes

**关键决策**

- `GatewayProperties` 只加格式与范围：12 个 `Duration` 用 `@DurationMin(nanos = 1)`，阈值 0..1、温度 0..2、工具轮次 ≥1；JWT、内部令牌、运维令牌与模型 key 一个必填注解都没加。
- `server.port` 单独由 `ValidatedServerProperties` 绑定校验（1..65535）。它属于 Spring 的 `server` 前缀，不塞进 `shoppilot` 聚合属性，也不把端口是否可监听冒充成校验器职责。
- 回环用例不是只跑一个裸绑定：先加载 `application.yml`，再执行 `DevDefaultsEnvironmentPostProcessor`，并加载 `DevDefaultsConfiguration` 第二道阻断，确认默认凭证被兜住且整条启动前链路没被校验器打死。
- 必填清单只钉 `application.yml` 里 19 个 `${SHOPPILOT_*}` 占位符集合；没有新增人读必填文档，避免和 ADR 0029 的绑定地址规则形成第二份语义。
- 完整 JVM 面实跑 `3 + 12 + 206 = 221` 全绿；随后只启动 Compose Redis，真实回环默认配置起网关并命中 `Started GatewayApplication in 7.061 seconds`，验证后网关与 Redis 都已停止。

**你需要能当场回答的三个追问**

1. 为什么不给四类敏感值加 `@NotBlank`？——必填性是绑定地址的性质，不是字段的性质；ADR 0029 已把回环默认值与非回环 fail-fast 分开裁决，再加必填注解会直接打死干净克隆起栈。
2. 为什么端口校验单独一个属性类？——`GatewayProperties` 的聚合前缀是 `shoppilot`，而 `server.port` 属于 Spring 自身的 `server` 段；单列 `ValidatedServerProperties` 能不扩大聚合边界地校验端口范围。
3. 不回显密钥是怎么保证的？——约束消息只写配置字段名，不拼字段值；测试同时注入 JWT、模型 key、内部令牌、运维令牌并断言失败文本里四个当前值都不出现。

*** Add File: D:/ShopPilot/.scratch/shoppilot-mvp/issues/29-state-gauges-four-groups.md
# 29 — 状态进指标面：四组「值随状态变」的读数 + 熔断迁移计数器

**What to build:** 运行时状态从「只有 HTTP 出口一个读数」搬到指标面：熔断器、依赖健康、dev 默认值、写回池饱和，四组各成 gauge/counter。 Prometheus 侧与票 23 的灯、票 21 的观测面字段从此说同一件事。票面写老实话：**本轮不写告警规则，只做完前置**（告警栈到位即回到射程，见 ADR 0030 第 5 条）。

**Blocked by:** 27 — 写回池停机 seam 与饱和计数（第四组的队列深度与代跑计数由该 seam 暴露）

**Status:** ready-for-agent

- [ ] 自注册，不引入 `resilience4j-micrometer` 桥（本仓只依赖 circuitbreaker 本体）；命名统一 `shoppilot_*`
- [ ] 组一：`shoppilot_circuit_state` gauge（0/1/2）**必配** `shoppilot_circuit_transition_total{from,to}`——瞬时值会漏掉两次抓取之间的闪断，两者成对才算一组
- [ ] 组二：`shoppilot_dependency_up{dependency}`，与 `deps` 健康组同源，不另造第二套判定
- [ ] 组三：`shoppilot_dev_defaults_in_use`，与观测面既有字段同源
- [ ] 组四：`shoppilot_writeback_queue_depth` gauge（+ 票 27 的计数在指标面挂出）
- [ ] 每组一条「值随状态变、不随请求变」用例；熔断组另断「闪断只有 transition 计数器看得见，抓取窗口的 state 读数全程正常」
- [ ] 变异反证：摘掉 transition 计数器后，闪断用例必判错
- [ ] README 指标计数的换代归票 30 统一执行，本票只在指标段挂一行「新增名清单见 grep 现算」的指针
- [ ] 零额度；审计项数保持 95；判据、阈值不动

**Verify:** JVM 单测面四组用例 + 变异反证 -> `MeterRegistry` 断言读数 -> 门禁全绿。
