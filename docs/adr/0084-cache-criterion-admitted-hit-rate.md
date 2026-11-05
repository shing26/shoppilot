# 缓存指标判据改为「准入内命中率 ≥80%」，总拦截率降级为参考读数

Context: 任务书判据「缓存总拦截率 ≥80%」与 ADR 0003 的准入规则结构性冲突——动作意图与
长尾按政策不准入缓存，mix80 流量的可缓存占比实测 78.1%，无论缓存多好总拦截率都到不了 80%。
2026-11-05 复测（票 208）证明准入内命中率恰为 100%：`l1_delta` 与 `admitted_delta` 逐档相等，
每一个被准入的请求都命中。ADR 0030 第 4 条把这条红挂为「触发 = 冲突被显式裁决（改定义或改
架构，二选一写成新 ADR）」。所有者 2026-11-06 裁决：**改定义**，不改架构。

决定：

1. **头条判据换为「缓存准入内命中率 ≥80%」**：分母 = `admitted_delta`（按 ADR 0003 被准入
   缓存的请求），分子 = `l1_delta + l2_delta + flight_delta`。当前实测 **100%**。
2. **「缓存总拦截率」保留为参考读数**，不再挂判据：它回答的是「多少流量被防线路过」，
   74–78% 的读数继续登记，但 80% 这条线不再作用于它。
3. **串号 0 次与 fail-closed 不可协商**（ADR 0003 原文）随判据一起生效：换分母不改变
   准入政策、不改变极性守卫、不改变跨租户过滤。
4. 现有产物不用重跑：`ladder-*.csv` 已含 `admitted_delta` 与三层命中列，`run_loadtest.py`
   与 `run_async_loadtest.py` 的 `interception_admitted` / `admitted_hit` 计算即新口径。

Consequences:

- `docs/current-metrics.json`、`docs/STATUS.md`、`README.md` 指标表三处换判据，旧读数与
  旧判据的冲突说明保留一句（为什么当年挂红），不删历史。
- `shoppilot_cache_admitted_total` / `shoppilot_cache_hit_total{layer}` 计数器不变；
  不新增指标、不改代码。
- 若未来想抬高总拦截率，路径回到 ADR 0003 的准入政策修订（放开某类只读动作），那是
  架构决策，另开 ADR；本 ADR 只改判据定义。
