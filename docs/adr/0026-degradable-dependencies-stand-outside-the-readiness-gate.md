# 可降级依赖不进就绪门，另立 deps 组

Context: 生产就绪度评估批了一句「README 自认的真单点不在就绪门里」。但 `scripts/run-acceptance.ps1` 拿 `/actuator/health/readiness` 当每个 Need 步的放行谓词，`scripts/up.ps1` 等 biz-mock 与网关的 readiness、超时即抛，而 `application.yml` 里 `no-ollama`、`no-embedding-cache`、`nocache`、`nosf`、`no-virtual` 这几档实验 profile 存在的意义就是把某个依赖人为弄残，去证明降级路径成立。把 Qdrant 与 ES 放进 readiness 组，这两类实验一跑整条门禁就不放行，等于用运维口把已经逐点写明的 fail-open 取向改回 fail-closed。

决定：readiness 组保持只有 `readinessState` 不变；Qdrant、ES、知识库装载三个 HealthIndicator 挂在新 group `deps` 下；调试台那颗健康灯与运维面板改读 `deps`，同时补上前端从来就没写过的 `add('bad')` 分支。

## Considered Options

- 三个指标直接进 readiness：否决，理由见 Context，它改的是取向而不是组件数。
- 只加 HealthIndicator、不建新 group：否决。指标不进任何 group 就没人能在一次请求里读全，那颗灯还是得自己拼三个端点，等于把假绿换成三个没人看的真读数。

## Consequences

- 假健康灯与就绪门是同一个 bug 的两端：后端没有可读的依赖状态，前端就只能恒绿。这一票之后两端各有其义，谁红谁绿都能说清是哪一端。
- 钉一条 JVM 用例：ES 置 DOWN 时 `deps` 红、`readiness` 仍 UP、降级路径照常出答案。这条同时是「为什么没放进 readiness」的机器侧答辩。
- 容易反转：将来真要上 K8s 并希望 ES 挂时摘流量，改一行配置把 `deps` 并进 `readiness`，不动代码。
- 知识库与意图质心本就在门内：`StartupInitializer` 与 `IngestRunner` 是 ApplicationRunner，`readinessState` 要等 runner 跑完才 UP，本轮不重复登记，也不新增审计项。
