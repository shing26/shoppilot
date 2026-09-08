# 06 — L1 精确缓存、写回资格与进程内 singleflight

**What to build:** 同一个政策问题第二次提问时不碰模型也不碰向量库，直接从 Redis 命中并在 30ms 内返回；错误答案与降级话术永不进缓存。落实 ADR 0003、0006。

**Blocked by:** 05 — 最细竖切

**Status:** ready-for-agent

**Verify:** 同一政策问题连问两次并读模型与 embedding 计数器 -> 第二次两者均为零调用；构造一条降级话术，断言其未进缓存。

- [ ] 缓存读取位于意图判定之后；`dynamic` 请求绝不查缓存
- [ ] key = `MD5(tenantId + scope + intent + kbEpoch + normalizedQuery)`；归一化含全半角、空白、标点、大小写处理
- [ ] 精确命中路径不做向量化（这是 `TP99 < 30ms` 成立的前提，代码里注释标明）
- [ ] 条目携带 `intent / tenantId / scope / kbEpoch / sourceRuleIds / modelId`，读取时校验 intent 与 tenant 一致，不一致视为 miss
- [ ] 写回资格六条全满足才写；SSE 收尾后由虚拟线程异步写，写失败不影响用户响应
- [ ] singleflight 进程内层：同 key 只放一个请求穿透，等待者收完整答案后一次性推送，等待上限 2s 超时则自行穿透
- [ ] 负缓存：检索无结果的冷门 query 写 60s 空标记，仅对已准入缓存的意图生效
- [ ] 必测用例：写回资格拒绝降级话术；命中路径不产生模型调用与向量调用（用计数器断言）

## Handoff notes

（收尾时填写：关键决策 + 你需要能当场回答的三个追问）
