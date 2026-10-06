# ADR 0062: B2——凭据生产态守卫、按库用户隔离与备份恢复演练

日期：2026-10-06
状态：已接受
关联：[ADR 0029](0029-bind-address-gated-credential-fail-fast.md)（绑定地址门控）、[ADR 0061](0061-b1-persistence-postgres-profile.md)（B1，§3 留给 B2 的两笔）、[program-a-to-b-upgrade.md](../../.scratch/shoppilot-mvp/program-a-to-b-upgrade.md)（B2 入口）

## 背景与触发

B2（凭据与部署）的触发条件是「B1 完成」——round28 已收口，所有者指示「进行 B2」。B1 还留下两笔明确
挂给 B2 的账：**按库用户隔离**（0061 §3）与 **Postgres 回滚演练**（0061 §2b / 后果节）。

## 现状的真实缺口（逐条对过代码）

1. **姿势守卫只在网关**：`DevDefaultsPolicy` 及其启动阻断（非回环 + 仓库默认凭证 = 拒启）只在
   `shoppilot-gateway`。持有订单、退款与**全部账号**的 biz-mock 与 ticket 在容器档绑 `0.0.0.0`，
   却没有同等收紧——仓库默认的内部令牌能在非回环上活到启动完成。
2. **守卫账本没有数据库口令**：B1 新增的 `SHOPPILOT_DB_PASSWORD` 不在任何姿势检查里。
3. **两库共用一个用户**：biz-mock 与 ticket 都以 `shoppilot` 超级用户连库——一个服务被攻破等于两库全失。
4. **零备份**：`pg_dump` 从未跑过；ADR 0061 承诺的大对象（oid）恢复行为无任何验证。
5. 「回滚」在 PG 侧没有实现形态（社区版 Flyway 无 undo；H2 侧有 `db/rollback/U1` 口径）。

## 决策

### 1. 姿势守卫下沉共享库，数据侧接入，账本扩容

- `isLoopback`（空地址 fail-closed）、`stillDefault` 与三个仓库默认值常量下沉为
  `shoppilot-tool-api` 的 `PostureGuard`——这条语义必须在每个有凭证的进程里同一份实现，
  各进程抄一份迟早分家（`DevDefaultsPolicy` 自己的 javadoc 早警告过）。
- 网关的 `DevDefaultsPolicy` 改为委托共享原语，**公开 API 与行为逐字节不变**（三份既有测试类不动）。
- biz-mock 与 ticket 各加一道同形状的启动阻断，账本两格：**内部令牌** + **数据库口令**
  （仅当数据源是 `jdbc:postgresql:` 时查口令——默认档的 H2 无口令可言，不许误伤）。
  措辞与网关同一家法：点名环境变量、点名当前监听、指回 `127.0.0.1` 的出路。
- **不是**把网关的 EnvironmentPostProcessor 也搬过去：两服务的 yml 占位符自带仓库默认值
  （`${SHOPPILOT_INTERNAL_TOKEN:dev-internal-token-change-me}`），没有「回环时填默认值」的需求
  （干净克隆判据只压在网关上），所以数据侧只需要「非回环即收紧」这一道。

### 2. 按库用户隔离：各自拥有，PUBLIC 收权

- `bizmock_app` / `ticket_app` 两个 LOGIN 角色，**各自是其数据库的 owner**（Flyway 以 owner 身份跑迁移，
  无需额外授权）；`REVOKE CONNECT ON DATABASE … FROM PUBLIC` 后按角色授 CONNECT——
  隔离判据是**跨库连接被 FATAL 拒绝**，不是「权限位看起来对」。
- 刻意**不做**两个口令：两个内部用户共享同一个 `SHOPPILOT_DB_PASSWORD`。隔离的目的是「一个服务被攻破
  不及于另一库」，口令唯一性防的是另一件事（口令泄露面的区分度）——那格收益在本仓的暴露面（数据库
  只发布到宿主回环、无公网）上接近零，多一个凭据变量反而是错配成本。**残余风险如实登记**。
- 初始化脚本从 `.sql` 换成 `.sh`（需要用环境变量传口令；`.sql` 的 init 阶段拿不到）。
  **对既有部署的代价**：init 只在数据目录为空时执行——已初始化的卷要享受隔离必须重建卷或手工执行
  同一段授权（runbook 写进演练脚本注释）。

### 3. 备份与恢复演练 = PG 回滚的实现形态

- `pg_dump -Fc`（大对象默认包含——这正是 ADR 0061 的既定输入）→ **灾难**（`DROP DATABASE … FORCE`，
  服务不重启）→ `pg_restore` → 重放 CONNECT 授权（DROP 掉的是库内授权，角色是集群级的）→ 验证三层：
  **API 层**（同凭据认证返回同一 accountId、工单原样）、**LOB 直读**（`lo_get(transcript)` 逐字相等）、
  **授权层**（跨库连接仍被拒）。**「服务跨恢复不重启」是刻意的验收项**：Hikari 断线重连是投用形态的
  必答，演练顺带验它。
- 备份落在宿主目录 `backups/`（docker cp 取出，二进制安全）；`pg_restore -l` 断言含 `BLOBS` 段。
- **脚本即 runbook**：`scripts/backup-postgres.ps1` / `scripts/verify-backup-restore.ps1` 的步骤注释就是
  恢复手册，不存在第二份文档。
- 不进验收矩阵（28 步不动）：备份步依赖「持久档 + 容器栈」的姿势，而 full 档还可能是本机 H2——
  硬塞进去会让本机 full 必红。**触发**：矩阵按姿势分档（或容器档成为唯一 full 形态）时再加。

### 4. 有意不做（各带触发条件，不许当「已做」）

- **Redis 不备份**：其持久内容（审计事件）已落 PG 的 `audit_event` 表（票 71），出站失败落回执工单
  （票 88）；流尾丢失的最大代价是未消费事件需从来源重建。「丢得起」的语义从 B1 的淘汰策略延伸到备份语义。
  **触发**：出现「只存在于 Redis 的持久状态」（如幂等回放下库）。
- **监控**：round22 的四条告警 + promtool 已是最小集；**长期指标存储与看板**登记 R5
  （触发：部署给非作者使用超过一周，或需要跨会话对账时）。
- **连接串硬化（TLS）**：数据库只发布到宿主回环、流量不出主机。**触发**：DB 离开单主机（跨机/托管）。

## 后果

- 网关行为零变化（三份守卫测试类原样通过）；biz-mock / ticket 各 +1 道启动阻断 + 用例。
- 既有部署（B1 时代的卷）升级到用户隔离需要重建卷或手工授权——runbook 在 init 脚本头注释。
- 演练脚本输出即 EVIDENCE 的 B2 行；`backups/` 加入 `.gitignore`（备份产物永不入库）。
