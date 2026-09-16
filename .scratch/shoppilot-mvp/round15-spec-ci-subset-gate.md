# round15 spec：CI 子集门禁

**Status:** implementing。来源是 ADR 0030 第 1 条：round14 收口后，下一轮第一票固定为 CI 子集门禁；若异机跑通需要先偿还未落地的隐性环境债，偿债票排它前面。

## Problem Statement

round14 的 17 步验收与 221 条 JVM 测试都在作者这台 Windows 机器上跑过，仓库本身没有任何 CI。现在最便宜、最接近真实接手场景的证据缺口是：把代码推到 GitHub 后，没有人会在干净 runner 上自动构建并跑 JVM 测试。全量 17 步门禁依赖本机 `logs/`、Ollama、ES、Qdrant、浏览器和一次性活体读数，不适合整包进 CI；但「构建 + JVM 单测」这一层不依赖这些外部条件，应该先被自动化。

## Solution

新增一个最小 GitHub Actions workflow：

- 在 `ubuntu-latest` 上装 Temurin JDK 21。
- 用仓库自带 Maven Wrapper 执行 `bash ./mvnw -B -ntp verify`。
- 只在 `push` 到 `main`、`pull_request` 与人工 `workflow_dispatch` 时运行。
- 不配置任何 secret、服务容器、模型额度、Ollama、ES 或 Qdrant。
- job 超时 20 分钟；同 ref 的新 run 取消旧 run，避免排队浪费。
- 失败时上传 Surefire 报告，让红的测试和栈回溯可直接下载。

CI 的判定不是「17 步换一种跑法」，而是独立的小门：干净环境能构建，能跑完整的 221 条 JVM 测试，且没有任何本机隐式前置。异机若暴露 Windows/本机状态依赖，先按 ADR 0030 第 1 条的例外修债，不在 CI 里加兼容分支掩盖。

## User Stories

1. As 维护者, I want 每次 push 到 main 都自动构建并跑 JVM 测试, so that 我不再依赖作者记得在本机跑 `mvn test`。
2. As 贡献者, I want 每个 pull request 都收到同一套 CI 结果, so that 合入前能看到 Linux 上的独立验证。
3. As 接手者, I want CI 不要求任何 secret 或本地服务, so that 一条 fork 后的 PR 也能正常跑门禁。
4. As 排障者, I want 失败时能下载 Surefire 报告, so that 红在哪一条测试不必翻完整日志。
5. As 维护者, I want 这条门禁只覆盖构建与 JVM 测试, so that 它不会把全量活体验收那套高波动依赖塞进每次提交。
6. As 未来 CI 扩展者, I want 当前边界写进 round15 spec, so that 不会被误认为已经有了全套 17 步远端门禁。

## Implementation Decisions

- runner 先用 `ubuntu-latest`：它比 Windows runner 更快、更便宜，也更能暴露「只在作者 Windows 机器上成立」的隐性依赖。若测试本身真需要 Windows，再按实际失败补独立 job，不把 Ubuntu 结果伪造成跨平台结果。
- 使用 `actions/setup-java@v4` 的 `temurin` 21 与 Maven cache；使用 `bash ./mvnw` 兼容当前仓库里 `mvnw` 的 Git mode（`100644`），不依赖 checkout 后保留可执行位。
- 单 job 单命令，直接跑 reactor `verify`。CI 不使用 `-o`，因为干净 runner 必须先下载 Maven 依赖。
- `timeout-minutes: 20` 是防跑挂的上限，不是性能承诺。已有本机全量 Maven 读数约 53 秒，20 分钟给冷缓存和 runner 抖动留足空间。
- 失败产物只上传 `**/target/surefire-reports/**`，不做覆盖率、扫描、部署、发布或通知。
- README 只登记 CI 子集的存在、触发条件和它不覆盖全量 17 步这一边界。

## Testing Decisions

- 本地等价复跑：`mvnw.cmd -B -ntp verify`，确认同一 goal 在提交前为绿色。
- 远端真实复跑：推送 workflow 后在 GitHub Actions 查看 run，必须正常终态成功。
- 首轮 CI 预期模块小计为 `3 + 12 + 206 = 221`；若 runner 上数量不同，先查是否漏编译、跳测或环境分支，不直接改期望值。
- 不新增审计项，不改 round14 的 95 项收口审计，不改任何业务测试与判据。

## Out of Scope

- 全量 17 步 `run-acceptance.ps1`、浏览器验收、评测、压测、模型调用。
- Ollama、ES、Qdrant、Redis、Docker Compose 或任何 service container。
- 部署、发布、制品仓库、CodeQL、覆盖率、最小告警集。
- Windows runner 矩阵。首轮先证明 Linux 干净环境可跑；跨 OS 需求等真实失败或下一票再定。

## Further Notes

- 这张票是 ADR 0030 第 1 条触发的直接承接，不需要重开「CI 是否值得做」的讨论。
- 现有 round14 公共门禁仍是本机全量证据；CI 结果只增加另一台干净 runner 上的构建与 JVM 测试证据，不替代 17 步落点。
