# 47 — embedding 段服务端计时器（补分段耗时唯一盲区）

**What to build:** 给向量化那一段补一个服务端 Timer。现状是分段耗时里唯一没有服务端计时器的一段：`shoppilot_ttft_seconds`、`shoppilot_retrieve_dense_seconds`、`shoppilot_retrieve_lexical_seconds`、`shoppilot_llm_latency_seconds` 都有，只有 embedding 没有，于是 `scripts/ttft_attribution.py:137-143` 只能自己探针量一次放进算式（脚本注释自陈「新问法的远程向量化没有计时器包住，只能自己量一次放进来」）。而它恰好是未命中 TTFT 里占比最大的一项（311 ms / 725 ms）。

**Blocked by:** None（只碰 `knowledge/EmbeddingClient`、`scripts/ttft_attribution.py`、`docs/`；与其余五票文件面不重叠）。

**Status:** implemented（2026-09-24）。

口径（ADR 0044 已定，本票只执行）：

- **Timer 必须带 `result` 标签**，取值与既有 `shoppilot_embedding_calls_total` 一致（`remote` / `in-process-cache` / `singleflight-merge`）。`EmbeddingClient.embed()` 同时承载两条路径：命中进程内缓存近零耗时、真打远程约 0.9 s。不带标签的 Timer 会把两者混成一个数，**比现在的探针估算更糊**——这是本票唯一的失败模式。
- **保留探针作为回退**：网关未起时 `ttft_attribution.py` 仍要能出数，所以是「优先读指标、读不到再探针」，不是删掉探针。
- **换测量方法导致数字变化按「测量保真度提升」处理，不是摘红**：旧值并列保留 + 换代指针。若新读数与 311 ms 探针估值差异显著，**差异本身就是结论**，写进 Handoff，不为了「和旧值一致」调数字。

- [ ] `knowledge/EmbeddingClient.java` 新增 `Timer shoppilot_embedding_latency_seconds`，按 `result` 标签分桶，与既有计数器同风格登记
- [ ] 计时覆盖 `embed(String)` 的两条路径（进程内缓存命中 / 真打远程），单飞合并路径按实际耗时归属
- [ ] JVM 用例：命中进程内缓存时断言计时器 `result=in-process-cache` 被记录且远程未被调用（既有 seam 已能区分）
- [ ] JVM 用例：真打远程时断言 `result=remote` 被记录
- [ ] 变异对照：Timer 的 `result` 标签去掉或写成固定值 → 上面用例变红
- [ ] `scripts/ttft_attribution.py`：优先读新指标，读不到回退到 `probe_vectorize()`；注释写明两种口径的差
- [ ] `README.md:211` 与 `docs/loadtest-report.md` 相关行加换代指针（旧值并列保留，不摘红）
- [ ] `docs/EVIDENCE.md` 的「TTFT 725 ms 归因」行登记新测量方法与新读数
- [ ] 覆盖率：新增 gateway 代码带用例，`python scripts/check_coverage.py` 仍 exit 0（gateway 门槛 54.0，余量仅 1.37pp）

