# 32 — CI 子集门禁：干净 runner 构建 + 221 条 JVM 测试

**What to build:** 给仓库加一个最小 GitHub Actions workflow，在干净 Ubuntu runner 上装 Temurin JDK 21、用 Maven Wrapper 跑 `verify`，不接任何本机服务或密钥。CI 失败时留 Surefire 报告；成功后 README 说明这条门禁的范围与全量 17 步的边界。

**Blocked by:** None。ADR 0030 第 1 条已把本票固定为下一轮第一票。

**Status:** implemented（Ubuntu Actions run 成功；commit `15ea402`，run `35062472053`，1m4s；3+12+206=221）

- [x] `.github/workflows/ci-subset.yml`：`push(main)`、`pull_request`、`workflow_dispatch` 三类触发
- [x] Ubuntu + Temurin 21 + Maven cache；使用 `bash ./mvnw -B -ntp verify`
- [x] 不配置 secrets、services、模型额度、Ollama、ES、Qdrant、Docker
- [x] job `timeout-minutes: 20`，同 ref 新 run 取消旧 run
- [x] 失败上传 `**/target/surefire-reports/**`，无文件时不报二次错误
- [x] 本地等价复跑 `mvnw.cmd -B -ntp verify` 全绿，模块小计 `3 + 12 + 206 = 221`
- [x] 推送后 GitHub Actions run 真正成功：`https://github.com/shing26/shoppilot/actions/runs/35062472053`（1m4s，Ubuntu/Temurin 21 跑出 `3 + 12 + 206 = 221`）
- [x] README 登记 CI 子集、触发条件与「不替代 17 步全量落点」边界
- [x] 审计项数保持 95；不改判据、阈值、gold、既有业务测试

**Verify:** 本地 `mvnw.cmd -B -ntp verify` -> `gh workflow run` 或 push 触发 -> `gh run watch` 到 success -> 下载失败产物路径规则抽检（成功 run 无需产物）。
