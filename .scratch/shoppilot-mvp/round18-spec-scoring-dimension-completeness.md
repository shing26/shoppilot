# round18 规格：补齐评分维度完备性（B8 数据层 + B9 测试体系）

> 依据：ADR 0041（重开依据与范围）｜基线：HEAD `fb8eacf`（v1.0.0 `7f4334c`，round17 已收口，22 步活体矩阵已跑过并登记）
> 对标物：《项目开发判断标准-三维度评分体系》（`D:\WorkBuddyData\项目开发判断标准-三维度评分体系-20260920.md`，sha256[:8] = `431790b9`）
> 短板来源：`D:\WorkBuddyData\ShopPilot-短板清单-20260921.md`（评分基线 `8d59a44`）

## 基线复核（2026-09-21，对仓现场核对）

短板清单里的两处计数口径与本仓权威数字不一致，本轮按实测口径登记，**不改判据、不改分母**：

| 清单口径 | 实测 | 本仓权威口径 |
|---|---|---|
| 42 文件 / 216 用例 | 42 文件 / **216 个 `@Test` 声明** / **267 条实际执行** | README 与 `docs/EVIDENCE.md` 用的是 **267**（多出的 51 条来自 10 个 `@ParameterizedTest` 展开） |
| 54 处埋点 / 46 个指标名 | **51 个指标名 / 59 个注册点** | 全仓 `src/main` grep 现算（ADR 0030 已立「指标名计数按全仓 grep 现算」）。清单的 46 是漏掉 `RuntimeStateMetrics` 里 5 个字符串常量的数法；「54」在本仓不存在 |

两条口径差异都指向同一个动作：**引用数字时写明数法**。本轮不因此改动任何既有产物，只在 `docs/EVIDENCE.md` 与 README 的相邻处确认 267 与 51/59 的数法可复现。

## 票据拆分

| 票 | 标题 | ADR | 依赖 | 估时 |
|---|---|---|---|---|
| 42 | Flyway V1 基线 + `ddl-auto: validate` 全档 | 0041 | 无 | 0.5 天 |
| 43 | 慢查询优化（V2 索引迁移 + EXPLAIN 前后对照证据） | 0041 | 42 | 0.5 天 |
| 44 | JaCoCo 覆盖率棘轮（报告进 verify + 0 token 门禁脚本） | 0041 | 无 | 0.5 天 |

建议顺序：42 → 43；44 与二者并行即可（它只碰根 pom、CI workflow 与 `scripts/`，与 42/43 的文件面不重叠）。

## 验收判据（每票一组，机器可复跑）

### 票 42 — 版本化迁移体系

1. `grep -c flyway shoppilot-biz-mock/pom.xml` ≥ 1，且**只引 `org.flywaydb:flyway-core`**（H2 支持在 core 内；已核实 Maven Central 不存在 `flyway-database-h2` 模块）。
2. `shoppilot-biz-mock/src/main/resources/db/migration/V1__baseline.sql` 存在，含 9 张表、3 个既有索引（`idx_addr_order`、`idx_logistics_order`、`idx_coupon_customer`）与 1 个唯一约束（`uk_refund_idempotency`），且每张表都带 `tenant_id` 列（`@TenantId` 判别列）。
3. `grep -n "ddl-auto" shoppilot-biz-mock/src/main/resources/application.yml` 输出为 `validate`；`grep -rn "ddl-auto" shoppilot-*/src/main/resources/` 不再出现 `create` / `create-drop` / `update`。
4. `.\mvnw.cmd -B -ntp verify` 全绿，三模块用例数不低于 `3 + 15 + 249 = 267`。
5. 新增 JVM 用例断言迁移真的跑过：`flyway_schema_history` 中 `success = true` 的行数 ≥ 1，且 9 张表在 `INFORMATION_SCHEMA.TABLES` 中齐备（0 token、进 CI）。
6. **变异对照（证明 `validate` 不是摆设）**：把 `V1__baseline.sql` 里任一列改名后聚焦复跑必须变红（实体与 DDL 漂移在起栈期即失败），恢复后全绿。实测读数写进 Handoff。
7. `db/rollback/U1__baseline_down.sql` 存在并在文件头写明「Flyway 社区版无 undo，本文件不被自动执行」；回滚口径进 README 与票 42 的 Handoff。

### 票 43 — 慢查询优化前后对比

1. `db/migration/V2__*.sql` 存在，含两个 `create index`：`feedback(review_status, created_at)` 与 `tickets(created_at)`（注意表名是复数 `tickets`）。
2. 新增 JVM 用例断言**真实数据库**里这两个索引存在（`INFORMATION_SCHEMA.INDEXES`），且两条查询的 `EXPLAIN` 文本命中对应索引名；聚焦复跑绿。
3. **变异对照**：删掉 `V2__*.sql` 后该用例必须变红，恢复后绿。
4. `docs/slow-query-optimization-2026-09-21.md` 存在，含：优化前 / 优化后的 `EXPLAIN` 原文（两个查询各一份）、扫描行数或扇出行数前后、放大种子量后的重复测量 p50/p95 前后、复现命令、以及**口径边界**（H2 内存库的绝对耗时不代表生产，证据价值在计划形态与扇出行数）。
5. `docs/EVIDENCE.md` 新增一行登记该产物与复现入口。
6. 若某条查询实测证明在 H2 上不可区分：**保留红值 + 登记触发条件**，不编数字、不改判据。