**Verify:**

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/check_coverage.py
pwsh -NoProfile -File scripts/up.ps1
python scripts/ttft_attribution.py
```

预期：全量用例数按实测增加（票 46 收口时为 `3 + 21 + 253 = 277`）；覆盖率 exit 0；归因脚本打印新的分段读数并说明取值来源（指标 or 探针）。

## Handoff notes

**关键决策**

- **三桶与三个计数器同分法，并把它做成机器断言。** `shoppilot_embedding_latency_seconds` 的 `result` 标签取值与既有 `shoppilot_embedding_calls_total` 逐字一致（`remote` / `in-process-cache` / `singleflight-merge`）。这不只是风格统一：同分法让「逐桶计数相等」成为一条可断言的不变式（`bucketsStayAlignedWithCounters`），一旦有人给离线路径也加了计时、或改了一个桶的标签，这条立刻红。**分开两个指标各记一套分法也能出数，但那样就没有这条不变式了。**
- **进程内缓存命中记零耗时，不是不记。** 它没有外部往返，记 `Duration.ZERO` 是陈述事实；好处是「三桶之和 = 总耗时」这条算式成立。第一版想的是「近零的桶是噪音、干脆不记」，被自己否掉了——不记的话三个桶的计数就凑不齐，不变式也就没了。
- **失败的调用照记耗时。** Micrometer 的 `record(Supplier)` 走 `finally`，所以超时的调用也会落进 `remote` 桶。这是有意的：调用方等到的就是那个耗时，把它排除掉会让「慢」从延迟曲线上消失，而失败次数另有 `shoppilot_embedding_failure_total` 单独计数。这一点写进了字段 javadoc。
- **抽出 `awaitLeader` 只为计时。** 计时器不接受受检异常，而等待者那段 try/catch 抛的是 `IllegalStateException`。抽成私有方法后调用点能用 `record(Supplier)`，异常语义一字未动。
- **归因脚本是「优先读指标、回退探针」而不是「换掉探针」。** 网关没起的时候归因脚本仍要能出数（它本来就有一半工作在离线读 CSV），所以探针保留为回退路径；两种口径的差别写进了脚本注释与 README，**因为它们是两个不同的量**。

**验证落点**

- 新增 `EmbeddingLatencyTimerTest`（5 条，0 token、假 Ollama 用 `HttpServer`）：远程桶计时且均值 ≥50 ms（上游睡 150 ms）、缓存命中桶记零且不打远程、并发等待者记在 merge 桶（1 remote + 7 merge）、三桶计数与计数器逐桶相等、关掉去重层时每个请求都记 remote 且缓存桶零样本。
- **变异对照**：把 `cacheHitTimer` 的标签从 `in-process-cache` 改成 `remote` → **5 条里 3 条红**（`cacheHitIsTimedWithoutRemoteCall`、`bucketsStayAlignedWithCounters`、`dedupeOffRecordsEveryRequestAsRemote`），恢复即绿。
- **第一版踩的坑**：最后一条原本断言「缓存桶的 Timer 不存在」（`find(...).timer()` 为 null）。红了——三个 Timer 在构造期就 `register` 好了，桶是**存在但零样本**。判据改成「计数为零」，这本来也是更强的断言（存在但恒零的桶与恒绿的夹具一样是摆设，要证的是「这条路径一次都没走过」）。
- 全量回归与覆盖率见票 52 的收口读数（本票新增 gateway 代码已配用例，gateway LINE 不得低于门槛 54.0）。
- **活体（2026-09-24 补跑）**：本票验收列里的「活体 console 一步」已跑——`verify-console.mjs` **36/36 全过**。**仍未做**：`ttft_attribution.py` 没重跑（它要求网关切 `perf` profile 并清缓存），所以 README 那行 725 ms 归因**仍是探针口径的原值**，新读数按 round19 spec 的登记「下次跑归因时产生」；归因脚本的指标优先路径已就位、有回退，语法与分支都核对过。

**你需要能当场回答的三个追问**

1. *Q：为什么一定要带 `result` 标签，不带不行吗？* A：`EmbeddingClient.embed()` 同时承载两条路径——命中进程内缓存（近零）与真打远程（本机约 0.9 s）。不带标签会把两者平均成一个数，那个数既不代表缓存命中也不代表远程调用，**比原来「外部探针量一次」更糊**。这正是本票唯一的失败模式。
2. *Q：加了计时器，那个 311 ms 的数字会变吗？* A：会，而且不该假装不会。311 是探针口径（另起一条新问句从外部量），新指标是请求路径上真打远程那些调用的均值（含并发与队列等待）。两个量不可直接相减比较，所以 README 与 EVIDENCE 写的是换代指针 + 口径差异说明，**没有预填一个新数字**——新值要等下次真跑归因才产生。
3. *Q：这一步为什么现在才补？* A：因为它是**唯一**没有服务端计时器的一段。TTFT、稠密检索、词法检索、模型四段早就有了；向量化那一步发生在 L2 查表内部，脚本注释当时就自陈「没有计时器包住，只能自己量一次放进来」。它恰好又是未命中 TTFT 里占比最大的一项（311/725），所以这是「观测盲区」而不是「指标不够多」。
