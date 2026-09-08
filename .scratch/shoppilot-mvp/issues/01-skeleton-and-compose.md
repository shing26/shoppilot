# 01 — 三模块骨架与中间件容器栈

**What to build:** 一条命令拉起 Redis / Qdrant / Elasticsearch，两个服务各自健康检查通过，`mvn verify` 全绿，为后续所有切片提供可运行的空壳。术语遵循 `CONTEXT.md`，边界遵循 ADR 0002、0004、0005。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

**Verify:** 新 shell 执行 `docker compose up -d` 后跑 `mvnw verify` -> 三中间件健康、两服务 `/actuator/health` 为 UP、构建全绿。

- [ ] 父 pom 下三个模块：`shoppilot-gateway`(:8082)、`shoppilot-biz-mock`(:8091)、`shoppilot-tool-api`（纯契约，无 Spring 依赖）
- [ ] 编译与运行锁定 JDK 21（`E:\java\jdk21`），不依赖机器上的 `JAVA_HOME`；Maven wrapper 进仓库
- [ ] `docker-compose.yml` 拉起 Redis 7、Qdrant、ES 7.17，各自内存上限显式声明，宿主端口避开已被占用的 8080
- [ ] 两服务均开启虚拟线程配置项且可通过 profile 关闭（为 ticket 17 的对比实验预留开关，不要到时候再改代码）
- [ ] `/actuator/health` 两服务可用；Micrometer 指标端点暴露
- [ ] `.env.example` 列出 `SHOPPILOT_LLM_API_KEY`、`SHOPPILOT_JWT_SECRET`、`SHOPPILOT_INTERNAL_TOKEN`；`.gitignore` 覆盖 `.env`、`target/`、H2 数据文件
- [ ] 仓库初始化 git，首个提交只含计划与骨架

## Handoff notes

（收尾时填写：关键决策 + 你需要能当场回答的三个追问）
