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
| 幂等、fallback、串号、身份否决项 | `README.md` 验收对照、相关 ticket | `scripts/verify-action-loop.ps1`、`verify-idempotency.ps1`、`verify-fallback.ps1`、`verify-polarity.ps1`、`verify_l2_filters.py` + JVM 测试 | `scripts/run-acceptance.ps1` 或单跑对应 `verify-*` | local/dev 模式与作用域必须随结果写全；“可查工单”只保证业务 Mock 进程生命周期。**2026-09-20 换代指针**：22 步全量矩阵的 `fallback` 步红——该步就是 `verify-fallback.ps1`，纯转人工请求 `转人工` 落 `EMOTION_ESCALATION` 而非 `USER_REQUESTED`；机制是情绪门（ADR 0034）位于 INTAKE、先于意图判定，其自身词表未命中该平静问句后交第二层小模型判成 `URGENT`，T0 的 `ESCALATE_WORDS`（含「转人工」）因此没执行到（根因 F2，见 `round17-spec-architecture-completeness.md` 的活体验收登记表）；README 那一行已把 2026-09-10 与 2026-09-20 两条读数并列登记。**2026-09-23 已修（票 45 / ADR 0042）**：显式转人工优先于情绪判定，活体 `verify-fallback.ps1` **7/7 PASS、exit 0**（`USER_REQUESTED / 转人工` 转绿），那条红随之关闭；见下方「274 条 JVM 测试」行 |
| 干净克隆可复现性 | `README.md` 干净检出检查、ADR 0020 | `scripts/clean_clone_check.ps1`；历史落点 `logs/clean-clone-check-*.log` 仅本机 | `pwsh -NoProfile -File scripts/clean_clone_check.ps1` | 脚本默认从 `origin` 克隆；异机和外部作者仍未验证 |
| JVM native OOM 与显式堆上限 | `README.md` 崩溃归因表、ticket 31 | 根目录 14 份 `hs_err_pid*.log` 与侧车 `replay_pid*.log`，均不入库 | 读文件；脚本侧看 `scripts/start-gateway.ps1`、`scripts/start-bizmock.ps1` | 因果链只写到 native OOM 与当时空闲内存，不替读者外推 |
| 全量收口审计 95 项 | ticket 27-31、相关 round spec | `.scratch/shoppilot-mvp/round3-closeout-audit.py`、`round3-closeout-audit.txt` | `python .scratch/shoppilot-mvp/round3-closeout-audit.py` | 审计总数在 round14 保持 95；活体读数与入库产物分开 |
| 267 条 JVM 测试与 Linux 干净 runner（**round18 换代指针：本地 273 绿**，见下一行；本行的 267 与历史落点保持原样） | ticket 32、ticket 33、ticket 34、ticket 35、ticket 36、ticket 37、ticket 38、ticket 39、ticket 41、风格票、README badge | `.github/workflows/ci-subset.yml`、GitHub Actions `ci-subset` run `35328032251`（`8e6437e`，50 s）与 run `35350399414`（`8b16a98`）、run `35430345281`（`791fdb0`，票 41 后 228 绿）、run `35440613321`（`a0047fe`，票 34 两步评测门禁首绿）、run `35501583017`（`fcba75c`，58 s，round17 新增的套件夹具门禁首绿，三步 0 token 门禁一次全过）、`GatewayMainPathJvmTest` | 本地 `.\mvnw.cmd -B -ntp verify`；CI `bash ./mvnw -B -ntp verify` | 2026-09-18 新增 3 条网关主链路 JVM smoke（当时 `3 + 12 + 209 = 224`）；2026-09-19 票 41 新增 4 条工具循环语义用例（228 绿）；票 35 新增 5 条 Prompt 版本化用例（233 绿）；票 36 新增 7 条情绪门用例（240 绿）；票 37 新增 9 条反馈账本与复核队列用例（249 绿）；票 38 新增 6 条渠道入站契约用例（255 绿）；风格票新增 7 条档位矩阵与注入拼装用例（262 绿）；票 39 新增 5 条计划执行语义用例，本地 `3 + 15 + 249 = 267` 绿；票 34 起 CI 另含 0 token 评测门禁（判据自检 40 项断言 + rescore 比对，`RESCORE DONE cases=180 files=6 tool_diff=4` 钉基线；round17 又加了第三步：新增套件判分器的 24 条夹具 `python scripts/eval_suites.py`，套件本体要活体网关、进不了 CI，夹具是 0 token 可跑的那一半）；CI 不覆盖 22 步活体验收、Docker、Ollama、在线评测或压测 |
| **273 条 JVM 测试与 round18 干净 runner 首绿**（round18 换代行，取代上一行的 267；**票 45 换代指针：本地 274 绿**，见下一行） | 本行 + `round18-spec-scoring-dimension-completeness.md` 收口段 | `.github/workflows/ci-subset.yml`、GitHub Actions `ci-subset` run `35538377810`（`edbd3b1`，**92 s，五步门禁一次全过**；后续 `e60b465` 的 run `35565984590` 同样绿）、`SchemaMigrationTest`、`SlowQueryPlanTest` | 本地 `.\mvnw.cmd -B -ntp verify`；CI `bash ./mvnw -B -ntp verify` | round18 票 42 新增 3 条模式迁移用例（Flyway 基线已应用、9 表 3 索引齐备、`ddl-auto` 仍是 validate）、票 43 新增 3 条慢查询计划用例，biz-mock 从 15 增到 21，全仓 **`3 + 21 + 249 = 273`**。CI 门禁从三步扩到**四步**：新增 `python3 scripts/check_coverage.py`（覆盖率棘轮）——该步在干净 Linux runner 上的首次运行即本 run。同 run 的四步读数：构建与 JVM 测试绿、判据自检 `40/40`、离线重算 `RESCORE DONE cases=180 files=6 tool_diff=4`、套件夹具 `SUITE SELFCHECK ok=24`。CI 仍不覆盖 22 步活体验收、Docker、Ollama、在线评测或压测 |
| **274 条 JVM 测试**（票 45 换代行，取代上一行的 273） | 票 45、`docs/adr/0042-explicit-escalation-precedes-sentiment-verdict.md`、`round17-spec-architecture-completeness.md` 的活体验收登记 F2 | `GatewayMainPathJvmTest`（新增第 250 条 `explicitEscalationSurvivesAnUrgentSentimentVerdict`）、`scripts/verify-fallback.ps1` | 本地 `.\mvnw.cmd -B -ntp verify`；`pwsh -NoProfile -File scripts/verify-fallback.ps1`（需栈起 + Ollama 两模型同时驻留） | 票 45 修 ADR 0034 对 ADR 0017 的回归，ADR 0042 定优先级：**显式转人工优先于情绪判定**。gateway 249 → 250（新增 1 条回归用例，非改名），全仓 **`3 + 21 + 250 = 274`**；收口审计 G6 常数随之换代 `[3, 21, 249]` → `[3, 21, 250]`。活体落点：`verify-fallback.ps1` **7/7 PASS、exit 0**（修前 step 7 出 `EMOTION_ESCALATION`、exit 1）。**变异对照**：摘掉 `&& !triageEngine.isExplicitEscalation(query)` → 该用例当场变红（`expected:<USER_REQUESTED> but was:<EMOTION_ESCALATION>`）。情绪门本身未改：修后 `shoppilot_sentiment_escalated_total{emotion=URGENT}` 仍计 1，只有优先级变了 |
| 22 步全量活体验收（本机限定） | round17 票 34-39、票 41、风格票；本机日志不入库 | 落点日志 `logs/acceptance-run-20260920-183028.log` 与 `logs/acceptance/*.log`（**不入库**，见上表 `logs/` 行）；评测冒烟产物 `eval/results/tool-eval-20260920-182940-local-smoke{,-summary.csv,-meta.json}`（入库） | `pwsh -NoProfile -File scripts/run-acceptance.ps1`（首次跑需把本机 Ollama 配成两模型同时驻留：`OLLAMA_MAX_LOADED_MODELS≥2`） | 2026-09-20 落点 **503s、16 步绿 / 6 步红**（plan、hitzero、fallback、emotion、feedback、plansteps）；六步红的四条根因登记在 `round17-spec-architecture-completeness.md` 的"活体验收登记"表，**一条判据都没改**。同一晚更早一次（18:12，2005s、10 步红）是 Ollama 冷启动的环境红：向量化 3s read-timeout < 模型换入换出约 6s → 检索降级 → 写回被 ADR 0006 资格拒掉，缓存相关六步全红，修 Ollama 后转绿。收口审计 G6 常数同期换代 `[3, 12, 206]` → `[3, 15, 249]` |
| 票 39 Plan 改造的全量评测硬闸门 | 本行（README 的工具调用指标行仍以 09-10 产物为准，本行只登记 Plan 改造未引入回归） | `eval/results/tool-eval-20260920-024515-dev-pre39-baseline.*`（改前）、`tool-eval-20260920-030230-dev-post39.*`（改后）、`tool-eval-20260920-021255-dev-round17-baseline.*`（回归现形，留档作缺陷证据） | `python scripts/run_tool_eval.py --tag <标签>`（dev 口径需云端 key；先 `mvnw package` 换 jar 并重启网关） | 同一 180 条 gold、同一栈同一晚；改前/改后均 **选对工具 171/180 = 95.0%**、分意图逐项一致；逐格差异 29 条全在动作类、净值 0（含 `ACT-LOG-06`/`ACT-LOG-09` 一负一正）；dev 口径下每个请求多一跳情绪分类调用，全量评测 token 成本约翻倍（180 条约 40-60 万），跑满全量前必须抬日预算；同晚更早那次 87.8% 的读数是情绪分类器误升级缺陷的证据，缺陷已由 `daa40cf` 修复 |
| 冻结基线同机复测一致（2026-09-17，100/200 并发位） | 本行（仅登记产物；README 指标仍以 0908 产物为准） | `loadtest/results/ladder-l1-perf-20260917-163320-meashit.csv`、`ladder-l1-perf-20260917-163538-meas2.csv` + 对应 `env-l1-perf-20260917-*.json` | `scripts/run_experiment_suite.ps1`（l1 模型 + perf profile，内部调 `scripts/run_loadtest.py`） | commit `0d78e0d`（v1.0.0 冻结后、round16 前）；同机发压 4 worker、`qps_scope=chat-only`、三步全 0 失败 0 限流；MockLLM 固定延迟 + 真实 bge-m3；100u QPS 301.77 / 200u QPS 547.18，与 0908 基线同位 295.63 / 579.70 差在 ±6% 内（1013 QPS 出自 800u 档，本轮未扫）；拦截率 73.22%-74.16% 与声明带 73.2-74.0% 同量级；当时空闲内存仅 0.0-0.5 GB，hit p99 69-170 ms，不承担 README 的 22 ms 未饱和口径 |
| 慢查询优化前后对照（**负结果**：计划变好、耗时没变好） | `docs/slow-query-optimization-2026-09-21.md` | 同报告内的 EXPLAIN 前后原文 + 四档选择性 × 四个独立轮次的 p50 读数；用例写出的原始读数 `shoppilot-biz-mock/target/slow-query-plan-readings.txt`（构建产物，不入库） | `.\mvnw.cmd -B -ntp -pl shoppilot-biz-mock -am -Dtest=SlowQueryPlanTest -Dsurefire.failIfNoSpecifiedTests=false test` | H2 内存库的绝对耗时不代表生产。能站住的是**计划形态**（`tableScan` → 索引，可机器复现）与「索引必须与租户作用域的谓词对齐」这条设计结论。复核队列索引落地（真实队列规模下持平、不是变差）；工单列表的 `tickets(tenant_id, created_at)` **实测后否决**（四轮读数三轮更差一轮更好，符号会翻转，等于量不出收益）。重开条件 = 给该查询加上界，或本仓接上磁盘型数据库 |
| 覆盖率棘轮（LINE 设闸 / BRANCH 只报，按模块分别判定） | 本行 + ticket 44 | 各模块 `target/site/jacoco/jacoco.xml`（构建产物，不入库）；门槛表在 `scripts/check_coverage.py`；CI run `35538377810`（`edbd3b1`）的 `Coverage ratchet (0-token)` 步 | `.\mvnw.cmd -B -ntp verify` 后 `python scripts/check_coverage.py` | 2026-09-21 首次实测 **gateway 55.37% / biz-mock 77.49% / tool-api 41.73%**（LINE）；门槛 = `floor(实测) − 1.0`，那 1pp 是**抖动余量不是目标值**。按模块分别设闸而不设聚合门槛的理由：gateway 249 条与 tool-api 3 条量级差太大，聚合会让量小的模块的回归被掩盖。覆盖率进 CI 是范围边界的更正（round15 spec 的 Out of Scope 那一行已加换代指针），全部门禁判据一字未动 |

## 入账规则

1. 新增或修改对外数字时，先更新生成物，再更新 `README.md` 与本文对应行；不要把新数字只写在聊天、ticket 评论或提交信息里。
2. 生成物应落回 `docs/`、`eval/results/` 或 `loadtest/results/`。只存在于 `logs/` 的读数不能承担干净克隆的复现承诺。
3. 指标未达成时保留红值、判据、归因和限制；不要改分母、阈值、gold 或模式来换一个绿值。
4. 历史文件名是审计接口。除非 ticket 明确要求迁移，不重命名、不压缩、不清理旧产物。
5. `ShopPilot-项目梳理-20260913.md` 不是证据；它是 2026-09-13 的过期快照，且当前未跟踪。

当前 tracker 见 [`.scratch/shoppilot-mvp/README.md`](../.scratch/shoppilot-mvp/README.md)，接手顺序见 [`AGENTS.md`](../AGENTS.md)。
