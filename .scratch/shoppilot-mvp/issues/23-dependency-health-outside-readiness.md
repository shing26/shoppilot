# 23 — 依赖健康与就绪门分开，假健康灯变可信

**What to build:** 让「还能应答但已经降级」与「别再给它流量」成为两个各自可读、可断言、并且在页面上看得见的事实：向量、检索、知识库的真实状态挂在新的 `deps` 组，调试台那颗灯第一次有能力变红。落实 ADR 0026。

**Blocked by:** None — can start immediately

**Status:** done（落点 `logs/acceptance-run-20260913-204500.log` 17/17、审计 95 项全绿；nodeps 活体读数与双轴审账见文末）

**Verify:** 单测面置依赖为 DOWN 后同时断三件事 -> 浏览器面断灯 -> 拿一档把依赖弄残的实验 profile 起栈，证明门禁照样放行 -> 零额度。

- [x] 向量、检索、知识库三个健康指示器在位，且都挂在新 `deps` 组下
- [x] `readiness` 组的成员一字未动（这是 ADR 0026 的红线，不是本轮可商量的项）
- [x] 依赖置 DOWN 时一条用例同时断三件事：`deps` 红、`readiness` 仍 UP、答案按既有降级路径给出
- [x] 用把依赖人为弄残的那几档实验 profile 起栈并跑门禁：必须仍然放行——这条就是「没从运维口把 fail-open 改回 fail-closed」的证据
- [x] 页面健康灯改读 `deps`，并补上它从来就没写过的变红分支
- [x] 灯的可用三态在 `verify-console.mjs` 那条既有 seam 里各有断言，不再靠肉眼看颜色
- [x] 摘掉任一健康指示器的注册，至少一格用例当场判错（第十一轮给合取项立的规矩）
- [x] 零额度；审计项数保持 95

## 收尾审账（fixed point = `7f17da6`，落点见 Status 行）

**八条逐条对上证据**，量具是 `DependencyHealthTest`（6 条，0 token）、`verify-console.mjs`（新增 3 条，共 18 条）、
一档新实验 profile 的活体读数，以及那两份门禁矩阵日志。

- 勾 1 由 `depsGroupHoldsTheThreeIndicators` 钉：组里恰好 `qdrant`、`elasticsearch`、`knowledgeBase` 三格，
  成员名取自 `@Bean` 方法名去掉 `HealthIndicator` 后缀——名字写错不会报错、只会悄悄少一格，所以 `contributorId()`
  把这条映射本身也钉了。三格的来路由真实的 `DependencyHealthConfiguration` 注册，不在测试里手搓指示器。
- 勾 2 是红线，钉法有两层：配置面 `readinessGateMembersUntouched` 直接读 `application.yml` 断
  `readiness.include == readinessState`、`liveness.include == livenessState`；端点面在依赖全挂那一格里断
  `members(endpoint,"readiness")` 只有一格。**测试上下文吃的组配置整份取自那份 YAML，不在测试里重抄成员名**——
  有人把 `deps` 并进 readiness，红的是配置那一格，而不是「测试与实现各说各话」。
- 勾 3 是同一条用例里的三件事，且三件事共用同一个「ES 与 Qdrant 都不可达」前提：`deps` DOWN、
  `readiness` UP、业务读路径 `HybridRetriever.retrieve` 不抛异常而标出 `degraded` 且词法路 0 条、融合仍有结果。
  第三条走的是真 `EsRestClient`（指向空端口），不是 mock——要证的正是「同一只死掉的 ES，运维口与业务口各说什么」。
