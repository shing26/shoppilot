# 32 — CI 子集门禁：干净 runner 构建 + 221 条 JVM 测试

**What to build:** 给仓库加一个最小 GitHub Actions workflow，在干净 Ubuntu runner 上装 Temurin JDK 21、用 Maven Wrapper 跑 `verify`，不接任何本机服务或密钥。CI 失败时留 Surefire 报告；成功后 README 说明这条门禁的范围与全量 17 步的边界。

**Blocked by:** None。ADR 0030 第 1 条已把本票固定为下一轮第一票。

**Status:** implemented（Ubuntu Actions run 成功；审查后代码 commit `3de06d7`，run `35063263167`，46s；3+12+206=221）

- [x] `.github/workflows/ci-subset.yml`：`push(main)`、`pull_request`、`workflow_dispatch` 三类触发
- [x] Ubuntu + Temurin 21 + Maven cache；使用 `bash ./mvnw -B -ntp verify`
- [x] 不配置 secrets、services、模型额度、Ollama、ES、Qdrant、Docker
- [x] job `timeout-minutes: 20`，同 ref 新 run 取消旧 run
- [x] 失败上传 `**/target/surefire-reports/**`，无文件时不报二次错误
- [x] 本地等价复跑 `mvnw.cmd -B -ntp verify` 全绿，模块小计 `3 + 12 + 206 = 221`
- [x] 推送后 GitHub Actions run 真正成功：`https://github.com/shing26/shoppilot/actions/runs/35063263167`（46s，Ubuntu/Temurin 21 跑出 `3 + 12 + 206 = 221`；首个竞态修复 run 为 `35062472053`）
- [x] README 登记 CI 子集、触发条件与「不替代 17 步全量落点」边界
- [x] 审计项数保持 95；不改判据、阈值、gold、既有业务测试

**Verify:** 本地 `mvnw.cmd -B -ntp verify` -> `gh workflow run` 或 push 触发 -> `gh run watch` 到 success -> 下载失败产物路径规则抽检（成功 run 无需产物）。

## Handoff notes

**关键决策**

- CI 只跑 `ubuntu-latest` + Temurin 21 + `bash ./mvnw -B -ntp verify`：`mvnw` 入库 mode 是 `100644`，显式经 bash 执行不依赖 checkout 保留可执行位；不接 secret、service container、Ollama、ES 或 Qdrant。
- 触发面固定为 `push(main)`、`pull_request`、`workflow_dispatch`，同 ref 的新 run 取消旧 run，job 上限 20 分钟；失败只上传 `**/target/surefire-reports/**`，成功不产包。这条门禁不冒充 17 步全量验收。
- 首次 Ubuntu 复跑暴露 `LogbackRotationTest` 的 Linux 清理竞态：`Files.walk` 读属性时，logback 压缩线程会把 `.tmp` 移走并触发 `NoSuchFileException`。修复改用 `walkFileTree`，只把并发删除后的不存在视为幂等成功，真实句柄/权限失败仍退避重试。
- 首个竞态修复 commit `15ea402` 的真实 run 为 `https://github.com/shing26/shoppilot/actions/runs/35062472053`，1m4s；审查收口后的代码 commit `3de06d7` 的真实 run 为 `https://github.com/shing26/shoppilot/actions/runs/35063263167`，46s。两轮 Surefire 均为 `3 + 12 + 206 = 221`。

**你需要能当场回答的三个追问**

1. "为什么 CI 不直接跑 17 步全量？" —— 全量依赖本机 `logs/`、活体中间件、模型额度、浏览器与一次性读数；当前最便宜且无隐式前置的门是干净 runner 上的构建和 221 条 JVM 测试。
2. "为什么先用 Ubuntu 而不是作者同款 Windows？" —— Ubuntu 更快、更便宜，而且这次确实先抓出了一个只在 Linux 并发删除时序下露头的测试清理竞态；跨 OS 需求等真实失败或下一票再定。
3. "为什么 `NoSuchFileException` 可以算清理成功，不算吞异常？" —— 它只表示压缩线程已把临时文件删/移走，目标状态就是“文件不存在”；权限错误、句柄占用等仍标记 `retryNeeded`，在 5 秒预算内重试，清不干净仍由 JUnit 报红。
