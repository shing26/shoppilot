# 票 220：持久化档验收

Round: 52（验收票，所有者 2026-11-20 直接指令）
ADR: 0061（持久档）、0062（备份恢复）；正文不改　Status: done（2026-11-21，所有者书面签收；与 219/221 统一收口）　Claimed by: root　Blocked by: 无
契约：[plans/220-persistence-tier-acceptance.md](../../plans/220-persistence-tier-acceptance.md)（2026-11-20 冻结）

## What to build

在现有容器 `full` 档上补齐“Postgres 持久化真的可用”的交付证据，不新增业务功能：

1. 核对 `biz-mock` / `ticket` 的 `postgres` profile 与 readiness；
2. 通过网关注册唯一买家并落一张绑定买家的工单；
3. 重启 gateway / biz-mock / ticket 后，同账号登录并通过网关读回工单；
4. 跑 `scripts/verify-backup-restore.ps1`，验证备份、灾难、恢复、LOB 与跨库授权；
5. 恢复后不重启服务，再次读回同一账号与工单；
6. 把命令、读数、镜像 ID、HEAD SHA 与边界写进 `docs/EVIDENCE.md` 和 Handoff。

新增可复跑脚本 `scripts/verify-persistence-tier.ps1`；它只做 HTTP、`docker inspect` 与
`docker restart`，不改业务代码。既有 `scripts/verify-backup-restore.ps1` 增加
`-DockerExec` 分支：容器 `full` 档不发布 `8091/8092`，用 `docker exec + curl` 调容器内接口；
默认宿主模式与全部断言保持不变。

## 边界

- 不改 `docker-compose.yml`、Java、Flyway、API、认证、提示词、gold、阈值或既有断言；
  `verify-backup-restore.ps1` 只新增容器执行分支，默认行为不变。
- 不把默认 H2 档改成持久档；本次只验容器 `full` 档。
- 不执行 `docker compose down -v`、不删 `postgres-data` 卷、不清库。
- 不把 secret 写入仓库或证据；只记录“已设置”。
- 发现缺陷时记录复现，另立修复票，不在本票顺手改业务代码。

## Verify

```powershell
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
pwsh -NoProfile -File scripts/verify-persistence-tier.ps1
pwsh -NoProfile -File scripts/verify-backup-restore.ps1
git diff --check
git status --short
```

## 验收项

- `biz-mock` / `ticket` 实际为 `SPRING_PROFILES_ACTIVE=postgres`、`SHOPPILOT_DB_HOST=postgres`，
  三者 readiness `UP`。
- 重启前后同一买家 `accountId` 不变；同一工单 `ticketId` 与 `status` 不变，且通过网关
  `/api/v1/support/ops/tickets/mine/{ticketId}` 可读。
- 备份恢复演练退出 0、`FAIL 0`，覆盖 `pg_dump -Fc`、`DROP DATABASE ... FORCE`、
  `pg_restore`、LOB 与跨库授权。
- 恢复后不重启服务，同一账号与工单仍可读。
- `scripts/verify-persistence-tier.ps1` 输出 PASS/FAIL 汇总；失败保留原始错误与退出码。
- `git diff --check` 退出 0；证据绑定 HEAD SHA 与 `git status --short`。

## Handoff notes

**Status**：done（2026-11-21；所有者书面签收；验收-only，工作树内完成，与 219/221 统一收口）

### 关键决策

- 验收对象是容器 `full` 档，不是本机 H2 默认档；默认档的可复现性保持不变。
- 新增可复跑脚本而不是只跑一次性命令，便于后续回归。
- 业务读回走网关真实入口：`/auth/register`、`/auth/login`、
  `/api/v1/support/ops/tickets/mine/{ticketId}`；不走内部接口冒充产品链路。
- 容器 `full` 档不发布 `8091/8092`，因此给 `verify-backup-restore.ps1` 增加
  `-DockerExec` 分支（`docker exec + curl`）；默认宿主模式、SQL、dump/restore 与全部断言不变。
