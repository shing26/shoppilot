# 100 持久档与 Postgres 迁移集（biz-mock + ticket）

**Status:** implemented（2026-10-06）

## What to build

B1（[ADR 0061](../../../docs/adr/0061-b1-persistence-postgres-profile.md)）的地基：两个有库的服务
各新增 `postgres` 持久档，默认档（H2 内存）行为逐字不变。

- biz-mock 与 ticket 各加 `application-postgres.yml`（datasource / driver / `flyway.locations` 指向
  `db/migration-postgresql`）。
- Postgres 方言迁移集：**V1 = 现状全量基线**（按 H2 V1 文件头预告的「换库时这份基线要整体重导出」，
  用 Hibernate 按 PG 方言导出后人工审阅固化），V2-V8（ticket V2）= **版本占位**——内容已并入 V1，
  保留文件是为了两侧迁移版本钟永远同步。
- 两个 pom 加 `org.postgresql:postgresql`（runtime，版本由 Boot 管）。
- 0 token 守卫：`PostgresMigrationParityTest` ×2——两侧迁移文件集按版本号逐字对齐，
  下一个增量（V9 起）只落一边就当场红。

## Blocked by

无。round27 全部收口（[95](95-round27-closeout.md)）。

## 口径

- **依据**：所有者政策覆盖（program-a-to-b-upgrade.md B1 入口清单；2026-10-06 所有者指示「继续推进」）。
- **H2 侧迁移文件一个字节不动**（V3/V4 checksum 教训）；`ddl-auto: validate` 两档都保持。
- LOB 落点以**实测矩阵**定案：三个候选里只有 `@Lob` 两侧全绿（H2 validate ✅ / PG oid 列型与绑定一致 ✅），
  **实体维持 `@Lob` 不动**。曾试过把实体改成 LONG32VARCHAR（PG text，但 `setNull(4001)` 被驱动拒）、
  LONGVARCHAR（绑定对但 H2 validate 炸）——完整矩阵见 Handoff，「PG 落 text」登记在 ADR 0061 §2b。
- 凭据家法（ADR 0029）：`SHOPPILOT_DB_PASSWORD` 无仓库默认值，空串默认 = 连接响亮失败。

## 验收

- JVM：`PostgresMigrationParityTest` ×2 绿；既有全部用例在默认档上行为不变。
- 活体（票 102）：PG 容器上迁移真应用 + validate 通过。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

### 落点

- `shoppilot-{biz-mock,ticket}/src/main/resources/application-postgres.yml`（新）。
- `shoppilot-biz-mock/src/main/resources/db/migration-postgresql/V1..V8`（V1 真内容 + 7 个占位）、
  `shoppilot-ticket/.../migration-postgresql/V1..V2`（V1 真内容 + 1 个占位）。
  **刻意用平级目录而不是 `db/migration/postgresql` 子目录**——Flyway 对 `classpath:db/migration`
  是递归扫描，子目录里的 V1 会被当成同版本迁移当场撞车（第一次 verify 抓到，51 个用例上下文全挂）。
- PG V1 相对导出产物的三处人工补齐（文件头有记）：`idx_audit_tenant_time`（H2 V5 的手工索引，
  实体注解没有它）、`actor_authenticated` 带 `default false`（对齐 H2 V8 后的现状）、
  ticket 侧六条规则 INSERT + `idx_ticket_queue_order`（与 H2 V1 逐字一致）。
- `Feedback.ruleIds` / `Ticket.payload` / `Ticket.transcript`：**实体维持 `@Lob`**（注释里留了取舍理由）；
  PG 迁移侧落 `oid`（导出产物原样）。

### LOB 实验读数（选型依据，不是猜测）

| 映射 | H2 validate | PG DDL | PG 空值绑定 | 结论 |
| --- | --- | --- | --- | --- |
| `@Lob`（现状，CLOB 2005） | ✅ | **oid 大对象** | ✅ | ✅ 唯一两侧全绿，**采纳** |
| `LONGVARCHAR`（-1） | ❌ found clob / expecting text | varchar(n) | ✅ | H2 validate 炸，否决 |
| `LONG32VARCHAR`（4001） | ✅ | text | ❌ Unknown Types value | PG null bind 炸，否决 |

4001 是 Hibernate 私有码，PG 驱动不认；单注解无法两侧全绿，text 落点要按 profile 注册自定义
JdbcType 才能得到——**登记不执行**（触发条件与完整矩阵见 ADR 0061 §2b）。oid 的运维代价
（pg_dump 带大对象）是 B2 备份演练的既定输入。

导出命令在 PG V1 文件头（注意 `create-target` 是**追加**写，重跑前必须删旧产物——本次实验就被追加坑出过双份产物）。

### Flyway 10 的 PG 模块与两个环境坑（活体实测）

- **Flyway 10 起数据库支持拆成独立模块**：不引 `flyway-database-postgresql` 就是
  `Unsupported Database: PostgreSQL 16.15` 当场拒启（两个 pom 已加，runtime，版本随 BOM）。
- **Flyway 对 `classpath:db/migration` 是递归扫描**：PG 迁移放子目录就是「Found more than one
  migration with version 1」51 用例全挂；已改平级目录 `db/migration-postgresql`。
  另注意 Maven 不清陈旧产物：挪目录后 `target/classes` 里的旧子目录还在，verify 前先清（本轮踩过）。

### 现场追问

1. 为什么 PG V1 是「现状全量」而不是复刻 H2 的历史演进？——H2 V2 的索引、V7 的表都已含在实体模型里，
   「忠实复刻」做不到干净切分；行业惯例就是给存量库做 baseline，V2-V8 占位保版本钟同步。
2. 两侧 SQL 内容不比吗？——两方言差异是刻意的（原生 enum vs check、clob vs text），比内容等于比出「必须相同」的假约束；文件集对齐才是要守的形状。
3. 为什么 `docker compose config` 现在会拒？——`:?` 必需语法是设计：不给密码就响亮失败（ADR 0029 同族）。
