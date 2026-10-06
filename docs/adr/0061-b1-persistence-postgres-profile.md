# ADR 0061: B1 数据持久化——Postgres 持久档与内存演示档并存

日期：2026-10-06
状态：已接受
关联：[program-a-to-b-upgrade.md](../../.scratch/shoppilot-mvp/program-a-to-b-upgrade.md)（B1 入口清单）、ADR 0052（定位：可运行客服系统雏形）、ADR 0029（密钥门控）、ADR 0041（迁移体系）

## 背景与触发

B1（数据活过一次重启）在 [program-a-to-b-upgrade.md](../../.scratch/shoppilot-mvp/program-a-to-b-upgrade.md) 里的触发条件是
「所有者决定这个仓开始接真实客户的那一刻」。**2026-10-06 所有者指示「继续推进」并明确当前 demo 形态不足**，
据此认定触发到达，开 round28 执行 B1。

按 ADR 0030/0031 的框架，本轮属于**所有者政策覆盖**（B 段入口清单是 2026-10-04 所有者裁定的 program），
不是事实性修正。

## 决策

### 1. 双档并存，默认档不变

| 档 | 数据库 | 用途 | 判据面 |
| --- | --- | --- | --- |
| 默认档（现状） | H2 内存，重启即清 | 演示与验收：每次从同一份种子开始，行为可复现 | **逐字不动** |
| 持久档（新增 `postgres` profile） | PostgreSQL，重启存活 | 投用形态；容器档 full 默认走这里 | 新增，不接管旧判据 |

默认档不动是 A 身份红线的直接推论：28 步活体矩阵、verify-\*.ps1、180 条 gold 的运行前提都是
「内存库 + 全量种子」。把默认档换成真库等于把全部活体判据的运行环境一次性换掉——那不是 B1，
那是把 A 段攒下的可复现性折价掉。持久档的验证单独建（见 §3）。

### 2. Postgres，不是 MySQL

- 本机已有本地化的 `pgvector/pgvector:pg16` 镜像（round76 的 Docker Hub 封锁绕行先例），MySQL 镜像需重新过代理；
- V1 基线文件头记载的「Hibernate 导出后人工审阅固化」流程对 Postgres 方言同样成立，导出产物即基线；
- 两侧库里 Hibernate 对 `@Enumerated(STRING)` / `@Lob` 的落地以**各自导出产物为准**（H2 是原生 enum，
  Postgres 是 varchar+check / oid），不假设「同一份 DDL 两边通用」。

#### 2b. `@Lob` 在 PG 上的落点：oid（实验定案），「text 落点」登记不执行

三种映射各瘸一条腿（2026-10-06 活体 + JVM 实测，非理论推断）：

| 映射 | H2 validate | PG DDL | PG 空值绑定 |
| --- | --- | --- | --- |
| `@Lob`（CLOB 2005） | ✅ | oid | ✅（LO API，绑定与列型一致） |
| `LONGVARCHAR`（-1） | ❌ found clob / expecting text | varchar(n) | ✅ |
| `LONG32VARCHAR`（4001） | ✅ | text | ❌ PG 驱动 `setNull(4001)` 抛 Unknown Types value |

4001 是 Hibernate 私有类型码，没有 JDBC 通用对应物；单注解无法两侧全绿，唯一的出路是按 profile
注册自定义 JdbcType——那是为一个「更好的落点」引入一段按方言分叉的类型代码，不是 B1 该背的复杂度。

**裁定**：维持 `@Lob`，PG 侧落 oid。代价（如实登记）：三个 LOB 列（`feedback.rule_ids` /
`tickets.transcript` / `tickets.payload`）只经 Hibernate 读写（全仓裸 SQL 不碰它们，SeedRunner 的
delete 不涉及），pg_dump / 恢复必须带大对象——这是 **B2 备份/恢复演练的既定输入**，不是被隐藏的坑。
**触发条件**：出现「必须在 SQL 层直接读这三列」或「大对象运维成本实际发作」的场合，重开此决策
（方案：按 profile 注册自定义 JdbcType，把 4001 的绑定翻译成 VARCHAR）。

### 3. 一个实例、两个库

`shoppilot-postgres` 一个容器，内含 `bizmock` 与 `ticket` 两个数据库——两服务继续**物理上不共库**
（票 72 裁定 B 的边界不变：跨服务只走 API），只是共用一个 Postgres 进程。理由：本机全栈内存账
（round23 裁定的「每多一个 JVM 约 500 MB」同类逻辑），两个 Postgres 容器省不出任何隔离收益。
按库隔离已保证「ticket 服务连不上 bizmock 库」之外的事故面；**按用户隔离留给 B2**（登记，不假装做了）。

### 4. Redis 挂卷 + AOF + 淘汰语义

- 挂卷 + `--appendonly yes`：事件流（audit / channel.outbound）的内容不再随容器重建丢失；
- 淘汰策略 `allkeys-lru` → `volatile-lru`：**有 TTL 的键（缓存/会话/幂等）在压力下可被逐出——它们丢得起
  （有 TTL、可重建、幂等层可重放）；无 TTL 的键（事件流，靠 MAXLEN 截断）不参与逐出**。
  「丢得起」的语义界定由此从注释变成配置。
- 网关进程内的出站去重表仍在内存（U4 原样），AOF 不解决它——不重复登记。

### 5. seed 拆两段，各自幂等

| 段 | 内容 | 开关 | 默认 |
| --- | --- | --- | --- |
| 初始化 | 租户（T001-T003） | `shoppilot.bizmock.seed.enabled`（沿用） | true |
| 演示 | 买家 200 + 压测订单 + 优惠券 + 演示固定单 | `shoppilot.bizmock.seed.demo-data`（新增，env `SHOPPILOT_BIZMOCK_DEMO_SEED`） | 默认档 true（现状）；持久档 false |

持久档下演示数据默认不播：5 万条假订单落进真库就是污染。要演示就在持久档上显式开
`SHOPPILOT_BIZMOCK_DEMO_SEED=true`，SeedRunner 既有的「orders 非空即跳过」保证重启不翻倍。

### 6. 凭据

Postgres 口令**不进仓库**（ADR 0029 家法）：`.env` 提供 `SHOPPILOT_DB_PASSWORD`，compose 的
postgres 服务用 `:?` 必需语法——不给就响亮失败，不兜底。用户名默认 `shoppilot`（非机密，容器只发布到回环）。

## 后果

- **验证分两层**：CI 0 token 层新增「迁移集逐版本对齐」守卫（`db/migration` 与 `db/migration-postgresql`
  文件集必须一致，加迁移必须两边一起加）；活体层（本机真 Postgres 容器）验「迁移可应用 + validate 通过 +
  重启存活」，读数登记 EVIDENCE。CI 不跑 Postgres（round15 起不依赖外部服务的纪律不变）。
  **PG 迁移集必须放平级目录**：Flyway 对 `classpath:db/migration` 是递归扫描，子目录里的同版本迁移会当场撞车。
- **B1 预告过的三件事开始挂账**：① 唯一约束在并发下才真正生效（H2 内存掩盖的，现在暴露）；②
  `@TenantId` 过滤在真库上逐条复核（本仓被咬过三次的洞）；③ 幂等键表会涨——**本仓幂等在 Redis（有 TTL），
  此条暂不适用，若将来幂等下库必须先有清理策略**。
- **不做的**（登记，非欠账）：Postgres 回滚演练（B2 备份/恢复一起做）；按用户隔离（B2 凭据治理）；
  `verify-outbound` 等活体门禁在持久档上的重跑（等下一次清场日）。