- 首次容器模式实跑抓到两个脚本缺陷并修正：`curl -X Post` 被 Spring 判 405（统一大写）；
  局部变量 `$container` 大小写不敏感覆盖参数 `$Container`（改名 `$targetContainer` / `$svcContainer`）。
  修正后才得到绿读数，没有放宽断言或删数据。

### 验证落点

- `pwsh -NoProfile -File scripts/check-ps-syntax.ps1` → `SYNTAX CHECK DONE files=36 errors=0`。
- `pwsh -NoProfile -File scripts/verify-persistence-tier.ps1` → **PASS 20 / FAIL 0**。
  - `biz-mock` / `ticket` 均 `SPRING_PROFILES_ACTIVE=postgres`、`SHOPPILOT_DB_HOST=postgres`；
    三者 readiness `UP`。
  - 买家 `t220515446` / `accountId=U1e51c451e4b7` / `customerId=C001`；工单
    `T1795177582376-1` / `OPEN`。
  - `docker restart` gateway / biz-mock / ticket 后，同账号登录返回同一 `accountId`，通过网关
    读回同一工单与状态。
  - `scripts/verify-backup-restore.ps1 -DockerExec` → **PASS 14 / FAIL 0、exit 0**；覆盖
    `pg_dump -Fc`、`DROP DATABASE ... FORCE`（服务不重启）、`pg_restore`、LOB 直读
    `len=50` 逐字相等、跨库授权拒绝。恢复后同一账号与工单仍可读。
- 容器镜像：gateway `sha256:7863e098...0a5a7`、biz-mock `sha256:097ece9...8188f`、
  ticket `sha256:5ed8e95...53b3`。HEAD `20fe0965c47ab06e2d67788386c28f7e5769ae27`。
- 本机证据：`logs/persistence-tier-220-20261120-202620.log`；备份
  `backups/bizmock-20261120-202642.dump`（3,839,109 bytes）与
  `backups/ticket-20261120-202642.dump`（40,298 bytes），均不入库。
- `git diff --check` 退出 0；`git status --short` 含本票新增/修改文件与既有 round51/票 219
  工作树改动，未清理、未混入本机日志/备份。

### 三个现场追问

1. **为什么不用 H2 文件库？** ADR 0061 已判：H2 内存档是演示与矩阵复现的前提，持久化走
   Postgres profile；把默认档换成文件库会一次性改变全部活体判据的运行环境。
2. **重启哪些容器？** 只重启 gateway / biz-mock / ticket，不重启 postgres；这样验证的是
   “服务进程重启后数据仍在库里”，而不是 Docker 卷有没有挂。
3. **备份恢复脚本会 DROP 数据库，为什么不另建库？** 这是 ADR 0062 的灾难演练形态；脚本
   自带备份与恢复，且要求服务不重启，正好验证 Hikari 断线重连。脚本会留下 drill 账号与
   工单作为证据，不清理。

### 边界

- 当前运行镜像不是 HEAD 源码重新构建：gateway 镜像构建于 2026-11-10、biz-mock/ticket 于
  2026-11-01；本次证明的是这套部署镜像的持久档行为，不是 HEAD 源码的构建产物。
- 本机容器 full 档没有发布 8091/8092，默认宿主模式未实跑；本次实跑的是新增的
  `-DockerExec` 分支。默认分支仅做了语法检查与代码对照，不能写成“已在宿主模式复验”。
- 未验证多实例、跨主机、备份加密/异地带、Postgres TLS 与前端持久档标识。

### 所有者书面签收（2026-11-21）

- 所有者对话内书面指令：**「签署 219/220 后统一收口」**，与 219/221 一并签收。
- 签收范围：AC1–AC8；以既有读数为准（`verify-persistence-tier.ps1` PASS 20/0，
  `verify-backup-restore.ps1 -DockerExec` PASS 14/0、exit 0）。
- 边界随签收保留：容器镜像不是 HEAD 源码重编（gateway 2026-11-10、biz-mock/ticket
  2026-11-01）；只实跑 `-DockerExec` 分支，默认宿主模式未实跑；不证明多实例、跨主机、
  备份加密/异地带或 Postgres TLS。README 的持久化档边界说明属交付文档同步，不扩大功能面。
