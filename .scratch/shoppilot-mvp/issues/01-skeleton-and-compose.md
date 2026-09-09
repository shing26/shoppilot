# 01 — 三模块骨架与中间件容器栈

**What to build:** 一条命令拉起 Redis / Qdrant / Elasticsearch，两个服务各自健康检查通过，`mvn verify` 全绿，为后续所有切片提供可运行的空壳。术语遵循 `CONTEXT.md`，边界遵循 ADR 0002、0004、0005。

**Blocked by:** None — can start immediately

**Status:** done

**Verify:** 新 shell 执行 `docker compose up -d` 后跑 `mvnw verify` -> 三中间件健康、两服务 `/actuator/health` 为 UP、构建全绿。

- [x] 父 pom 下三个模块：`shoppilot-gateway`(:8082)、`shoppilot-biz-mock`(:8091)、`shoppilot-tool-api`（纯契约，无 Spring 依赖）
- [x] 编译与运行锁定 JDK 21（`E:\java\jdk21`），不依赖机器上的 `JAVA_HOME`；Maven wrapper 进仓库
- [x] `docker-compose.yml` 拉起 Redis 7、Qdrant、ES 7.17，各自内存上限显式声明，宿主端口避开已被占用的 8080
- [x] 两服务均开启虚拟线程配置项且可通过 profile 关闭（为 ticket 17 的对比实验预留开关，不要到时候再改代码）
- [x] `/actuator/health` 两服务可用；Micrometer 指标端点暴露
- [x] `.env.example` 列出 `SHOPPILOT_LLM_API_KEY`、`SHOPPILOT_JWT_SECRET`、`SHOPPILOT_INTERNAL_TOKEN`；`.gitignore` 覆盖 `.env`、`target/`、H2 数据文件
- [x] 仓库初始化 git，首个提交只含计划与骨架

## Handoff notes

**关键决策**

1. **三模块而不是单模块。** `shoppilot-gateway` / `shoppilot-biz-mock` / `shoppilot-tool-api`，其中 `tool-api` 是纯契约 jar，只依赖 Jackson 注解，不引 Spring。这样"给模型的 Function Schema"和"网关的 HTTP 调用签名"由同一份 DTO 生成（ticket 10），跨进程边界在编译期就被钉住，而不是靠两边手工对齐字段名。
2. **宿主端口全部偏移：网关 8082、biz-mock 8091、Redis 16379、Qdrant 16333、ES 19200，且只绑 `127.0.0.1`。** 这台机器上 Docker Desktop 的 wslrelay 已经占住 6379/6333/9200，另有 `opspilot-*`、`nexus-*` 容器占着 8080/8081。偏移不是洁癖，是"绝不与别人抢端口"的硬约束——所有中间件都只监听回环，不对外暴露。
3. **中间件内存上限显式写死**：Redis `maxmemory 128mb` + `allkeys-lru`（容器 192m）、Qdrant 512m、ES `-Xmx512m`（容器 1024m）。16 G 开发机同时跑别的项目，不设上限的 ES 会在压测时把整机拖进交换区，那组延迟数字就没有意义了。
4. **虚拟线程开关从第一天就在配置里**（`spring.threads.virtual.enabled`），并预留 `no-virtual` profile 关掉它。ticket 18 的对比实验要求"不改代码就能切换"，临时加开关的实验不可信。
5. **Maven wrapper 用 `only-script` 分发**（`mvnw` / `mvnw.cmd` / `.mvn/wrapper/maven-wrapper.properties`，锁 3.9.14），干净机器上 `./mvnw verify` 会自己取 Maven。
6. **`.env.example` 只列变量不列值**，`.gitignore` 覆盖 `.env`、`target/`、H2 数据文件、`.venv-loadtest/`、`loadtest/results/locust-*`（保留 `ladder-*.csv` 与 `env-*.json` 两份可复核产物）。
7. **`up.ps1` 等 biz-mock readiness 的上限是 300 s，不是 180 s。** 空机上 5 万单 seed 实测 8.3 s，但门禁全量跑时 seed 与知识库入库（90 块 × 向量化 + ES/Qdrant 写入）并行抢同一台 16 G 机器，实测把 `stack` 步顶到 249 s 红过一次。300 s 是给并行阶段留了约 20 倍实测余量，不是把超时调成"永远够"——真卡住时它照样会在 300 s 处红，并且红在"等 readiness"这一行，不会伪装成服务起不来。

**你需要能当场回答的三个追问**

- *Q：为什么 ES 用 7.17 而不是 8.x？* A：8.x 强制 HTTPS + 客户端版本协商，本机内存预算下多一层安全握手不值当；7.17 的 `_search` + BM25 就是这个项目要用的全部能力，闭源特性一个没用。
- *Q：Redis 设了 `allkeys-lru`，缓存被驱逐了怎么办？* A：语义缓存的正确性不依赖 Redis 驻留——L1 被驱逐等价于一次 miss，走穿透路径重新生成并写回。真正不能丢的是幂等记录，那部分有 biz-mock 的 DB 唯一约束兜底（ticket 12）。
- *Q：`./mvnw` 在你机器上跑得起来吗？* A：能，但要注意本机 `mvn` 的全局 `conf/settings.xml` 把 `localRepository` 指到了 `E:\maven_repository`，而 wrapper 用的是默认 `~/.m2/repository`。离线复现时加 `-Dmaven.repo.local=E:\maven_repository` 即可对齐；干净机器联网首跑不需要这个。

**验证记录（2026-09-05）**

`docker compose up -d` 三容器起来；两服务 `/actuator/health` 均 UP；`mvn -o verify` 全绿。Qdrant 容器 healthcheck 长期报 `unhealthy`（`/readyz` 在其内部 wget 下超时），实测读写正常，已记入 README 已知限制。
