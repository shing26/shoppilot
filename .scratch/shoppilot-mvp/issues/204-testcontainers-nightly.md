# 票 204：真实依赖集成层（Testcontainers Redis Streams + Postgres，nightly）

Round: 48（spec：[round48-spec-quality-closure.md](../round48-spec-quality-closure.md)）
ADR: 0083　Status: implemented　Blocked by: 无（按 spec 可在 201 后并行）

## What to build

默认 `mvnw verify` 跑在 H2 内存库与 Mock 中间件上：Flyway 的 PG 方言迁移、`ddl-auto: validate`
的实体映射、Redis Streams 的真实 XADD 语义都没有被真正执行过。本票补一层**只在 nightly / 手动触发**
的集成层：

1. **Postgres 集成**：`shoppilot-ticket` 用 Testcontainers 起 `pgvector/pgvector:pg16`（与
   `docker-compose.yml` 同镜像），以 `postgres` profile 启动上下文：
   - Flyway 应用 `db/migration-postgresql` 的 V1-V3，`flyway_schema_history` 全 success；
   - `ddl-auto: validate` 通过（实体 ↔ 真 PG 模式）；
   - 租户作用域仓储在真 PG 上可写可读（不触发 H2 方言特有的行为）。
2. **Redis Streams 集成**：`shoppilot-ticket` 用 `redis:7-alpine`（与 compose 同镜像）起容器，
   默认 H2 档启动上下文，注入真实 `StringRedisTemplate`：
   - `AuditPublisher.publish` 真的写到 `AuditTopics.AUDIT`，字段集与事件契约一致；
   - `OutboundPublisher.publish` 真的写到 `AuditTopics.CHANNEL_OUTBOUND`，web/无目标不落流；
   - Redis 不可达时两者只 warn、不向业务抛（既有契约）。
3. **nightly 工作流**：新增 `.github/workflows/nightly.yml`（`schedule` + `workflow_dispatch`），
   在 GitHub runner 的 Docker 上跑 `bash ./mvnw -B -ntp verify -Pintegration`，失败时上传
   surefire 报告。CI 子集 `ci-subset.yml` 不接这一层（它必须 0 依赖、秒级）。

## 边界

- 不改生产代码、配置默认值与表结构；不加 H2→PG 的兼容层。
- 集成用例一律 `*IT.java` 命名，默认 surefire 不匹配；只有 `-Pintegration` 才由
  maven-failsafe 执行。Docker 不可用时用 `@Testcontainers(disabledWithoutDocker = true)` 跳过，
  **跳过不算通过**——nightly 的判据是"在 Docker 可用环境实际执行"。
- 不把网关的 Redisson 缓存、biz-mock 的完整 PG 模式纳入本票（登记为后续触发条件：
  下一次改缓存键结构或 biz-mock 迁移集时再补同型 IT）。
- 不新增产品能力，不动 gold / 阈值 / `judge()` / 指标分母。

## Verify

```bash
# 默认构建：集成用例应被跳过（不要求 Docker）
bash ./mvnw -B -ntp verify -Dspring-boot.repackage.skip=true

# 集成层：要求 Docker；本地 Docker 不可用时会 skip，nightly 上必须实跑
bash ./mvnw -B -ntp verify -Pintegration -Dspring-boot.repackage.skip=true
```

## 验收项

- `-Pintegration` 下 3 个 IT 类在 Docker 可用时全绿、无 Docker 时 skip 而非 fail。
- 默认 `verify` 的用例数与票 203 基线一致（780），不因本票变化。
- 提交里包含 nightly 工作流，命令与本地一致。
- `docs/EVIDENCE.md` 记录本机 Docker 状态、实际执行/跳过情况与边界（未在 GitHub runner 实跑要说清）。

## Handoff notes

**关键决策**

