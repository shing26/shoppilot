# 27 — 写回池停机 seam 与饱和计数

**What to build:** 缓存写回从「状态机直接持线程池」的形状里搬出来，住进专用 seam（`cache/WriteBackPool`），从此停机有宽限、丢弃有计数、饱和有读数。运维按 `stop.ps1` 停栈后，排队里的写回在 5 秒宽限内落完；落不完的进 `shoppilot_writeback_dropped_total`，一笔都不静默。队列满时「请求线程代跑远程 I/O」这个既有策略第一次被数得出来：代跑了几次、队列压了多深。

**Blocked by:** None — can start immediately

**Status:** implemented（五用例绿 + 两发变异反证已打；round14 共用门禁 `logs/acceptance-run-20260916-095520.log` 17/17、543s，G6 已换代）

- [x] 新 seam `cache/WriteBackPool`：`submit()` 内部自带 `RequestTrace.wrap`（提交点不再各自记得包）；`AgentStateMachine` 的写回提交点整体迁入，状态机不再直接持有执行器
- [x] `close(Duration)`：shutdown → awaitTermination → 超时则 shutdownNow 并计 `shoppilot_writeback_dropped_total`（dropped 只数「没被线程拿走」的，边界写在 javadoc，不假装一条不漏）
- [x] 池关闭后到达的提交走调用线程代跑——最坏是慢，不是丢，也不是抛进黑洞
- [x] `CallerRunsPolicy` 包一层带计数的拒绝策略：`shoppilot_writeback_caller_runs_total`；队列深度经 `queueDepth()` 对外暴露（票 29 第四组的供数处）。现成策略在 shutdown 后是**静默丢**（源码写着 `if (!e.isShutdown()) r.run();`），所以拒绝策略必须自造——变异 2 已实证
- [x] `server.shutdown: graceful` 进公共配置；HTTP 收尾用 Spring 默认 30s 不调；写回池 drain 宽限 5s
- [x] README 运维段一格：35s 总预算句（本票在 README 里唯一允许动的文字）
- [x] `stop.ps1` 加一格「等端口释放超时 ≥ 停机预算」（默认 40s），不在预算用尽前误判「停了」
- [x] 五条不起容器用例（`WriteBackPoolTest` 5/5 绿）：宽限内跑完不丢 / 超时才丢且 dropped 计数对 / 池关后提交走调用线程 / 队列满时代跑被计数 / 队列深度读数随实际积压变化
- [x] 变异反证两发已打：摘掉宽限（await 改 0ms）→ 「宽限内跑完」与「饱和不丢」两格当场红；换回现成 `CallerRunsPolicy` → 「关后代跑」与「代跑计数」两格当场红。计数断言是硬断言，摘表即摘线
- [x] 审计 95 项与 G6 常数换代（3+12+206=221）在同一次 round14 共用门禁落点复跑全绿（round13 同法）

**Verify:** JVM 单测面五用例 + 三变异反证 -> 本地起栈跑 `stop.ps1` 目测端口释放时序（活体只登记当时状态，不当证据）-> 门禁全绿。

## Handoff notes

**关键决策**

1. **写回从状态机直接持线程池，迁进 `cache/WriteBackPool` seam。** 状态机只提交任务，seam 统一负责 `RequestTrace.wrap`、队列、拒绝策略、停机与读数。
2. **停机语义是 shutdown → awaitTermination → 超时 shutdownNow。** 宽限内尽量落完；强制结束后没被线程拿走的任务进 `shoppilot_writeback_dropped_total`。dropped 不宣称“一条不漏”，只精确表示拒绝时仍在队列里的笔数。
3. **拒绝策略必须自造，不能直接用现成 `CallerRunsPolicy`。** 现成策略在 executor shutdown 后静默丢弃；本实现让池关闭后的提交走调用线程，最坏是慢，不是黑洞，并且每次代跑进 `shoppilot_writeback_caller_runs_total`。
4. **队列深度是 TP99 口径的一部分。** `queueDepth()` 与 caller-runs 计数让“命中路径 22 ms 是未饱和读数”可验证；饱和时请求线程可能替池去付远程 I/O。
5. **停机预算固定为 HTTP 30s + 写回 drain 5s。** `server.shutdown: graceful` 负责 HTTP 收尾，`stop.ps1` 等待端口释放默认 40s，不能早于预算宣告“停了”。

**你需要能当场回答的三个追问**

- *Q：为什么写回需要一个专用 seam，不能直接给 `ExecutorService` 多包几个监听？* A：停机、队列深度、代跑计数和 trace 传播需要共享同一套状态；散在状态机与裸池上无法保证同一时刻读到一致语义，也无法在拒绝策略里准确计数。seam 把这些行为收在一个可测试对象里。
- *Q：为什么不直接使用 JDK 的 `CallerRunsPolicy`？* A：源码在 executor shutdown 后会判断 `isShutdown()` 并静默返回，任务就丢了。本实现自造拒绝策略，关闭后仍让提交线程执行并计 `caller_runs`，所以“慢”是可见的，“丢”只在宽限耗尽后明确计数。
- *Q：`dropped` 与 `caller_runs` 分别是什么？* A：`dropped` 是强制停机时仍在队列、没被工作线程取走的写回；`caller_runs` 是队列饱和或池关闭后，由调用线程代跑的次数。前者表示停机损失，后者表示饱和代价，不能合并成一个数。

**验证记录**

`WriteBackPoolTest` 五条覆盖宽限内完成、超时丢弃、关闭后提交、饱和代跑与队列深度；两发变异分别摘掉 await 和换回现成策略，均使对应断言当场判红。已知边界：五条用例都在 JVM 内，真停栈时序只记当时活体观察；历轮压测读数来自显式堆上限之前的启动形态，跨形态不承诺逐位复现。
