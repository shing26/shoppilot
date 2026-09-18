# round16 spec：网关主链路 JVM 集成缝

**Status:** implemented（2026-09-18）。来源是外部补齐清单的 S2：网关侧没有 JVM 内主链路用例，CI 只覆盖构建与单元/架构测试。用户明确要求继续后，本工作按维护性质量轮处理，不新增业务能力、不改验收判据、阈值或 gold。

## Problem Statement

v1.0 已冻结，现有 `.github/workflows/ci-subset.yml` 能在干净 runner 上跑构建和 221 条 JVM 测试，但主链路回归仍主要依赖本机 `scripts/verify-*.ps1`：

- `GatewayMainPathJvmTest` 之前不存在，网关的缓存命中、工具循环和 fallback 只能通过活体服务或拆分得很细的单元测试间接证明。
- 全量 17 步验收依赖 Ollama、ES、Qdrant、浏览器和本机 `logs/`，不适合直接塞进每次提交。
- 如果后续继续修改代码，最危险的回归面是 `ChatController -> AgentStateMachine -> ToolDispatcher/FallbackService` 这条组合链，而不是单个类的内部细节。

## Solution

新增 `shoppilot-gateway/src/test/java/com/shoppilot/gateway/web/GatewayMainPathJvmTest.java`，用三条 JVM 级集成 smoke 覆盖：

1. 缓存命中：Controller 返回 L1 正文与引用，模型零调用。
2. 工具循环：真实 `ToolDispatcher` 处理订单查询，工具结果回填到第二轮，最终答复由模型总结。
3. 显式转人工：fallback 原因与工单号进入 HTTP 响应，模型不参与。

测试使用真实 `ChatController`、`AgentStateMachine` 和 `ToolDispatcher`，仅把跨进程 HTTP、Redis、向量检索和模型边界替换为确定性替身。三条用例随普通 `mvnw verify` 自动执行，不要求 Docker 或外部服务。

## Implementation Decisions

- 不启动完整 `@SpringBootTest` 应用：完整网关上下文会拉入 Redis、Redisson、Qdrant、ES、Ollama 等外部依赖；为了三条 smoke 把这些依赖搬进 CI，会把“主链路回归”变成新的活体门禁。
- 使用 standalone `MockMvc`，因为 HTTP 绑定、JSON 序列化、Controller 准入和真实状态机都在同一线程内可确定性复现；SSE 与真实跨进程验证仍由 17 步活体矩阵承担。
- 不重命名 `ci-subset.yml`。新增的是 JVM 主链路集成缝，不是全量验收；`ci-subset` 仍然准确描述“干净 runner 上的构建 + JVM 测试”边界。
- 不改已有 221 条测试的判据或实现，只增加三条对新组合行为的覆盖。

## Testing Decisions

- 聚焦复跑：`mvnw.cmd -B -ntp -pl shoppilot-gateway -am -Dtest=GatewayMainPathJvmTest -Dsurefire.failIfNoSpecifiedTests=false test`，3/3 绿。
- 全量复跑：`mvnw.cmd -B -ntp verify`，三模块 `3 + 12 + 209 = 224` 全绿。
- CI 不需要改配置：现有 workflow 已执行同一个 `verify` goal；下一次 push/PR 会在干净 runner 上自动带上这三条用例。

## Out of Scope

- 跨实例 singleflight、异机复现、真实 Ollama/ES/Qdrant 活体验证。
- 完整 SSE 长连接、工具调用评测、压测、浏览器验收。
- Dockerfile、部署形态、MySQL、Kubernetes 或任何新中间件。

## Further Notes

- 这次增加的是“主链路组合回归”，不是“所有外部依赖都被 JVM 测试覆盖”。真实跨进程边界仍以 `scripts/verify-*.ps1` 为准。
- `README.md` 与 `docs/EVIDENCE.md` 的当前 JVM 基线随本轮更新；round14/round15 的历史 221 读数保留为当时落点。
