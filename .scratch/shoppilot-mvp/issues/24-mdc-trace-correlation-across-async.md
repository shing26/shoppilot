# 24 — 四个坐标进 MDC，并跨异步边界手工接上

**What to build:** 一次请求的四个坐标（链路、租户、买家、会话）出现在这条请求打出的每一行日志里，响应发出去之后的那一次缓存写回也认得回它是谁触发的；日志自己按大小与时间轮转落盘。落实 ADR 0027。

**Blocked by:** None — can start immediately

**Status:** done（落点见文末「收尾双轴审查」；双轴审账亦在文末）

**Verify:** 单测面断 MDC 内容、跨线程一致性与轮转配置 -> 实跑一次 local 档问答后按 traceId 全量 grep 能捞出一行 -> 零额度、门禁全绿。

- [x] MDC 四键在鉴权入口一次装填，REST 与流式共用同一个 traceId 来源
- [x] 装填点在任何一行日志之前：那条「忽略客户端自带的租户标识」的告警本身也必须带 traceId，它是最该被追到的一行
- [x] 同一请求内所有日志事件的 traceId 相等且非空（用例）
- [x] 并发两个请求的上下文互不污染（用例）
- [x] 异步边界：缓存写回与工单升级的日志带触发者的 traceId；摘掉任一处手工传播，必有格子当场判错（第十一轮合取项规矩）
- [x] 上下文用完即清，线程复用不残留上一个请求的坐标
- [x] 落盘与轮转：按大小与时间两条策略齐备；文件名与本地 `logs/` 既有约定不冲突
- [x] 不引入 context-propagation 库（ADR 0027 已记这条取舍）
- [x] 身份坐标进日志是合成数据下的取巧，真实投产须换假名标识——这句写进 README
- [x] 零额度；审计项数保持 95

## 收尾双轴审查（fixed point = ab31407）

**这一票买到了什么**：日志第一次能回答「这一行是谁的一单」。改之前 `traceId` 只活在 `SseEventSink` 的
`done` 帧里和一行 `log.error` 里，MDC 全仓零命中；改之后四个坐标由日志模板统一带出，其中「答案已经发出去了、
写回还没落地」那一段本来是彻底认不回触发者的空白，现在那行失败日志带得上链路号。

**Spec 轴（逐勾取证，判据在哪、读数是什么）**：

- 勾 1：`AuthFilter` 是唯一装填点（`RequestTrace.start()` + `bind()`），`ChatController` 两条通道都读
  `RequestTrace.traceId()`，那个类自己的 `java.util.UUID` import 已删——共用来源这件事由「import 不在」而不是由注释保证。
  用例：`AuthFilterTest`「四个坐标在鉴权入口一次装填」、`TraceCorrelationAcrossAsyncTest`「REST 与流式共用同一个
  traceId 来源：响应体与两处日志行都指向它」。
- 勾 2：`start()` 是那段 `try` 的第一句，`warnOnClientSuppliedTenant` 排在它之后。这一条是**变异量出来的**：
  把 `start()` 挪回告警之后，`AuthFilterTest`「装填发生在任何一行日志之前：那条试探告警自己就带 traceId」当场判红，
  复原后转绿。全仓最该被追到的一行由此长在一格上，而不是长在这句话上。
- 勾 3：`RequestTraceTest`「日志事件里的四个坐标：同一请求内所有事件同一个 traceId」用 logback 的
  `ListAppender` 抓真事件（不是抓 `MDC` 的抄本），断言相等且非空。
- 勾 4：两条分开钉——`AuthFilterTest`「并发两单各走各的鉴权：线程复用时租户与买家不许串」，
  `RequestTraceTest`「任务里改 MDC 不许回头污染提交者的快照，也不许影响同一快照的第二次派发」。
