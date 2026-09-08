# 06 — L1 精确缓存、写回资格与进程内 singleflight

**What to build:** 同一个政策问题第二次提问时不碰模型也不碰向量库，直接从 Redis 命中并在 30ms 内返回；错误答案与降级话术永不进缓存。落实 ADR 0003、0006。

**Blocked by:** 05 — 最细竖切

**Status:** done

**Verify:** 同一政策问题连问两次并读模型与 embedding 计数器 -> 第二次两者均为零调用；构造一条降级话术，断言其未进缓存。

- [x] 缓存读取位于意图判定之后；`dynamic` 请求绝不查缓存
- [x] key = `MD5(tenantId + scope + intent + kbEpoch + normalizedQuery)`；归一化含全半角、空白、标点、大小写处理
- [x] 精确命中路径不做向量化（这是 `TP99 < 30ms` 成立的前提，代码里注释标明）
- [x] 条目携带 `intent / tenantId / scope / kbEpoch / sourceRuleIds / modelId`，读取时校验 intent 与 tenant 一致，不一致视为 miss
- [x] 写回资格六条全满足才写；SSE 收尾后由虚拟线程异步写，写失败不影响用户响应
- [x] singleflight 进程内层：同 key 只放一个请求穿透，等待者收完整答案后一次性推送，等待上限 2s 超时则自行穿透
- [x] 负缓存：检索无结果的冷门 query 写 60s 空标记，仅对已准入缓存的意图生效
- [x] 必测用例：写回资格拒绝降级话术；命中路径不产生模型调用与向量调用（用计数器断言）

## Handoff notes

**关键决策**

1. **缓存读取位于意图判定之后**（ADR 0003）。`AgentStateMachine` 先 `triage`，只有 `intent.cacheAdmissible()`（即 `POLICY_*`）才调 `cacheService.lookup()`；`ACTION_*` / `UNKNOWN` / `ESCALATE` 根本不查缓存。这条顺序是"防串号"的第一道闸，不是性能优化。
2. **key = `MD5(tenantId + scope + intent + kbEpoch + normalizedQuery)`**，前缀 `shoppilot:c:l1:`。归一化在 `QueryNormalizer`：全半角、空白、标点、大小写。一次查询生成两个桶 key——平台 scope 与店铺 scope 各一个（ADR 0004），平台级政策不因店铺不同而重复缓存。
3. **精确命中路径不做向量化**，这是 `TP99 < 30ms` 成立的前提，代码里注释标明。向量化只在 L1 miss 后为 L2 服务。
4. **条目携带 `intent / tenantId / scope / kbEpoch / sourceRuleIds / modelId / query`**，读取时校验 intent 与 tenant 一致，不一致视为 miss——即使 key 算错也不会串。
5. **写回资格是七道判定，全过才写**（`WriteBackPolicy`）：意图可准入、答案非空、长度 ≥20、检索有命中、未用工具、未降级、无错误。任务书写"六条"，实现把"空答案"与"过短"拆开，因为拒绝原因要能分别计数。没有这一层，缓存会积累"抱歉系统繁忙已为您转人工"，然后在大促里高速复读道歉语，监控显示拦截率 85% 而实际全在胡说。
6. **SSE 收尾后由虚拟线程异步写回**，写失败只记日志，不影响用户响应——缓存是加速器不是数据源。
7. **singleflight 进程内层**：同 key 只放一个请求穿透，等待者 `CompletableFuture.get(2s)` 拿完整结果后一次性推送，**不做 token 流广播**；超时则自行穿透。等待者等完整结果是刻意的克制——多路 token 转发会把冲刺期拖进泥潭。
8. **负缓存 60s**：检索无结果的冷门 query 写空标记，仅对已准入的意图生效。没有它，大促里"查无此条款"的长尾会反复打满 ES + Qdrant + 模型。

**你需要能当场回答的三个追问**

- *Q：怎么证明命中路径没碰模型和向量库？* A：靠计数器不靠读代码。`shoppilot_llm_calls_total{kind}` 与 `shoppilot_embedding_calls_total{result=remote|in-process-cache}` 是为此专门加的；`scripts/verify-hit-zero-llm.ps1` 冲缓存后同一问题问两次，第二次的模型与远程 embedding 增量必须为 0。
- *Q：为什么 L1 TTL 是 6 小时而不是跟着政策走？* A：跟着政策走的是 `kbEpoch`——纪元一变，key 变了，旧条目自然读不到，TTL 只是兜底回收。政策生效不需要等缓存过期。
- *Q：等待者拿完整结果，那第一个请求的流式体验不是被复制了两遍？* A：不会。穿透者自己流式输出，等待者只在 leader 完成后一次性拿到整段答案并作为单个 `token` 帧推出。代价是等待者没有打字机效果，收益是不用维护 N 路转发；2 秒等待上限保证最坏情况也只是退化成自己穿透。

**验证记录**

`WriteBackPolicyTest` 11 项（含降级话术被拒、七道判定各自的拒绝原因）；`scripts/verify-polarity.ps1` 与压测计数器证明命中路径 `embed_remote_delta = 0`。同一政策问题连问两次：第二次 `meta.cacheLayer=L1`、模型调用增量为 0。
