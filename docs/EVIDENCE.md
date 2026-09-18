# ShopPilot Evidence Map

README 里的数字、否决项和“可复现”声明都应从这里找到证据落点。规则很简单：产品说明引用报告，报告引用原始产物，ticket 保存收口上下文；不要把本机日志当成人人克隆都会有的文件。

## 证据分层

| 层 | 路径 | 是否入库 | 用途 |
| --- | --- | --- | --- |
| 汇总报告 | `docs/*.md`、`docs/*.csv`、`docs/*.png` | 是 | 给人读的结论、曲线、标定和面试材料 |
| 评测原始产物 | `eval/results/` | 是 | 工具调用准确率、重算明细、运行 meta |
| 压测原始产物 | `loadtest/results/` 中保留的 `ladder-*.csv`、`env-*.json`、`sse-ttft-*.csv`、`ttft-attribution-*.csv` | 是 | README 指标和图像的下游输入 |
| Locust 明细 | `loadtest/results/locust-*`、`*_stats.csv`、`*_cpu.txt` | 否 | 本机原始采样，由 `.gitignore` 排除 |
| 收口矩阵与审计 | `.scratch/shoppilot-mvp/` | 是 | ticket、round spec、审计脚本和当时落点 |
| 本机运行日志 | `logs/` | 否 | 全量活体验收、服务输出、截图步骤的现场记录 |
| JVM 崩溃日志 | 根目录 `hs_err_pid*.log`、`replay_pid*.log` | 否 | 票 31 的一手归因现场；干净克隆没有 |

`logs/` 与根目录崩溃日志会出现在 README/ticket 的叙述里，这是有意保留的“当时证据”；它们不是可从仓库复跑的文件。需要长期证据时，把机器可复跑命令、生成物和限制一起落到上表的入库路径，再在本文登记。

## 指标与声明

