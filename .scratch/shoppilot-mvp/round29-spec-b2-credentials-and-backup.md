# round29 spec —— B2「凭据生产态 + 按库用户隔离 + 备份恢复演练」

> 状态：执行中（2026-10-06 开轮）。
> 依据：[program-a-to-b-upgrade.md](program-a-to-b-upgrade.md) B2 入口（触发 = B1 完成，round28 已收口）；
> ADR 0061 留给 B2 的两笔（按库用户隔离、PG 回滚演练）；[ADR 0062](../../docs/adr/0062-b2-credential-posture-and-backup-restore.md)。
> 性质：**所有者政策覆盖**（B 段 program，所有者 2026-10-06 指示「进行 B2」）。

## 0. 本轮一句话

B1 让数据活过了重启；B2 回答「**这套东西凭什么敢给真实客户用**」：凭证在非回环上不再有默认值可吃、
两个库不再共用一把钥匙、备份恢复不是文档而是演练过的脚本。

## 1. 范围

- **做**：
  1. 姿势守卫原语（`isLoopback` / `stillDefault` / 仓库默认值常量）下沉 `shoppilot-tool-api`；
     网关委托、行为零变化；biz-mock / ticket 各接入一道启动阻断（内部令牌 + 持久档数据库口令）；
  2. 按库用户隔离：`bizmock_app` / `ticket_app` 各自拥有数据库，PUBLIC 收 CONNECT，跨库 FATAL 为判据；
  3. 备份与恢复演练：`pg_dump -Fc`（含大对象）→ DROP FORCE → `pg_restore` → 重授权 → API + LOB + 授权三层验证；
     脚本即 runbook；**服务跨恢复不重启**是验收项。
- **不做**（各带触发条件，见 ADR 0062 §4）：Redis 备份、长期指标存储（R5）、连接串 TLS、备份步进验收矩阵。

## 2. 硬约束

- 判据面零改动：verify-\*.ps1 / 矩阵步数 / gold / 阈值全不动（守卫不得误伤本机回环路径——
  `up.ps1` 链路全部绑 127.0.0.1，姿势守卫在回环上是 no-op）。
- 网关行为零变化：`DevDefaultsPolicy` 公开 API 与语义逐字节保持，其三份测试类一字不改。
- 凭据不进仓库：口令仍走 `.env`；init 脚本从容器环境取。
- CI 九步不变；新增 JVM 用例全部 0 token。

## 3. 验证形态

| 层 | 内容 |
| --- | --- |
| JVM | 姿势守卫用例 ×2 服务（非回环+默认令牌拒启 / 持久档空口令拒启 / 回环 no-op）；网关三份既有测试类原样绿 |
| 活体 | ① 守卫真拒：0.0.0.0 + 默认令牌起 biz-mock → 拒启；② 隔离真拒：`ticket_app` 连 bizmock 库 FATAL；③ 灾难演练：dump → DROP FORCE → restore → 同 accountId / 工单原样 / `lo_get` 逐字 / 跨库仍拒 |

## 4. 票

| # | 票 | 依赖 |
| --- | --- | --- |
| 104 | 姿势守卫下沉共享库 + 数据侧接入 | — |
| 105 | 按库用户隔离 | — |
| 106 | 备份与恢复演练 | 104、105 |
| 107 | 收口 | 104-106 |

## 5. 登记节

- 已初始化的 B1 时代卷升级到用户隔离需要重建（init 只在空数据目录执行）——runbook 在 init 脚本头注释，
  演练以全新卷为准。**演练追加的发现（2026-10-06 活体）**：跨凭证纪元的混合属主会被隔离当场拦住
  （超用户建的历史表，应用角色连 `flyway_schema_history` 都读不了）——库级重建是升级 runbook 的必选项。
- Redis 不备份、R5 监控、TLS 的触发条件都在 ADR 0062 §4；R5 同时登记进 `registered-debt.md`。
- 演练中的 `backups/` 目录为宿主产物，不入库。
- **演练结论（已实跑）**：`verify-backup-restore.ps1` **PASS 14 / FAIL 0，exit 0**——三层验证
  （API 同 accountId / LOB `lo_get` 逐字 / 跨库 FATAL）全绿，服务跨 DROP/restore 不重启。
  首跑的 2 个 FAIL 都是断言侧口径（无 LO 数据的库 pg_dump 本就不产生 BLOBS 段；多字节字符过
  docker→PowerShell 编码），修断言不修判据对象，二跑全绿。