- 集成层只进 `shoppilot-ticket`：本票要证的三件事（PG 方言迁移、`@TenantId` 在真库上的隔离、
  两条 Stream 的真实 XADD）都落在这个模块。网关的 Redisson 缓存与 biz-mock 的完整 PG 模式
  维持登记状态，触发条件写在票的边界段。
- Testcontainers 依赖是**无条件 test scope**，不是塞进 profile：Maven 无论激活哪个 profile 都会
  编译全部测试源，放进 profile 会让默认 `verify` 因 `*IT.java` 找不到 `org.testcontainers` 而编译失败。
  profile 只控制**执行**（failsafe）。
- failsafe 版本在票内 pom 显式钉 `3.2.5`，与根 pom 的 surefire 同版本：不钉时本机 Maven 解析到
  `3.6.1`，同一份 pom 会在不同 Maven 发行版上换执行器，这类差异不该由环境决定。
- `disabledWithoutDocker = true` 让本地无 Docker 时整类 skip；**skip 不是 pass**，nightly 才是
  「实际执行」的落点。
- PG 用例用 `@ActiveProfiles("postgres")` + `@DynamicPropertySource` 覆盖 datasource，而不是
  `spring.profiles.active` 动态属性：profile 在 dynamic source 之前解析，后者不可靠。
- 流程用例刻意**不加 `@Transactional`**：`HibernateTenantResolver.validateExistingCurrentSessions=true`
  时同一 session 换租户会抛异常；每次仓储调用各自开会话，才是线上 filter 的实际形状。

**验证落点**

- 默认档（本机，2026-11-05）：`./mvnw.cmd -B -ntp verify -Dspring-boot.repackage.skip=true`
  **BUILD SUCCESS**；按 surefire XML 汇总四模块 **780 tests / 131 suites**、0 失败，与票 203 基线一致；
  三个 `*IT` 未被 surefire 拾取（类名不匹配任何默认 include）。
- 集成档（本机，Docker 不可用）：`./mvnw.cmd -B -ntp -pl shoppilot-ticket -am verify -Pintegration
  -Dspring-boot.repackage.skip=true` **BUILD SUCCESS**；surefire 50 绿，failsafe **3 个类 / 8 条全部
  skip**（`Could not find a valid Docker environment ... npipe:////./pipe/dockerDesktopLinuxEngine`）。
  **这不是通过**——只证明「无 Docker 时 skip 而非 fail」这条验收项。
- 单独 `-pl shoppilot-ticket`（不带 `-am`）会因本地仓库里的 `shoppilot-tool-api` 快照过期而出现
  `NoSuchMethodError: TicketView.<init>` 的 500；这是本机陈旧安装产物，不是本票改动，验证时一律带 `-am`
  或按 AGENTS 在根目录跑。
- nightly `.github/workflows/nightly.yml`（`schedule` 18:23 UTC / 02:23 CST + `workflow_dispatch`）
  已入库；**尚未在 GitHub runner 实跑**（本票提交时没有 run），镜像标签取自 `docker-compose.yml`
  （`pgvector/pgvector:pg16`、`redis:7-alpine`），本机也未拉取过这两个镜像。

**现场追问**

1. 第一次 nightly 若在 `PostgresSchemaIT.lobColumnsLandAsLargeObjects` 红：先看 Hibernate 版本是否换了
   `@Lob String` 的 PG 落点；ADR 0061 的实验矩阵只对当时版本成立。
2. 若 `PostgresTicketFlowIT` 在 `claim` 上红而 `findByIdAndTenantId` 绿：先断是不是 `@TenantId` 谓词
   没有拼到 JPQL update 上（本仓历史上有过 `findById` 不拼的先例），再考虑条件更新本身。
3. 若 nightly 里 IT 全 skip 而不是跑：runner 的 Docker 探测失败或 `TESTCONTAINERS_*` 被设了远端策略；
   看 `failsafe-reports` 的 dumpstream 与 Testcontainers 的 `DockerClientProviderStrategy` 日志。