| 声明 | 权威汇总 | 原始/可复现落点 | 复现入口 | 必须保留的口径 |
| --- | --- | --- | --- | --- |
| 缓存总拦截率 73.2%-74.0% / 78% 任务书口径 | `docs/loadtest-report.md` | `loadtest/results/ladder-l1-perf-20260908-231233-final.csv`、`ladder-mix80-perf-20260908-233023-final.csv` | `python scripts/build_loadtest_report.py --strict` | 分母含 30% 按 ADR 0003 不准入缓存的请求；红未摘，裁决挂起 |
| 命中路径 P99 22 ms / 32-970 ms 扫描 | `docs/loadtest-report.md` | `ladder-l1-*-final.csv`、`sse-ttft-*-sweepmix.csv` | `scripts/run_loadtest.py`、`scripts/run_ttft_sweep.ps1`、`scripts/run_sse_ttft.py` | 22 ms 是未饱和队列读数；SSE 侧是客户端口径 |
| 命中路径零模型/零远程 embedding | `README.md` 请求主链路与验收段 | `scripts/verify-hit-zero-llm.ps1` | `pwsh -NoProfile -File scripts/verify-hit-zero-llm.ps1` | 需要活体栈；计数器增量是断言的一部分 |
| 未命中 TTFT 690-1499 ms | `docs/ttft-sweep.png`、`docs/loadtest-report.md` | `loadtest/results/sse-ttft-20260909-*-sweepmix.csv`、`sse-ttft-20260909-151606-1-attrib.csv` | `scripts/run_sse_ttft.py`、`scripts/ttft_attribution.py` | 只算第一个 `token`；按 `meta.cacheLayer` 与意图分桶 |
| TTFT 725 ms 归因 | `README.md` 未命中归因段 | `loadtest/results/ttft-attribution-20260909-151609-1conn.csv` | `python scripts/probe_embedding_latency.py`、`python scripts/ttft_attribution.py` | Mock 首字 300 ms + 本机 embedding 311 ms 是形态下限 |
| 吞吐 1013 QPS / 1141 QPS | `docs/loadtest-report.md`、`docs/loadtest-curves.png` | `ladder-l1-*-final.csv` + `env-l1-perf-*-final.json`；`ladder-l2-*-l2.csv` + `env-l2-perf-*-l2.json` | `scripts/run_experiment_suite.ps1`、`python scripts/plot_loadtest_curves.py` | `qps_scope=chat-only`；同机发压，峰值 CPU/空闲内存随数字登记 |
| L2 真 embedding 天花板 23.8-64.8 QPS | `docs/loadtest-report.md` | `ladder-l2-perf,no-embedding-cache-20260909-133010-l2emb.csv` + 对应 `env-*.json` | `scripts/run_experiment_suite.ps1` | 每请求真打 bge-m3；`embed_cached=0` |
| 虚拟线程 400/800 并发收益 | `docs/loadtest-report.md` | `ladder-l1-perf,no-virtual-20260909-011351-novirtual.csv` + 对应 `env-*.json` | `scripts/run_experiment_suite.ps1` | 同模型同并发，仅切换虚拟线程和平台线程池 |
| Token 节约 62.4% | `docs/loadtest-report.md` | `ladder-l1-perf,nocache,nosf-*.csv`、`ladder-l1-perf,nocache-*.csv`、`ladder-l1-perf-*-cacheton.csv` | `scripts/run_experiment_suite.ps1` | token 由 perf Mock 模板估算；三档只差防线开关 |
| 工具调用准确率与 95.6% 重算 | ticket 16、ticket 20、`README.md` 指标表 | `eval/results/tool-eval-20260910-075747-dev*`、补跑 `080[6-9]*-dev-budgetfix*`、`tool-eval-20260911-042142-rescore.csv` | `python scripts/run_tool_eval.py`、`python scripts/verify_eval_judge.py`、`--rescore` | 必须分开报告选对工具与填对参数；重算是旧明细 + 新判据，不是重跑 |
| 语义缓存阈值与反义守卫 | `docs/threshold-calibration.md`、`docs/threshold-sweep.csv` | 同目录 CSV/PNG | `python scripts/calibrate_threshold.py` | 0.95 工作点召回实测 0；反义对由极性守卫兜，不靠调门 |
| Hybrid 与 dense-only 对比 | `docs/retrieval-comparison.md` | 同报告内逐条明细 | `python scripts/retrieval_compare.py` | 该语料 16/16 对 16/16，打平也照登 |
| HikariCP 饱和点 | `docs/loadtest-report.md` | `ladder-biz-perf-*-pool2/10/30.csv` + 对应 `env-*.json` | `scripts/run_experiment_suite.ps1` | 记录 pending 峰值、获取均值和池读数；任务书假设未被实测支持 |
| 幂等、fallback、串号、身份否决项 | `README.md` 验收对照、相关 ticket | `scripts/verify-action-loop.ps1`、`verify-idempotency.ps1`、`verify-fallback.ps1`、`verify-polarity.ps1`、`verify_l2_filters.py` + JVM 测试 | `scripts/run-acceptance.ps1` 或单跑对应 `verify-*` | local/dev 模式与作用域必须随结果写全；“可查工单”只保证业务 Mock 进程生命周期 |
| 干净克隆可复现性 | `README.md` 干净检出检查、ADR 0020 | `scripts/clean_clone_check.ps1`；历史落点 `logs/clean-clone-check-*.log` 仅本机 | `pwsh -NoProfile -File scripts/clean_clone_check.ps1` | 脚本默认从 `origin` 克隆；异机和外部作者仍未验证 |
| JVM native OOM 与显式堆上限 | `README.md` 崩溃归因表、ticket 31 | 根目录 14 份 `hs_err_pid*.log` 与侧车 `replay_pid*.log`，均不入库 | 读文件；脚本侧看 `scripts/start-gateway.ps1`、`scripts/start-bizmock.ps1` | 因果链只写到 native OOM 与当时空闲内存，不替读者外推 |
| 全量收口审计 95 项 | ticket 27-31、相关 round spec | `.scratch/shoppilot-mvp/round3-closeout-audit.py`、`round3-closeout-audit.txt` | `python .scratch/shoppilot-mvp/round3-closeout-audit.py` | 审计总数在 round14 保持 95；活体读数与入库产物分开 |
| 224 条 JVM 测试与 Linux 干净 runner | ticket 32、ticket 33、README badge | `.github/workflows/ci-subset.yml`、GitHub Actions run、`GatewayMainPathJvmTest` | 本地 `.\mvnw.cmd -B -ntp verify`；CI `bash ./mvnw -B -ntp verify` | 2026-09-18 新增 3 条网关主链路 JVM smoke；CI 不覆盖 17 步活体验收、Docker、Ollama、评测或压测 |

## 入账规则

1. 新增或修改对外数字时，先更新生成物，再更新 `README.md` 与本文对应行；不要把新数字只写在聊天、ticket 评论或提交信息里。
2. 生成物应落回 `docs/`、`eval/results/` 或 `loadtest/results/`。只存在于 `logs/` 的读数不能承担干净克隆的复现承诺。
3. 指标未达成时保留红值、判据、归因和限制；不要改分母、阈值、gold 或模式来换一个绿值。
4. 历史文件名是审计接口。除非 ticket 明确要求迁移，不重命名、不压缩、不清理旧产物。
5. `ShopPilot-项目梳理-20260913.md` 不是证据；它是 2026-09-13 的过期快照，且当前未跟踪。

当前 tracker 见 [`.scratch/shoppilot-mvp/README.md`](../.scratch/shoppilot-mvp/README.md)，接手顺序见 [`AGENTS.md`](../AGENTS.md)。
