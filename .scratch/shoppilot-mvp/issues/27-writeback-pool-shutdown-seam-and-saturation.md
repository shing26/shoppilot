# 27 — 写回池停机 seam 与饱和计数

**What to build:** 缓存写回从「状态机直接持线程池」的形状里搬出来，住进专用 seam（`cache/WriteBackPool`），从此停机有宽限、丢弃有计数、饱和有读数。运维按 `stop.ps1` 停栈后，排队里的写回在 5 秒宽限内落完；落不完的进 `shoppilot_writeback_dropped_total`，一笔都不静默。队列满时「请求线程代跑远程 I/O」这个既有策略第一次被数得出来：代跑了几次、队列压了多深。

**Blocked by:** None — can start immediately

**Status:** implemented（五用例绿 + 两发变异反证已打；G6 常数换代与 README 其余记账按惯例留给落点，票 30 承接）

- [x] 新 seam `cache/WriteBackPool`：`submit()` 内部自带 `RequestTrace.wrap`（提交点不再各自记得包）；`AgentStateMachine` 的写回提交点整体迁入，状态机不再直接持有执行器
- [x] `close(Duration)`：shutdown → awaitTermination → 超时则 shutdownNow 并计 `shoppilot_writeback_dropped_total`（dropped 只数「没被线程拿走」的，边界写在 javadoc，不假装一条不漏）
- [x] 池关闭后到达的提交走调用线程代跑——最坏是慢，不是丢，也不是抛进黑洞
- [x] `CallerRunsPolicy` 包一层带计数的拒绝策略：`shoppilot_writeback_caller_runs_total`；队列深度经 `queueDepth()` 对外暴露（票 29 第四组的供数处）。现成策略在 shutdown 后是**静默丢**（源码写着 `if (!e.isShutdown()) r.run();`），所以拒绝策略必须自造——变异 2 已实证
- [x] `server.shutdown: graceful` 进公共配置；HTTP 收尾用 Spring 默认 30s 不调；写回池 drain 宽限 5s
- [x] README 运维段一格：35s 总预算句（本票在 README 里唯一允许动的文字）
- [x] `stop.ps1` 加一格「等端口释放超时 ≥ 停机预算」（默认 40s），不在预算用尽前误判「停了」
- [x] 五条不起容器用例（`WriteBackPoolTest` 5/5 绿）：宽限内跑完不丢 / 超时才丢且 dropped 计数对 / 池关后提交走调用线程 / 队列满时代跑被计数 / 队列深度读数随实际积压变化
- [x] 变异反证两发已打：摘掉宽限（await 改 0ms）→ 「宽限内跑完」与「饱和不丢」两格当场红；换回现成 `CallerRunsPolicy` → 「关后代跑」与「代跑计数」两格当场红。计数断言是硬断言，摘表即摘线
- [ ] 审计 95 项与 G6 常数换代（3+12+174→3+12+179=194）待落点复跑；本轮与票 28-31 共用一次门禁（round13 同法）

**Verify:** JVM 单测面五用例 + 三变异反证 -> 本地起栈跑 `stop.ps1` 目测端口释放时序（活体只登记当时状态，不当证据）-> 门禁全绿。
