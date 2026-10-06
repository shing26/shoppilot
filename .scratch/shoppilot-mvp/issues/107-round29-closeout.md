# 107 round29 收口

**Status:** implemented（2026-10-06）

## What to build

round29 收口：B2「凭据生产态 + 按库用户隔离 + 备份恢复演练」全部落地后的登记与换代。

- `docs/EVIDENCE.md` B2 行（守卫拒启原文 / 隔离 FATAL / 演练 14 项断言明细 / 两笔记账）。
- `docs/CODE_MAP.md`：tool-api 行补 `tool/config/PostureGuard` 位置。
- `CONTEXT.md`：新增术语「姿势守卫 (Posture Guard)」。
- `README.md`：已知限制补备份/守卫覆盖面两条。
- `registered-debt.md`：R5（长期指标存储与看板）登记，带触发条件。
- tracker：round29 条目 + Round 表 + 票索引 + 读数换代。
- `program-a-to-b-upgrade.md`：B2 换代指针。

## Blocked by

[104](104-credential-posture-guard-data-side.md)、[105](105-per-database-user-isolation.md)、[106](106-backup-and-restore-drill.md)。

## 口径

- 判据面零改动；网关行为零变化（三份既有守卫测试类原样绿）。
- 读数换代以本次实测为准：JVM `10 + 64 + 345 + 45 = 464`（+10 全为本轮：biz-mock +5、ticket +5）。

## 验收

- `mvnw verify` 全绿；`git diff --check` / `git status --short` 干净（无 logs/、backups/、.env 混入）。

## Verify

```powershell
git diff --check; git status --short
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

### 交付层读数换代（2026-10-06 实测）

- JVM **`10 + 64 + 345 + 45 = 464`**（tool-api / biz-mock / gateway / ticket；+10：biz-mock +5 =
  PostureGuardConfigurationTest、ticket +5 = PostureGuardConfigurationTest 3 + PostgresIsolationScriptTest 2）。
- ADR **61 篇**（新增 0062）；票 **107** 张（新增 104-107，全收口）；CI 九步未动。

### 本轮最值得记的三条

1. **隔离把混合属主状态当场拦住**：超用户跑过迁移的库里，`flyway_schema_history` 归超用户，
   应用角色连历史表都读不了——「升级 runbook 必须含库级重建」是演练买来的知识，不是文档抄来的。
2. **构建链的隐形换包**：`-pl` 不带 `-am` 从 `~/.m2` 解析旧共享库；jar 被运行中进程锁住时 repackage
   失败但保留旧包——两个坑叠加时，跑起来的服务和盘上的 jar 可以是两个版本。
3. **恢复手册里最容易被忘的一格**：`pg_dump` 不含库级属性——owner 与 CONNECT 授权随 DROP 丢失、
   角色是集群级的；「数据恢复了但服务再也连不上」就是漏了授权重放。

### 现场追问

1. 为什么数据侧守卫不搬网关的 EnvironmentPostProcessor？——数据侧 yml 占位符自带仓库默认值，
   没有「回环填默认值」的需求（干净克隆判据只压在网关）；只搬「非回环即收紧」这一道。
2. 两个应用角色为什么共享一个口令？——隔离的目的是权限不及于另一库，不是口令唯一性；
   残余风险登记在 ADR 0062 §2，不假装做了。
3. 备份为什么不进验收矩阵？——它依赖持久档 + 容器栈姿势，而 full 档还可能是本机 H2；
   硬塞进去让本机 full 必红。触发条件在 ADR 0062 §3。
