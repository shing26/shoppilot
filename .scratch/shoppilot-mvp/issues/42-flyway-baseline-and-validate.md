# 42 — Flyway V1 基线 + `ddl-auto: validate` 全档

**What to build:** 给 `shoppilot-biz-mock` 引入版本化迁移体系：`V1__baseline.sql` 固化现有 9 张表的完整 DDL（由 Hibernate 导出后人工审阅，不手猜方言），`ddl-auto` 从 `create` 全档切到 `validate`，并把回滚口径按 Flyway 社区版实况写成「`clean` 重放 + 手工 down 脚本」。

**Blocked by:** None。ADR 0041 已记录本轮为项目所有者的显式政策覆盖（对标《三维度评分体系》而非面试反馈触发）。本票不改判据、阈值、gold、指标分母，也不动 `application.yml` 里任何阈值常量。

**Status:** implemented（2026-09-21）。

- [x] `shoppilot-biz-mock/pom.xml` 引入 `org.flywaydb:flyway-core`（H2 支持在 core 内，无 `flyway-database-h2` 模块）
- [x] 用 `jakarta.persistence.schema-generation.scripts.*` 从 Hibernate 导出一次 DDL，人工审阅后固化为 `src/main/resources/db/migration/V1__baseline.sql`
- [x] `application.yml`：`ddl-auto: create` → `validate`
- [x] `db/rollback/U1__baseline_down.sql` 作为回滚约定载体，文件头写明「社区版无 undo，本文件不被自动执行」
- [x] 新增 JVM 用例 `SchemaMigrationTest`：断言 `flyway_schema_history` 有 `success = true` 行且 9 张表齐备
- [x] 变异对照：改 `V1` 里 `feedback.review_status` 列名 → 聚焦复跑必须红；恢复后绿
- [x] `.\mvnw.cmd -B -ntp verify` 全绿
- [ ] ~~回滚口径与「迁移不改变数据持久性作用域」写进 README~~ —— **并入收口**：口径落在 `db/rollback/U1__baseline_down.sql` 文件头与 `CONTEXT.md` 的「模式迁移」词条，README 只留指向；理由见 Handoff。

**Verify:**

```powershell
.\mvnw.cmd -B -ntp verify
.\mvnw.cmd -B -ntp -pl shoppilot-biz-mock -am -Dtest=SchemaMigrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：全量 `3 + 21 + 249 = 273` 全绿（本票新增 3 条）；聚焦复跑 `Tests run: 3` 全绿；`grep -n "ddl-auto" shoppilot-biz-mock/src/main/resources/application.yml` 输出 `validate`。

## Handoff notes

**关键决策**

- **V1 基线由 Hibernate 导出后固化，不手写。** 导出命令写在 `V1__baseline.sql` 的文件头。这个选择当场就兑现了价值：Hibernate 6 把 `orders.status` 映射成 **H2 原生 enum**（`enum ('CANCELLED','COMPLETED',...)`），`Instant` 一律是 `timestamp(6) with time zone`——手写这两处几乎必错。导出内容逐字未改，只加了注释块、把行尾从 CRLF 归一为 LF；可复算的 diff 命令也在文件头。
- **`validate` 全档统一，包括测试路径。** 3 条 `@SpringBootTest` 继承主 `application.yml`，所以实体与 DDL 一旦漂移会在 273 条用例里当场报错。这正是拒绝「validate 只在主档开」的理由——只开主档等于让一致性在 CI 里无人看管。代价是 V1 写错的爆炸半径是全模块起栈失败，所以第 6 条变异对照是必须做的。
- **回滚口径按社区版实况写，不假装有 undo。** Flyway 社区版没有 `undo`（Teams 才有）。`db/rollback/U1__baseline_down.sql` 放在 `db/migration/` 之外正是为了不被 Flyway 当迁移扫描到；它文件头写明了两条回滚路径（整库 `clean` 重放 / 手工单版回退）并明确「本文件不被自动执行」。本仓的常规路径其实是第一条——H2 是内存库，每次起栈本来就是干净世界。
- **README 只留指向，口径落在代码旁。** 原计划的验收项写的是「写进 README」，实际落在 `U1__baseline_down.sql` 文件头 + `CONTEXT.md` 词条。理由：README 是产品与交付说明，回滚口径是运维事实，放在回滚脚本自己的文件头里离使用点最近、最不容易和脚本脱节。这是一处验收项的措辞修正，不是范围缩水。

**验证落点**

- 全量：`.\mvnw.cmd -B -ntp verify` → `3 + 21 + 249 = 273` 绿（biz-mock 从 15 增到 21，本票 3 条 + 票 43 的 3 条）。
- 聚焦：`SchemaMigrationTest` 3/3 绿（迁移已应用 / 9 表 3 索引齐备 / `ddl-auto` 仍是 validate）。
- **变异对照实测**：把 `V1` 里 `review_status varchar(12)` 改成 `review_state varchar(12)` 后聚焦复跑 → `Tests run: 3, Failures: 0, Errors: 3`，根因 `org.hibernate.tool.schema.spi.SchemaManagementException: Schema-validation: missing column [review_status] in table [feedback]`；恢复后 3/3 绿。
- 基线对照：改动前 `3 + 15 + 249 = 267` 绿，改动后 `3 + 21 + 249 = 273` 绿，gateway 与 tool-api 用例数未动。

**踩到的坑（值得记住）**

- **设了 `jakarta.persistence.schema-generation.scripts.action=create` 会关掉 `ddl-auto` 派生的 database action**（JPA 规范行为：只指定 `scripts.action` 时只生成脚本、不建库）。导出时这不影响——脚本照样写出来了；但要知道，否则会以为导出命令坏了。导出时看到的 `Table "ORDERS" not found` 就是这个原因，不是配置错误。
- **Flyway 10 在 H2 上把历史表连表带列都建成带引号的小写名**（`"flyway_schema_history"` / `"version"` / `"success"`）。裸写表名会报 `Table "FLYWAY_SCHEMA_HISTORY" not found (candidates are: "flyway_schema_history")`，裸写列名会报 `Column "VERSION" not found`——查询必须加引号。这处踩了两轮才过。
- **重命名迁移文件后必须清模块 `target`**：Maven 不删陈旧资源，`target/classes/db/migration/` 里留着旧版本文件会让 Flyway 报 `Found more than one migration with version 2`，起栈即失败。

**你需要能当场回答的三个追问**

1. "H2 是内存库，加迁移体系有什么意义？" —— 迁移管的是**表结构怎么产生**，与数据活多久无关。`ddl-auto: create` 在生成姿态上是硬伤：schema 没有版本、没有审阅记录、改不了、回不去。换成 V1 基线 + `validate` 之后，实体与 DDL 的漂移会在起栈期和 273 条用例里当场报错，而不是在生产上悄悄错位。
2. "`validate` 会不会让测试变得很脆？" —— 会，而且这正是要的。第 6 条变异对照就是证据：改一个列名，3 条用例全红、根因直指 `missing column [review_status] in table [feedback]`。脆的是「DDL 与实体不一致」这件事，不是测试本身——不一致本来就该在第一时间炸。
3. "回滚到底怎么做？" —— 两条路径，都写在 `U1__baseline_down.sql` 文件头。本仓常规走第一条：H2 内存库每次起栈就是干净世界，设 `flyway.clean-disabled=false` 后 `clean` + `migrate` 即可，或直接重启进程。要保留数据时走第二条：手工执行文件里的 `drop table` 再删掉 `flyway_schema_history` 里 V1 那一行。**社区版没有 `undo`，这一点不糊弄。**