- 勾 4 分两份读数。**其一（依赖弄残那一档）**：新增实验档 `nodeps`（把 `shoppilot.retrieval.qdrant-url` /
  `es-url` 指到 59997 / 59998 两个空端口，与既有 `no-ollama` 同一家法，不碰共用的 16333 / 19200）。
  起栈后：网关日志 `检索基础设施未就绪，网关以降级模式启动` 之后照常 `Started ... in 5.314 seconds`，
  `/actuator/health/readiness` = **UP**、`/actuator/health/liveness` = **UP**，`/actuator/health/deps` =
  **HTTP 503 + 三格全 DOWN**，`scripts/chat.ps1` 问「七天无理由怎么算」拿 **HTTP 200**、
  `intent=POLICY_RETURN triage=T0 citations=0` 与一句正常答案。门禁的 `Test-Ready`（每步的放行谓词，读 readiness）
  当场为真：`run-acceptance.ps1 -SkipBuild -SkipStack -Only fallback,ratelimit,idem` 在这档下
  `idem`、`ratelimit` 两步真跑绿（`logs/acceptance-run-20260913-195335.log`）——放行谓词没被本票改向，这是这一勾要的的性质。
  **同一档下 `fallback` 判红，且不在本票的放行面上**：该脚本第一枪是 `POST /ops/cache/flush`，
  Qdrant 不在时它 500（现场照登在 `logs/acceptance/fallback.log`）。那是「这一步业务真需要那个依赖」而不是
  「运维口把流量摘了」，两者不能混谈——顺带记一笔：那个 500 是一具裸 Spring 错误体，没有 envelope，
  **转票 25**（ADR 0028 的口径：网关自产错误一律 envelope，这一枪是自产的）。
  **其二（依赖齐那一档）**：`local` 档全量门禁 17 步全绿，`stack` 步等的仍是 readiness，一行判据没动。
- 勾 5 由 `healthLightReadsDepsAndHasRedBranch` 钉（读网关下发的静态页）：灯读 `/actuator/health/deps`；
  `classList.add('bad')` 这一支从「从来没写过」变成写出来了，另配 `unknown` 第三态与 `.dot.unknown`；
  并且断 `login()` 函数体里**不再出现 `health`**——「签出 token 就把灯抹绿」是本票缺陷的另一半本体，
  把它写进灯里就永远是绿。灯每轮答完补一次读数（`ask()` 的 `finally`），否则一次真故障要刷新页面才看得见。
- 勾 6 三条断言各喂一份 deps 读数（三格齐 / 两格 DOWN 且 HTTP 503 / 一具不是健康读数的 body），
  每条同时断 `data-state` 与 `className`：只断颜色会放过「CSS 蒙对了但状态机是坏的」，只断状态会放过
  「状态对却没上色」。DOWN 那一档喂真 503，因为 Boot 默认就把 DOWN 映射成 503——页面只认 `body.status`，
  拿 HTTP 码当健康判据等于把 503 又读成「读不到」。三态在**另开的页面**上打桩量，主页面那条
  「页面无失败请求」的断言才不会被自己的桩污染成假红。
- 勾 7 实跑验过，不是推理：把 `knowledgeBaseHealthIndicator` 改名（等价于摘掉注册）后重跑该文件，
  **6 条里 3 条当场判错**——两错在 Boot 自己的组成员校验（`NoSuchHealthContributorException`，少一格直接拒绝起
  上下文），一错在 `eachRegistrationIsLoadBearing` 的名字映射那格。之后按备份复原，并留意一次复原没生效的坑：
  `Copy-Item` 带回原时间戳会让 maven 判定 class 不需重编，改完必须验一次「摘掉仍判红」的读数是拿新 class 量的。
- 勾 8 零额度：`tokensUsedToday` 差值为 0（审计 A3 活体读）；审计项数 **95 不变**，本票没新增审计项，
  只把 G6 的当轮常数从 `3 + 12 + 134 = 149` 换代到 `3 + 12 + 140 = 155`（多出的 6 条全是本票新增），
  换代理由按第十一轮的规矩写在量具旁边。`console` 从 15 项涨到 18 项，README 那三处「15 项」同步换代。

**一条连带必须记在本票名下，因为它是本票自己带来的**：注册三个 `HealthIndicator` 之后，**未分组的**
`/actuator/health` 会把这三格算进总健康，依赖被弄残时它跟着变 503。仓里三处读总健康的判据本意都是
「进程还在不在服务」，拿总健康当判据等于把 ADR 0026 从 readiness 门口挡住的那扇门从另一扇门放进来，
于是这三处一律改读 `readiness`（`run_experiment_suite.ps1` 起网关后的等健康、`run_loadtest.py` 每档结束的
存活探测、`verify-plan-actions.ps1` 第 01 段那句「两服务 health 为 UP」）。改完全仓再无一处拿总健康当放行谓词。
`readiness` 组成员本身一字未动，这一条与那条红线不冲突：红线管的是**组**，这里管的是**读哪个端点**。

