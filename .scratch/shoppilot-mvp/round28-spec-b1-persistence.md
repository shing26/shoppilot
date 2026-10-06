# round28 spec —— B1「数据活过一次重启」

> 状态：执行中（2026-10-06 开轮）。
> 依据：[program-a-to-b-upgrade.md](program-a-to-b-upgrade.md) B1 入口清单（⛔ 阻塞 B2-B5）；
> 所有者 2026-10-06 指示「继续推进」认定触发到达；[ADR 0061](../../docs/adr/0061-b1-persistence-postgres-profile.md)。
> 性质：**所有者政策覆盖**（B 段 program），不是 ADR 0031 事实性修正。

## 0. 本轮一句话

把「订单/退款/工单/账号重启即清」这个 A 形态最大的运行缺口补掉，**但不以任何方式扰动 A 身份下
仍可复现的演示与验收路径**。

## 1. 范围

- **做**：
  1. biz-mock 与 ticket 各新增 `postgres` 持久档（datasource / Flyway locations / schema 对齐）；
  2. Postgres 方言迁移集（基线按 V1 文件头记载的 Hibernate 导出法重导，增量手写、逐版本与 H2 集对齐；
     放**平级目录** `db/migration-postgresql/`——Flyway 对 `classpath:db/migration` 递归扫描，子目录会同版本撞车）；
  3. seed 拆两段：初始化（租户）/ 演示（买家+订单+固定单），各自幂等，持久档默认不播演示段；
  4. compose：postgres 服务（一个实例两库）+ Redis 挂卷 + AOF + `volatile-lru` + full 档接线；
  5. 活体验证：真 Postgres 上「迁移可应用 → 建数据 → 重启 → 数据仍在」，读数登记 EVIDENCE。
- **不做**（登记非欠账）：Postgres 回滚演练与按用户隔离（B2）；`verify-outbound` 等活体门禁在持久档重跑
  （等清场日）；IM/渠道任何改动（票 96-99 原样）。

## 2. 硬约束

- 默认档（H2 内存）行为**逐字不变**：28 步矩阵、verify-\*.ps1、180 条 gold 的运行前提不动。
- `ddl-auto: validate` 两档都保持；`flyway.clean-disabled` 默认 true 不变。
- 判据面（gold / 阈值 / `judge()` / 降级枚举口径 / verify-\*.ps1 断言）零改动。
- 凭据不进仓库：`SHOPPILOT_DB_PASSWORD` 走 `.env`，compose `:?` 必需语法。
- CI 九步不加不减：CI 不跑 Postgres（round15 纪律）；新增守卫是 0 token 纯 JVM。
- 既有迁移文件（H2 侧）一个字节不动——V3/V4 的 checksum 教训。

## 3. 验证形态

| 层 | 内容 | 证据 |
| --- | --- | --- |
| CI / 本机 JVM | `mvnw verify` 全绿；迁移集对齐守卫 ×2；seed 拆段用例 | surefire |
| 活体（本机） | 真 Postgres 容器：两服务迁移应用 + validate 过 + 建号/建单/规则 → 进程重启 → 三样都在；Redis AOF：XADD → 容器重启 → XRANGE 仍在 | 本机落点，登记 EVIDENCE |

## 4. 票

| # | 票 | 依赖 |
| --- | --- | --- |
| 100 | 持久档与 Postgres 迁移集（biz-mock + ticket） | — |
| 101 | seed 拆两段 | 100 |
| 102 | compose 持久化 + 活体重启验证 | 100、101 |
| 103 | 收口（EVIDENCE / CODE_MAP / CONTEXT / README / tracker） | 100-102 |

## 5. 登记节

- 活体验证只覆盖 biz-mock 与 ticket 两个有库服务；网关无库（Redis 会话在 AOF 后也可活，属顺带收益不单独验）。
- 唯一约束并发生效、`@TenantId` 真库复核两件事是 B1 之后的**持续观察项**，不在本轮单测面内展开。
- 本轮不改 `registered-debt.md` 的 R/U 条目（U3 跨进程原子性由 B1 显形的验证随下次全栈活体做）。
