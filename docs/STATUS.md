# ShopPilot Status

当前状态以本页为准；历史读数在 `docs/history/` 与 `docs/EVIDENCE.md`（historical）。

<!-- STATUS:BEGIN -->
> 自动生成，勿手改。命令：`python scripts/gen_status.py`；CI 用 `--check` 防漂移。

- JVM：**806** tests / 0 failures / 0 errors / 0 skipped（135 suites）
- 覆盖率（LINE）：tool-api **44.70%** / biz-mock **82.37%** / gateway **70.31%** / ticket **78.62%**
- CI：[ci-subset](https://github.com/shing26/shoppilot/actions/workflows/ci-subset.yml)（badge 为准）

| 指标 | 当前值 | 判据 | 状态 | 条件 / 证据 |
| --- | --- | --- | --- | --- |
| 命中路径 P99 | 22 ms @200 并发 | <30 ms | 条件达成 | 未饱和队列；800 并发为 480 ms，整体 P99 1200 ms；9-17 复测 69–170 ms |
| 虚拟线程收益 | +61% @400 / +65% @800 | 报出对照 | 达成 | 同机同模型，no-virtual 对 605.19 vs 975.81 QPS |
| Token 节约率 | 62.4% | 报出基线 | 条件达成 | perf/Mock 模板估算，非计费口径 |
| 缓存准入内命中率 | 100%（总拦截率参考读数 73.2–78.2%） | >=80%（准入内口径，ADR 0084） | 达成 | 2026-11-06 所有者裁决改判据（ADR 0084）：分母=admitted_delta；复测 l1=admitted 逐档相等；串号 0 与 fail-closed 随判据生效。旧「总拦截率 ≥80%」判据因 mix80 可缓存占比仅 78.1% 结构性不可达，历史挂红记录保留 |
| 吞吐峰值 | 1468 QPS @800 / 1351 QPS @1200（轻量发压器复测） | >=1200 QPS | 达成（复测口径） | 2026-11-05 同网关同时段只换发压器：轻量发压器 1468@800、1351@1200，0 失败；同机 Locust 800 并发仅 1015，历史 1013/1200=974 为发压器共同上限；独立发压节点复核待做 |
| 未命中 TTFT | TTFT 均值 416.9 ms（单连接强制未命中，Verifier 亲跑）；embedding 段 30.1 ms | <500 ms | 达成（sidecar 口径） | ADR 0085 裁定 A 落地（票 212，2026-11-11）：运行期查询向量化切 ONNX int8 sidecar（保 bge-m3 同模型同维），embedding 段 355.7→46.3/30.1 ms、TTFT 771.5→472.7/416.9 ms（实现方与独立 Verifier 双读数）；判据与 Mock 300 ms 夹具零改动；修前红值读数 690–1499 ms 保留 |
| 工具调用准确率 | 179/180 = 99.4% 选对（dev qwen-plus，2026-11-06 复跑） | 分意图 >=95% | 未达成 | 票 168 后全量复跑：POLICY/ESCALATE/UNKNOWN 全 100%，退款定向红点 ACT-RFD-13 转绿、ACTION_REFUND 18/18；唯一红点 ACT-ORD-14（签收吗？没签收的话我要退款）模型不发读工具直接拒答，qwen-plus 服务端行为漂移，12/12 口语探针与 0/5 gold 原句探针并存；local qwen2.5:3b 166/180 |
<!-- STATUS:END -->