- 勾 5：全仓 `main` 源码里异步提交点**由 grep 现算为两处**（`AgentStateMachine` 的写回、`ChatController` 的流式工作线程），
  两处都经 `RequestTrace.wrap`；工单升级发生在 `agent.run(...)` 之内，即已包好的那根工作线程上，
  所以它的日志与流式同源。三处变异各摘掉一处都当场判红：摘流式提交点的 wrap →
  `streamWorkerSharesTheRestTraceId` 红；摘写回提交点的 wrap → `writeBackFailureCarriesTheTriggersCoordinates` 红；
  加上勾 2 那次，共三次变异、三次复原后复验为绿。复原时踩到一次「`Copy-Item` 带回旧时间戳使 maven 判定 class 不需重编」，
  已按票 23 记的坑补验（改完时间戳再量一次「摘掉仍判红」）。
- 勾 6：`clear()` 只删本类那四个键，其余留给别的组件；`wrap` 收尾是**换回**运行前的快照而不是 `clear()`——
  写回池用 `CallerRunsPolicy`，任务有可能就在请求线程自己身上跑，收尾若盲清会把请求后半程的日志抹瞎。
  用例：`RequestTraceTest`「CallerRuns 把任务退回请求线程自己身上时，不许顺手把请求的坐标清掉」、
  「复用线程不残留上一单」、`AuthFilterTest`「401 的三条返回路径同样清场」。
- 勾 7：`logback-spring.xml` 用 `SizeAndTimeBasedRollingPolicy`（大小与时间两条策略齐备的那一个），
  落盘名 `logs/gateway.log` 与本地 `logs/` 既有约定同目录不冲突。这里有一条**必须照登的实现期事实**：
  一开始想用 `-Dspring-boot.run.jvmArguments=-DLOG_APP=ingest` 给入库进程分名，实跑证明那个参数到不了
  `spring-boot:run` 另起的那个 JVM，只有环境变量到得了——现在 `ingest.ps1` 设 `$env:LOG_APP`，
  落点看 `logs/ingest.log` 与 `logs/gateway.log` 各一份。轮转 5 条里有一条真把阈值压到 1 KB 跑出归档，
  钉的是「这条策略是活的」而不是「配置文本里有这个标签」。
- 勾 8：不引 `io.micrometer:context-propagation`，由 `LogbackRotationTest`「不引 context-propagation：
  跨线程靠手工 wrap，这条取舍由类路径本身守住」拿类路径断言守，比注释硬。
- 勾 9：README「已知限制」新增一条，主语是「四个请求坐标直接进日志」这件事本身，写清真实投产要换假名标识、
  并把「谁能在日志平台上按身份键检索」当成与数据库权限同等级的门。
- 勾 10：零额度由审计 A3 活体读；审计项数**保持 95，一项没加**，只把 G6 的当轮常数从
  `3 + 12 + 140 = 155` 换代到 `3 + 12 + 158 = 173`（+18 = `RequestTraceTest` 7、`AuthFilterTest` 4→8、
  `LogbackRotationTest` 5、`TraceCorrelationAcrossAsyncTest` 2），换代理由按第十一轮规矩写在量具旁边。

**Verify 那句的活体部分**：门禁全绿之外，另跑一次 local 档问答（带 `X-Conversation-Id`），
按那次响应的 `traceId` 去 grep `logs/gateway.log`，捞出的行同时带四个坐标——这一条不是单测能代替的，
它测的是「日志模板与真实运行时的 MDC 之间没有第三样东西挡着」。

**Standards 轴：报 6 → 成立待办 1、成立接受 3、报出后不成立 2**（不成立的两条留在账上，免得下一个人重新纠结）：

- S1 [P2] **「新增异步提交点必须经 `wrap` 提交」这条谓词没有机器格子**：本票靠的是「现在只有两处、两处都包了」
  这种一次性取证，第三处提交点出现时不会有任何东西拦。ADR 0027 明写这条要先想清楚依赖形状、
  「本轮不做，按欠账照登」，所以本票不擅自补一格。**这一条按欠账登记，不转票 25/26**：那两张票的主题是错误形状与调试台，
  把它们接过来等于给一条架构守卫找个不合适的家。
- S2 [P3] `RequestTrace.apply()` 在「运行前快照为空」时走 `MDC.clear()`，会连带抹掉别的组件装的键，
  与本类 `clear()` 只删自己四键的口径不对称。接受：那一处的语义是「把这根线程恢复成它进来之前的样子」，
  进来时是空的，恢复成空就是清；只删自己四键反而会把任务里新装的键留给下一单。