**一条本票没做的取舍也留在这儿**：`deps` 三格里没有 embedding / Ollama 那一格。ADR 0026 点名的是 Qdrant、
ES、知识库装载三格，向量化那条路的降级早写在读路径里（`EmbeddingWarmupRetryTest`、`probe_embedding_latency.py`），
加一格等于替 ADR 追加一条它没写过的决定。同理 `knowledgeBase` 只数两引擎的条目，不判「语料对不对」——
那是入库与 `retrieval_compare.py` 的地盘。

**Standards 轴另两条接受项（都不改判据，记在这儿免得下一个人重新纠结）**：

- S2 [P3] `deps` 组配了 `show-details: never`，明细（URL 与条目数）只在未分组总健康里。代价是那颗灯只能点名到
  「哪一格不为 UP」，说不出「因为连不上 16333」。这是有意的：页面上泄内部端口号没有收益，而真要诊断的人本来
  就读得懂未分组那一格。
- S3 [P3] 读一次 `/actuator/health/deps` 会打出四次引擎往返（两格 ping 加两次 `count`）。运维口的轮询频率远
  低于业务口，且这一格不参与摘流量，不值得为它加一层缓存——加了反而要回答「缓存没过期的那一刻，灯说的话算不算」。

## Handoff notes

**关键决策**

1. **依赖健康与 readiness 分家。** Qdrant、ES、知识库装载挂在新的 `deps` 组；readiness 成员一字未动。缓存、检索和模型都按设计可降级，把它们并进就绪门会把 fail-open 从运维口改回 fail-closed。
2. **降级模式必须能被真实起栈。** `nodeps` 实验档把两个检索地址指向空端口后，readiness 仍 UP、服务仍启动、业务请求返回降级答案；门禁的放行谓词因此没有被本票改向。
3. **调试台读 `deps`，不读总健康。** 灯补上红/未知分支，且每次问答后刷新；登录 token 不再把灯抹绿，否则页面永远看不到真实依赖性。
4. **总健康仍只作诊断。** 注册三个 `HealthIndicator` 后，未分组的 `/actuator/health` 会连同可降级依赖一起变红，所以实验、压测和计划脚本全部改读 readiness。
5. **`deps` 不追加 embedding 格。** ADR 0026 只点名 Qdrant、ES、知识库三格；Ollama/embedding 的降级已由读路径与专项测试负责，加一格等于替 ADR 追加决定。

**你需要能当场回答的三个追问**

- *Q：为什么 Qdrant / ES / 知识库不进 readiness？* A：它们降级后系统仍能给降级答案或转人工，ready 的定义是“能否接流量”，不是“全部依赖齐不齐”。并进 readiness 会让实验档在人为弄残依赖时被门禁直接拒绝，等于把 fail-open 改回 fail-closed。
- *Q：`deps` 组修了“假健康灯”的哪一端？* A：修了两端：服务端第一次能明确区分“可接流量但已降级”和“依赖全齐”；调试台第一次能根据真实 `deps` 显示红灯，而不是只在登录时显示一次绿色。
- *Q：如果以后上 K8s，希望 ES 挂时摘流量，要改什么？* A：先写新 ADR 明确这条依赖从可降级变为放行条件，再把对应 indicator 纳入 readiness 或单独的 ingress gate；不能只改脚本或页面，因为那会把运行时语义偷偷改成 stop-the-world。

**验证记录**

`DependencyHealthTest` 六条、`verify-console.mjs` 三态断言、`nodeps` 活体起栈和 `local` 全量 17/17 共同收口。已知边界：`deps` 配了 `show-details: never`，页面只说哪一格不为 UP，不暴露内部端口；该端点每次读取会打引擎 ping/count，未加缓存。
