# 33 — 网关主链路 JVM 集成测试：缓存命中、工具循环、fallback

**What to build:** 给 `shoppilot-gateway` 增加三条 JVM 主链路集成测试，覆盖缓存命中、工具循环和显式转人工 fallback；测试必须能在无 Docker、无 Ollama、无 ES、无 Qdrant 的 `mvnw verify` 中运行。

**Blocked by:** None。用户在 2026-09-18 明确授权继续后，作为 round16 维护性质量轮执行；本票不新增业务功能，不改判据、阈值、gold 或既有测试。

**Status:** implemented（2026-09-18；`GatewayMainPathJvmTest` 3/3，全量 JVM `3 + 12 + 209 = 224` 绿）。
2026-09-19 双轴复核发现工具循环那条是假绿（桩按调用次序返回，去掉回填照样通过），已补请求体断言与变异对照，见 `## Handoff notes`。

- [x] 新增 `GatewayMainPathJvmTest`：真实 `ChatController` + `AgentStateMachine` + `ToolDispatcher`
- [x] 缓存命中用例断言 L1 正文/引用返回且 `LlmGateway` 零交互
- [x] 工具循环用例断言订单工具结果进入第二轮：直接查第二轮与收尾那轮的 `LlmTypes.Request` 里带 `tool` 消息
      （含 `toolCallId` 与业务结果原文）与发起调用的 assistant 消息，而不是只看 HTTP 响应
- [x] 显式转人工用例断言 `USER_REQUESTED` 与工单号进入响应，模型零交互
- [x] 不启动完整 Spring 容器，不引入 Testcontainers、Docker、Ollama、ES 或 Qdrant
- [x] 聚焦复跑 3/3 绿：`GatewayMainPathJvmTest`
- [x] 全量复跑绿：`mvnw.cmd -B -ntp verify`，三模块 `3 + 12 + 209 = 224`
- [x] 更新 README、EVIDENCE、CODE_MAP 与面试材料的当前 JVM 基线；历史 221 读数保留
- [x] 不重命名 `ci-subset.yml`，不把 JVM smoke 冒充全量 17 步活体验收

**Verify:**

```powershell
.\mvnw.cmd -B -ntp -pl shoppilot-gateway -am -Dtest=GatewayMainPathJvmTest -Dsurefire.failIfNoSpecifiedTests=false test
.\mvnw.cmd -B -ntp verify
```

预期：聚焦复跑 `Tests run: 3` 全绿；全量三模块 `3 + 12 + 209 = 224` 全绿；CI workflow 无需新增服务或 secret。

变异对照（证明那条断言不是恒绿）：临时删掉 `AgentStateMachine` 里
`messages.add(LlmTypes.Message.tool(call.id(), dispatch.json()))` 后聚焦复跑必须变红
（2026-09-19 实测 `Tests run: 3, Failures: 1`），恢复后 3/3 绿。

## Handoff notes

**关键决策**

- 没有把完整 Spring 容器搬进测试。网关完整上下文需要 Redis/Redisson 配置、Qdrant、ES、Ollama 等外部边界；三条主链路 smoke 的目的是守住 Controller 到状态机、工具分发和 fallback 的组合行为，不是复制活体验收。
- 保留了真实 `ChatController`、`AgentStateMachine` 和 `ToolDispatcher`，只替换跨进程 HTTP、向量检索和模型。这样“缓存命中不碰模型”“工具结果回填后再总结”“转人工带工单号”三件事都能在普通 JVM 测试里判红。
- workflow 仍叫 `ci-subset`。新增覆盖后它仍是构建 + JVM 测试子集，不包含全量活体验收；名字没有变成错误承诺。
- 当前 JVM 基线从历史 `3 + 12 + 206 = 221` 变为 `3 + 12 + 209 = 224`；round14/round15 的历史落点文字不改，只有当前对外入口和证据地图更新。
- **2026-09-19 双轴复核（Standards / Spec）**：两条轴独立指出同一个 P1 —— 工具循环用例只断言了 HTTP 响应与 `bizMock.call`，
  而 `llm.complete` / `llm.stream` 的桩是按调用次序返回的，把「tool 消息回填」整段删掉测试仍是绿的。
  处置：改为用 `ArgumentCaptor` 抓请求体，断言第二轮规划请求里有 `tool` 消息（`toolCallId` 指回 `call-order-1`、
  正文含业务结果原文）与带 `toolCalls` 的 assistant 消息，收尾那轮同样要能看到工具结果；并做了一次变异对照（见上）。
  同轮复核的其余意见已按轻重处置：`EVIDENCE.md` 补了 CI run 号；`Harness` 单字段 Middle Man 已内联。
- **本轮之外落地的两份文档提交**：同一天的 `8b16a98`（ADR 0032「编排自研 + provider 缝」）与 `.gitattributes`
  （问答库是 CRLF，`git diff --check` 需要 `whitespace=cr-at-eol`）属于同一会话的登记型改动，不新增功能、
  不改判据；它们没有单独开票，原因与编号冲突见 `## Handoff notes` 的关键决策第 5 条。
- **干净 runner 落点**：`8e6437e` 的 `ci-subset` run `35328032251`（50 s，`3 + 12 + 209 = 224`），
  `8b16a98` 的 run `35350399414` 同样绿。
- 没有为上面那两份文档提交另开票：跟踪器里票 34-40 的编号已被工作区里一份**未提交**的在途草稿
  （`.scratch/shoppilot-mvp/round17-spec-architecture-completeness.md` 及其 ADR 0033-0040）占用。
  为避免编号碰撞，本轮的登记型 docs 改动随本票记录；那份草稿不是本票产出，本轮也未审、未动、未提交。

**你需要能当场回答的三个追问**

1. "为什么不是 `@SpringBootTest` 全量起网关？" —— 那会把 Redis、Qdrant、ES、Ollama 或对应的替身配置全搬进每次 JVM 门禁，成本和脆弱性都超过三条 smoke 的收益。这里测的是组合行为，真实跨进程边界仍由 `verify-*.ps1` 持有。
2. "这三条测试到底防住了什么回归？" —— 缓存命中若又偷偷经过模型、`AgentStateMachine` 不再把工具结果回填进第二轮/收尾轮的请求、或 `USER_REQUESTED` 不再落出 ticketId，对应断言会直接红，不依赖本机服务。这条能力本身也做过反证：把回填那一行删掉，测试当场变红。
3. "为什么 CI 仍叫 subset？" —— 它只证明干净 runner 能构建并跑 224 条 JVM 测试；SSE 长连接、真实 ES/Qdrant、Ollama、浏览器和压测仍在 17 步活体验收里，名字保留正是为了不把两者混为一谈。
