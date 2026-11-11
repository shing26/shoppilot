# ShopPilot Status

当前状态以本页为准；历史读数在 `docs/history/` 与 `docs/EVIDENCE.md`（historical）。

<!-- STATUS:BEGIN -->
> 自动生成，勿手改。命令：`python scripts/gen_status.py`；CI 用 `--check` 防漂移。

- JVM：**790** tests / 0 failures / 0 errors / 0 skipped（132 suites）
- 覆盖率（LINE）：tool-api **44.70%** / biz-mock **81.91%** / gateway **69.60%** / ticket **78.20%**
- CI：[ci-subset](https://github.com/shing26/shoppilot/actions/workflows/ci-subset.yml)（badge 为准）

| 指标 | 当前值 | 判据 | 状态 | 条件 / 证据 |
| --- | --- | --- | --- | --- |
| 命中路径 P99 | 22 ms @200 并发 | <30 ms | 条件达成 | 未饱和队列；800 并发为 480 ms，整体 P99 1200 ms；9-17 复测 69–170 ms |
| 虚拟线程收益 | +61% @400 / +65% @800 | 报出对照 | 达成 | 同机同模型，no-virtual 对 605.19 vs 975.81 QPS |
| Token 节约率 | 62.4% | 报出基线 | 条件达成 | perf/Mock 模板估算，非计费口径 |
| 缓存准入内命中率 | 100%（总拦截率参考读数 73.2–78.2%） | >=80%（准入内口径，ADR 0084） | 达成 | 2026-11-06 所有者裁决改判据（ADR 0084）：分母=admitted_delta；复测 l1=admitted 逐档相等；串号 0 与 fail-closed 随判据生效。旧「总拦截率 ≥80%」判据因 mix80 可缓存占比仅 78.1% 结构性不可达，历史挂红记录保留 |
| 吞吐峰值 | 1468 QPS @800 / 1351 QPS @1200（轻量发压器复测） | >=1200 QPS | 达成（复测口径） | 2026-11-05 同网关同时段只换发压器：轻量发压器 1468@800、1351@1200，0 失败；同机 Locust 800 并发仅 1015，历史 1013/1200=974 为发压器共同上限；独立发压节点复核待做 |
| 未命中 TTFT | 690–1499 ms | <500 ms | 未达成 | 固定项下限：Mock 首字 300 ms + bge-m3 253–311 ms + 检索 15 ms + 编排 104 ms ≥ 672 ms；2026-11-05 探针 252.6/277.3 ms，结论不变；需 ADR 0011 的独立 embedding |
| 工具调用准确率 | 179/180 = 99.4% 选对（dev qwen-plus，2026-11-06 复跑） | 分意图 >=95% | 未达成 | 票 168 后全量复跑：POLICY/ESCALATE/UNKNOWN 全 100%，退款定向红点 ACT-RFD-13 转绿、ACTION_REFUND 18/18；唯一红点 ACT-ORD-14（签收吗？没签收的话我要退款）模型不发读工具直接拒答，qwen-plus 服务端行为漂移，12/12 口语探针与 0/5 gold 原句探针并存；local qwen2.5:3b 166/180 |
<!-- STATUS:END -->