- S3 [P3] `FILE` appender 是同步的，每条日志多一次文件写，压测档（`perf` profile）会把它带上热路径。
  接受，但边界写清：本票没在 `perf` 档复量，README 与 `docs/loadtest-report.md` 那批读数**记的是有这格之前的代码**，
  它们是历史证据不是当前主张；将来若在 `perf` 档看到 I/O 抖动，第一个候选就是这一格。
- S4 [P3] `LOG_DIR` 默认值是相对路径 `logs`，换个工作目录起进程就会把日志写到别处。接受：
  仓内所有起进程的脚本都 `Set-Location $root`，而给一个演示项目加绝对路径校验不划算。
- 报出后不成立 1：「流式那一路只有成功才有一行 INFO，失败按 traceId grep 会落空」——不成立，
  `catch` 里那句 `log.error` 就在这根包好的工作线程上，模板会把四个坐标带出来。
- 报出后不成立 2：「`traceId()` 不在链路里返回 null，会把 null 写进响应体」——`/api/v1/support/chat` 只有过
  `AuthFilter` 才可达，装填在验签之前，这条路不存在 null；改成抛异常反而违反 ADR 0027 那句「读日志坐标不该是业务规则」。

**转票**：无。票 25 的 `traceId` 字段自此有了唯一来源，票 26 的调试台不受本票影响；本票欠的那一格（S1）
按 ADR 0027 的口径留在 ADR 与本页，不进 25/26 的勾。

## Handoff notes

**关键决策**

1. **四个坐标在鉴权入口一次装填。** `AuthFilter` 先 `RequestTrace.start()` 再打任何日志；那条“客户端自带 tenantId”的 WARN 本身也带 traceId，因为它是最需要追踪的试探现场。
2. **异步传播靠手工 `RequestTrace.wrap`，不引 context-propagation 库。** ADR 0027 把两条异步提交点钉死：流式工作线程与缓存写回；宿主的工单升级在已包装的工作线程里，同源带出。未来新增提交点必须显式经过 wrap。
3. **wrap 收尾恢复运行前快照，不盲目 `clear()`。** 写回池可能 `CallerRunsPolicy`，任务就在请求线程跑；如果收尾清空，会把请求后半程的日志坐标抹掉。
4. **日志轮转按大小与时间同时生效。** `SizeAndTimeBasedRollingPolicy` 管 `logs/gateway.log`，入库进程通过 `LOG_APP=ingest` 落独立文件；JVM 参数传给 `spring-boot:run` 子 JVM 的路径实测不可靠，环境变量才可靠。
5. **身份坐标进日志是演示形态。** 真实投产必须替换为假名标识，并把日志平台上的身份检索权限当作与数据库权限同级的边界。

**你需要能当场回答的三个追问**

- *Q：为什么不用上下文传播库，手工 wrap 不是更容易漏？* A：本仓异步提交点很少，ADR 0027 明确选择显式边界。手工 wrap 把“跨线程时坐标如何走”放在代码里可见，且不需要把第三方传播器带进热路径；代价是新增提交点没有机器守卫，这条欠账已登记。
- *Q：具体跨了哪些异步边界？* A：流式聊天的工作线程和缓存写回提交。工单升级在 `agent.run(...)` 的已包装线程内，不需要第二个传播点。落点用三发变异证明：摘掉流式 wrap、摘掉写回 wrap、把 `start()` 挪到告警之后，各有对应用例判红。
- *Q：为什么 wrap 结束要恢复快照，而不是清掉四个键？* A：`CallerRunsPolicy` 可能让任务在提交者线程直接执行。恢复快照能保证结束后外层请求仍保留自己的 trace、tenant、buyer、conversation；盲目 clear 只会让后半程日志失去归属。

**验证记录**

`RequestTraceTest`、`AuthFilterTest`、`LogbackRotationTest` 与 `TraceCorrelationAcrossAsyncTest` 覆盖装填时序、并发隔离、恢复语义与轮转策略；活体侧按响应 traceId 在全量日志中反查。已知边界：文件 appender 是同步的，perf 档未复量；日志坐标直接进日志只适用于合成数据，投产需假名化。