### 票 44 — 覆盖率棘轮

1. 根 `pom.xml` 声明 `jacoco-maven-plugin`（版本按根 pom 既有风格显式声明，不依赖 BOM 是否管理），`prepare-agent` + `report` 绑到 `verify`。
2. `.\mvnw.cmd -B -ntp verify` 后各模块 `target/site/jacoco/jacoco.xml` 存在。
3. `python scripts/check_coverage.py` exit 0，并打印**按模块分别的**实测值与门槛；口径 LINE 设闸、BRANCH 只报。
4. **变异对照**：把任一门槛临时上调 1 个百分点后脚本必须 exit 1，恢复后 exit 0。
5. `.github/workflows/ci-subset.yml` 新增第 4 个 0 token step 运行该脚本；CI run 绿且该步输出可见。
6. `docs/EVIDENCE.md` 登记覆盖率数字、门槛值与数法（按模块、LINE 设闸、BRANCH 只报、门槛 = 首次实测值向下取整留余量）。
7. 本 spec 记录「覆盖率从 round15 Out of Scope 移出」的覆盖与理由（范围边界更正，不是判据改动）。

## 冻结与收口

- 本轮结束后回到 ADR 0031 冻结机制，不自动续期。
- 全部票据独立可交付，中途冻结任意时刻项目自洽。
- 收口时同步：tracker README 的 round 表与票索引、`docs/CODE_MAP.md` 的持久化行与测试行、`docs/EVIDENCE.md` 的新增两条、收口审计脚本的 `ROUND_FP` 按 ADR 0023 重锚到本轮起点。

## 禁面核对（ADR 0023）

本轮改动面与内容级禁面**零交集**，已逐条核对：

| 本轮要改 | 是否禁面 | 依据 |
|---|---|---|
| `shoppilot-biz-mock/pom.xml`、根 `pom.xml`、`application.yml`、`db/migration/` | 否 | 禁面只含 gold 三文件、`eval/results/` 的改/删、`knowledge/`、`scripts/verify_eval_judge.py`、本轮起点已在库的旧 ADR |
| `.github/workflows/ci-subset.yml`、`scripts/check_coverage.py`（新增） | 否 | 同上；禁面只钉 `scripts/verify_eval_judge.py` 一支 |
| `docs/adr/0041-*.md`（新增） | 否 | 新写的 ADR 不在 `prior_adrs` 集合内（该集合取自 `ROUND_FP:docs/adr`） |
| `CONTEXT.md`、`docs/EVIDENCE.md`、`docs/CODE_MAP.md` | 否 | 同上 |

`application.yml` 里的阈值不在路径禁面内，这是 ADR 0023 照登的已知缺口；本轮**不动任何阈值常量**，只改 `ddl-auto` 这一个键。

## 登记不执行

