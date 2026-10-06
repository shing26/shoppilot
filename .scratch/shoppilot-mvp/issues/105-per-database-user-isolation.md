# 105 按库用户隔离（bizmock_app / ticket_app）

**Status:** implemented（2026-10-06）

## What to build

ADR 0061 §3 留给 B2 的那笔：此前两个服务以同一个 `shoppilot` 超级用户连库——一个服务被攻破等于两库全失。

- `deploy/postgres-init.sql` → **`postgres-init.sh`**（init 阶段要从容器环境取口令，`.sql` 拿不到）：
  建 `bizmock_app` / `ticket_app` 两个 LOGIN 角色，**各自拥有**自己的库（Flyway 以 owner 身份跑迁移）；
  `REVOKE CONNECT … FROM PUBLIC` 后按角色授回——隔离判据是**跨库连接被 FATAL 拒绝**。
- compose：postgres 挂载新脚本 + 把口令传进容器环境；两个服务的 `SHOPPILOT_DB_USERNAME` 写死各自角色名
  （用户名不是机密，写死比共享变量清楚）；超级用户 `shoppilot` 只剩运维面（备份/恢复/健康检查）。
- `application-postgres.yml` ×2：默认用户名各自分工（`${SHOPPILOT_DB_USERNAME:bizmock_app}` / `ticket_app`）。
- 接线守卫 `PostgresIsolationScriptTest`（ticket 模块，0 token）：钉 init 脚本的角色/收权/授权/属主四段 + compose 的分工——
  少任何一行，隔离就从事实退化成注释。

## Blocked by

无。与票 104 并行。

## 口径

- **刻意不做两个口令**：两个内部用户共享同一个 `SHOPPILOT_DB_PASSWORD`。隔离的目的是「一个服务被攻破
  不及于另一库」；口令唯一性防的是另一件事，在本仓的暴露面（库只发布到宿主回环）上收益近零。
  **残余风险如实登记**（ADR 0062 §2）。
- **既有卷的代价**：init 只在空数据目录执行——B1 时代的卷要享受隔离必须重建卷或手工执行脚本里的授权段
  （runbook 在脚本头注释）。口令不要带单引号。
- 本机 `.env` 的 `SHOPPILOT_DB_USERNAME` 行已删（那会让本地持久档跑成超级用户，隔离合不上）。

## 验收

- JVM：`PostgresIsolationScriptTest` 2 条绿。
- 活体：`ticket_app` 连 bizmock 库 FATAL、`bizmock_app` 连 ticket 库 FATAL、各自连自己的库放行（演练脚本 5c 三连）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
docker exec -e PGPASSWORD=<pw> shoppilot-postgres psql -U ticket_app -d bizmock -c "select 1"   # 必须 FATAL
```

## Handoff notes

- pg_dump 不含库级属性：owner 与授权随 DROP 丢失、角色是集群级的——**恢复后必须重放 CONNECT 授权**，
  这半步由演练脚本执行（漏掉 = 数据恢复了但服务再也连不上）。
