# ShopPilot Status

当前状态以本页为准；历史读数在 `docs/history/` 与 `docs/EVIDENCE.md`（historical）。

<!-- STATUS:BEGIN -->
> 自动生成，勿手改。命令：`python scripts/gen_status.py`；CI 用 `--check` 防漂移。

- JVM：**684** tests / 0 failures / 0 errors / 0 skipped（117 suites）
- 覆盖率（LINE）：tool-api **45.54%** / biz-mock **81.83%** / gateway **70.09%** / ticket **78.62%**
- CI：[ci-subset](https://github.com/shing26/shoppilot/actions/workflows/ci-subset.yml)（badge 为准）

| 指标 | 当前值 | 判据 | 状态 | 条件 / 证据 |
| --- | --- | --- | --- | --- |
| 命中路径 P99 | 22 ms @200 并发 | <30 ms | 条件达成 | 未饱和队列；800 并发为 480 ms，整体 P99 1200 ms；9-17 复测 69–170 ms |
| 虚拟线程收益 | +61% @400 / +65% @800 | 报出对照 | 达成 | 同机同模型，no-virtual 对 605.19 vs 975.81 QPS |
| Token 节约率 | 62.4% | 报出基线 | 条件达成 | perf/Mock 模板估算，非计费口径 |
| 缓存总拦截率 | 73.2–74.0% / 77.8–78.2% | >=80% | 未达成 | L1 主导；L2/SF 增量为 0；2026-10-17 同口径复跑 52%，见 U10 |
| 吞吐峰值 | 1013 QPS @800 | >=1200 QPS | 未达成 | 4 个 Locust 进程同机发压，单进程约 260 QPS；1200/1600 并发降到 974/862 |
| 未命中 TTFT | 690–1499 ms | <500 ms | 未达成 | 1 并发 690 / 50–200 并发 1031–1049 / 500 并发 1499；Mock 首字 300 ms + 本机 embedding 311 ms |
| 工具调用准确率 | 93.3% 选对 / 90.5% 填对；重算 95.6% | 分意图 >=95% | 未达成 | qwen-plus 180 条；最新全量 4 个意图低于 95%（R15） |
<!-- STATUS:END -->
