# 102 compose 持久化 + 活体重启验证

**Status:** implemented（2026-10-06）

## What to build

B1 的运行形态落地：容器档从「重启即失忆」变成「数据活过一次重启」，并留下活体读数。

- **compose**：
  - 新增 `postgres` 服务（profile full，**不进默认中间件**——默认中间件的用法不该被一张 DB 密码
    逼着配 .env）：`pgvector/pgvector:pg16`（本机已本地化镜像，round76 先例）、一个实例两库
    （`POSTGRES_DB=bizmock` + `deploy/postgres-init.sql` 建 `ticket`）、宿主端口偏移 15432、
    `mem_limit 256m` + `restart` + 健康检查；密码走 `${SHOPPILOT_DB_PASSWORD:?}` 必需语法——
    不给 compose 直接拒（ADR 0029 同族：默认口令就是一把没换过的锁）。
  - biz-mock / ticket 的 full 档 env：`SPRING_PROFILES_ACTIVE=postgres` + DB 四件 + `depends_on postgres healthy`
    ——**容器档默认走持久档**（「数据活过一次重启」是投用形态的默认值）。
  - Redis：挂卷 `redis-data:/data` + `--appendonly yes` + 淘汰策略 `allkeys-lru` → **`volatile-lru`**
    （有 TTL 的键可逐出——丢得起；无 TTL 的事件流靠 MAXLEN 截断，不参与逐出）。
- **活体验证**（本机真 Postgres 容器，读数登记 EVIDENCE）：
  1. 两服务以 postgres profile 启动，迁移真应用、validate 过、readiness UP；
  2. 建数据（注册账号 / 落一张工单 / 读规则表）；
  3. **杀进程重启**，三样数据逐项核销；
  4. Redis AOF：XADD → 容器重启 → XRANGE 仍在。

## Blocked by

[100](100-persistent-postgres-profile.md)、[101](101-seed-init-demo-split.md)。

## 口径

- 默认中间件行为不变（postgres 不在其中；redis 换 AOF 是 B1 的明说意图）。
- **容器档 full 默认连 PG** 是 B1 的内容本身，不是对 round24「默认路径逐字不变」的违背——那条约束
  守的是本机 JVM 档（`up.ps1` 不带开关的行为），本票不碰它。
- 全栈容器档的整栈起栈（≈6.9 GB）不属本票验收——按 round23 起的裁定 A 照登，活体只起「有库的两个服务 + 中间件」。

## 验收

- compose config 对 `:?` 必需语法的响亮拒绝实测；
- 活体读数五条（见 Handoff），登记 EVIDENCE.md。

## Verify

```powershell
docker compose --profile full config --quiet   # 需 .env 有 SHOPPILOT_DB_PASSWORD
.\mvnw.cmd -B -ntp verify
# 活体序列见 EVIDENCE.md「B1 持久化」节
```

## Handoff notes

（活体读数见下方「活体读数」节；命令与输出摘录登记在 docs/EVIDENCE.md。）

### 实现坑（三个，全活体实测）

1. **Flyway 10 缺 PG 模块**：`Unsupported Database: PostgreSQL 16.15` 拒启 → 引
   `flyway-database-postgresql`（票 100 已记）。
2. **`export && cmd1 & cmd2 &` 的优先级坑**：`&&` 绑得比 `&` 紧，第二个后台进程吃不到 export 的
   环境变量——ticket 第一次「UP」其实是 H2 默认档在答。**「服务 UP」不等于「连的是你想要的库」**，
   活体断言必须读日志里的 profile 行与连接串，不能只看 readiness。
3. **运行中的 JVM 锁 fat jar**（round21 已登记过的老坑复发）：repackage `Unable to rename` →
   先 `jps -l` 找 PID 杀干净再打包。

### 活体读数

见 `docs/EVIDENCE.md` B1 节（2026-10-06 落点）。

### 现场追问

1. 为什么 postgres 进 full 档而不是默认中间件？——默认中间件 = 本机 JVM + H2 的老用法，
   逼它配 .env 密码是把 B1 的成本错摊给不用持久档的人。
2. Redis 挂卷后「丢得起」的语义哪去了？——写进淘汰策略：`volatile-lru` 让有 TTL 的键（缓存/会话/
   幂等）在压力下可逐出，无 TTL 的事件流不参与——语义从注释变成了配置。
3. 容器档现在必须配 .env 才能起？——是，且失败是响亮的（`:?` 语法）。干净克隆一条命令的完整链路
   等下一次清场日复验（照登）。