1. **B5 可观测性**（清单列为「可选补」）：不执行。触发条件沿用 ADR 0030 第 5 条——本仓出现可机器寻址的 Prometheus/Alertmanager 形态时，ADR 0024 对告警栈的否决自动失效。届时要做的是「46/51 个指标名收束成 3–5 个 SLO 面板 + MTTR 口径」，仍须先处理与 ADR 0013/0015 的冲突。
2. **清单第④项「Agent 雏形能力是否单独出 A 表」**：不执行，且**它不是技术短板而是定位决策**。本仓现状：`AgentState.java` + `AgentStateMachine.java` 的 10 状态机是真实决策循环而非任务生命周期，A2 三段闭环可在 `AgentStateMachine.java:264/273/296/344` 逐行验证。若简历主轴改往 Agent 靠，需另出一份 A 表评分，**两份报告不可混算**（副类型能力永不进入分子或分母）。
3. **跨项目台账回写**：`D:\WorkBuddyData\项目提升计划-20260921.md` 第 3 步的 B-3/B-4 行与 `项目评估结果\` 下的评分报告在本轮执行后会变陈，需回写。那两份产物不在本仓纪律约束范围内，本 spec 只登记这个欠账。

## 收口状态（2026-09-21）

三张票全部实现并各自留 Handoff。当前 JVM verify `3 + 21 + 249 = 273` 绿（round17 收口时为 `3 + 15 + 249 = 267`）。

| 票 | 状态 | 关键落点 |
|---|---|---|
| 42 Flyway 基线 + validate | implemented | `V1__baseline.sql`（Hibernate 导出后固化，9 表/3 索引/1 唯一约束）+ `ddl-auto: validate` 全档 + `SchemaMigrationTest` 3 条 + `db/rollback/U1__baseline_down.sql` |
| 43 慢查询优化 | implemented | `V2__index_feedback_review.sql` + `SlowQueryPlanTest` 3 条 + `docs/slow-query-optimization-2026-09-21.md`；**落地一个索引、否决一个** |
| 44 覆盖率棘轮 | implemented | 根 pom 接 JaCoCo（`prepare-agent` + `report` 绑 verify）+ `scripts/check_coverage.py` + CI 第 4 个 0 token step |

**对本 spec 验收判据的两处修正**（都是记录实测结果，不是放宽判据）：

1. **票 43 第 1 条**原写「`V2` 含两个 `create index`」。实测后只落一个：工单列表的 `tickets(tenant_id, created_at)` 能让计划从 `tableScan` 变成索引，但四个独立轮次的 p50 是 8353/6031/7596/5533 对 6659/5691/5630/6712——**三轮更差、一轮更好，符号会翻转**，等于量不出收益。量不出收益、还偶尔更差的索引不进仓；那条查询的真问题是**无上界**（一次取走某租户全部工单），改查询形状是功能改动，不在本轮范围，已登记触发条件。这与第 6 条「保留红值 + 登记触发条件」是同一条纪律。
2. **票 42 第 7 条**原写「回滚口径进 README」。实际落在 `db/rollback/U1__baseline_down.sql` 的文件头与 `CONTEXT.md` 的「模式迁移」词条，README 只留指向——回滚口径是运维事实，放在回滚脚本自己的文件头里离使用点最近。措辞修正，不是范围缩水。

**验收判据实测落点**

- 票 42：`grep -c flyway shoppilot-biz-mock/pom.xml` ≥ 1 且只引 `flyway-core`；`V1__baseline.sql` 含 9 表 / 3 索引 / 1 唯一约束、每表带 `tenant_id`；`ddl-auto: validate`；全量 273 绿；`SchemaMigrationTest` 断言历史表成功记录与 9 表齐备；**变异对照**改 `review_status` 列名 → `Tests run: 3, Errors: 3`，根因 `Schema-validation: missing column [review_status] in table [feedback]`。
- 票 43：`V2__index_feedback_review.sql` 存在；`SlowQueryPlanTest` 3/3 绿且断言索引存在 + 计划命中；**变异对照**移走 `V2` → `v2IndexesExist` 与 `reviewQueueBeforeAfter` 两条红；四档选择性 × 四轮读数在产物文档里；EVIDENCE 已登记。
- 票 44：根 pom 声明 jacoco `0.8.12`；三模块各产出 `jacoco.xml`；`python scripts/check_coverage.py` exit 0 并打印 `gateway 55.37% / biz-mock 77.49% / tool-api 41.73%` 对门槛 `54.0 / 76.0 / 40.0`；**变异对照**把 gateway 门槛临时改成 56.0 → `COVERAGE FAIL` 且 exit 1；CI 第 4 步已加；round15 spec 的 Out of Scope 行已加换代指针。

**CI 五步门禁的验证（2026-09-21）**

本机各跑过一次，push 后又在干净 Linux runner 上跑了一次，读数如下：

| 步 | 命令 | 本机 | 干净 runner（run `35538377810`，`edbd3b1`，92 s） |
|---|---|---|---|
| 1 构建与 JVM 测试 | `bash ./mvnw -B -ntp verify` | `3 + 21 + 249 = 273` 绿 | 绿 |
| 2 判据自检 | `python3 scripts/verify_eval_judge.py` | `合计 40/40 通过` | 绿 |
| 3 离线重算 | `python3 scripts/run_tool_eval.py --rescore …` | `RESCORE DONE cases=180 files=6 tool_diff=4` | 绿 |
| 4 套件夹具 | `python3 scripts/eval_suites.py` | `SUITE SELFCHECK ok=24` | 绿 |
| 5 覆盖率棘轮（本轮新增） | `python3 scripts/check_coverage.py` | `COVERAGE OK modules=3` | 绿（该步的首次干净 runner 运行即本 run） |

第 3 步在本机落下的 `eval/results/tool-eval-20260921-051558-rescore.csv` 是本机验证副产物（差异集合与基线一致，不携带新信息），已删除、未入库。

**收口时的口径换代**

- `docs/EVIDENCE.md`：新增两行（慢查询负结果、覆盖率棘轮），并在 267 那一行加 round18 换代指针指向 273。
- `docs/CODE_MAP.md`：Biz-Mock 表新增 `db/migration` 行（模式的唯一产生源）；Test Map 新增模式迁移、慢查询计划、覆盖率棘轮三行。
- 收口审计：`ROUND_FP` 按 ADR 0023 重锚到 `fb8eacf`（round18 起点）；`G6_EXPECT` 换代 `[3, 15, 249]` → `[3, 21, 249]`。
- `CONTEXT.md`：新增「模式迁移」词条，消歧与「知识库纪元」的版本轴冲突，并明写迁移不改变数据的持久性作用域。

**本轮已知未做（登记不执行）**

- B5 可观测性：按清单列为「可选补」，未执行。触发条件沿用 ADR 0030 第 5 条。
- 工单列表查询的上界：见上面第 1 条修正。
- 跨项目台账（`D:\WorkBuddyData\`）回写：不在本仓纪律约束范围内，欠账照登。
