# 票 220 契约：持久化档验收

**状态**：2026-11-20 冻结
**判定**：验收票（verification-only）。不触碰业务逻辑、内部/对外 API、认证授权、支付、数据删除、数据库 schema 或生产依赖；不改变 compose 默认档与既有验收判据。
**开票依据**：所有者 2026-11-20 指令「开第一张『持久化档验收』票，冻结 plans/<task-id>.md 后再执行」。前序调查确认容器档已默认 `postgres`，但缺少当前环境下的“重启存活 + 备份恢复 + 业务可查”证据。

**契约修订 1（2026-11-20，执行前发现）**：容器 `full` 档只发布网关 8082，不发布
`biz-mock:8091` / `ticket:8092` 到宿主；既有 `verify-backup-restore.ps1` 的宿主 HTTP
调用因此不可达。允许给该脚本新增 `-DockerExec` 分支，用 `docker exec + curl` 调容器内
接口；不改变默认宿主模式、断言、SQL、dump/restore 步骤或通过条件。

## 目标

在现有 `full` 容器档（PostgreSQL 持久档）上补一组可机器复跑的交付证据：

1. 确认 `biz-mock` 与 `ticket` 实际运行 `postgres` profile，且连接 compose 内的 `postgres`；
2. 通过网关注册一个唯一买家账号，并落一张绑定该买家的工单；
3. 重启 `gateway`、`biz-mock`、`ticket` 三个服务容器后，同一凭据仍能登录，且买家能通过网关读回自己的工单；
4. 运行既有 `scripts/verify-backup-restore.ps1`，验证备份、灾难、恢复、LOB 与跨库授权；
5. 恢复后再次确认同一账号与工单仍可读；
6. 把命令、读数、容器镜像、HEAD SHA 与限制写入 `docs/EVIDENCE.md` 和票面 Handoff。

## Allowed paths

- `scripts/verify-persistence-tier.ps1`（新增验收脚本）
- `scripts/verify-backup-restore.ps1`（仅新增 `-DockerExec` 分支，默认行为不变）
- `plans/220-persistence-tier-acceptance.md`
- `.scratch/shoppilot-mvp/issues/220-persistence-tier-acceptance.md`
- `.scratch/shoppilot-mvp/README.md`
- `docs/EVIDENCE.md`（仅追加本轮证据）
- `backups/`（本机演练产物，不入库）

## Acceptance criteria

1. `docker inspect` 或等价命令显示 `shoppilot-biz-mock-1`、`shoppilot-ticket-1` 的
   `SPRING_PROFILES_ACTIVE=postgres` 与 `SHOPPILOT_DB_HOST=postgres`；三者 readiness 均为 `UP`。
2. 创建一个唯一买家账号（`/auth/register`）并取得 `accountId`、`customerId`；创建一张
   `customerId` 与之一致的工单，记录 `ticketId`、`status`、`queue`。
3. 重启 `shoppilot-gateway-1`、`shoppilot-biz-mock-1`、`shoppilot-ticket-1` 后，同一账号
   `/auth/login` 返回同一 `accountId`，`/api/v1/support/ops/tickets/mine/{ticketId}` 返回同一
   `ticketId` 与 `status`。
4. `scripts/verify-backup-restore.ps1` 退出码为 0，演练汇总为 `FAIL 0`；日志包含
   `pg_dump -Fc`、`DROP DATABASE ... FORCE`、`pg_restore`、LOB 与跨库授权断言。
5. 备份恢复演练结束后，不重启服务，再次用同一账号登录并读回同一工单；账号与工单均不丢失。
6. 验证脚本自身输出 `PASS/FAIL` 汇总；失败必须保留原始错误与退出码，不得改断言或删除数据让结果变绿。
7. `git diff --check` 退出 0；证据绑定当前 `HEAD` SHA 与 `git status --short`。
8. 不修改 `docker-compose.yml`、Java 源码、迁移、`application*.yml` 或既有 PowerShell 断言；
   `scripts/verify-backup-restore.ps1` 只允许新增 `-DockerExec` 分支，默认宿主模式逐字保留。

## Verification commands

```powershell
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
pwsh -NoProfile -File scripts/verify-persistence-tier.ps1
pwsh -NoProfile -File scripts/verify-backup-restore.ps1
git diff --check
git status --short
```

`scripts/verify-persistence-tier.ps1` 会自行调用备份恢复脚本；若单独运行最后一条，必须确保
`.env` 已提供 `SHOPPILOT_INTERNAL_TOKEN` 与 `SHOPPILOT_DB_PASSWORD`。

## Forbidden

- 不把默认档从 H2 内存库改成持久库；本次只验容器 `full` 档。
- 不执行 `docker compose down -v`、删除 `postgres-data` 卷、清空 `bizmock` / `ticket` 库或
  重置既有数据来“让验证通过”。
- 不修改 `docker-compose.yml`、Java、Flyway 迁移、认证/API、提示词、gold、阈值或既有断言；
  `verify-backup-restore.ps1` 只允许新增容器执行分支，不改变默认行为与通过条件。
- 不把 secret 写入 `docs/`、票面、日志或命令输出；证据只记录“已设置”。
- 不把 `readiness UP` 单独当成持久化证据；必须包含重启后的业务读回与恢复演练。
- 发现缺陷时只记录复现与原始读数，另立修复票；本票不顺手改业务代码。

## 边界

- 不验证多实例、跨主机、备份加密、异地备份、Postgres TLS 或生产 SLA。
- 不证明前端“持久档/演示档”标识；那是后续产品面票。
- 容器镜像与当前源码可能不是同一构建，证据必须同时记录镜像 ID 与 HEAD SHA，不能把旧镜像的结果写成当前源码已验证。
