# 05 — 最细竖切：一句话进，政策答案出

**What to build:** 带 token 的买家问"生鲜坏了怎么赔"，网关判定为政策咨询、检索规则块、调 DashScope、经 SSE 打字机吐出带引用的答案；同步端点返回同一结果。这是第一条贯穿全链路的 tracer bullet。

**Blocked by:** 02 — mock JWT 发签与身份注入；04 — 政策知识库离线入库脚本

**Status:** done

**Verify:** 在页面问"生鲜坏了怎么赔" -> 逐字打字机输出、`done` 事件含非空 citations、同步端点返回同一结果。

- [x] 状态机骨架按 ADR 0008 的十个状态显式枚举，本切片先允许 `RETRIEVE` 只走 Qdrant dense，ES 那路在 ticket 08 接入
- [x] T0 规则层：实体正则（订单号/运单号/手机号）+ 关键词表 + 所有格动作词表，产出 `cacheable` / `dynamic`，判定不确定即 `dynamic`（fail-closed）。本切片只做 T0，T1 质心与 T2 模型在 ticket 07 落地
- [x] 每次状态转移落 trace（state、时间戳、输入输出摘要），SSE 事件由转移驱动，不手写推送逻辑
- [x] SSE 事件契约按 `PLAN.md` 落地：`meta` `status` `token` `done`，`done` 含 `citations` 与 token 用量
- [x] 同步端点 `POST /api/v1/support/chat` 与流式端点 `/chat/stream` 共用同一编排核心，仅输出适配不同
- [x] 政策答案模板禁止断言式个性化结论，涉及"你是否适用"时输出引导语（ADR 0003）
- [x] `dev` profile 接 DashScope `qwen-plus`，OpenAI 兼容协议，客户端不引厂商 SDK；token 日预算熔断默认 20 万，超限走 `LLM_BUDGET_EXCEEDED`
- [x] 顺手塞一个 60 行以内的原生 JS `index.html`：`fetch()` + `ReadableStream` 收 POST SSE，把文本 `innerText` 追加到页面。它只负责证明打字机通信畅通，不做布局、不做时间线、不做工单（完整控制台在 ticket 15）
- [x] 集成测试：一条政策咨询请求返回非空答案且 citations 非空

## Handoff notes

**关键决策**

1. **十个状态是显式枚举，不是字符串。** `AgentState` = INTAKE / TRIAGE / CACHE_READ / RETRIEVE / PLAN / TOOL_EXEC / SLOT_ASK / REPLY / CACHE_WRITE / FALLBACK。每次转移经 `step()` 落 trace 并驱动 `status` 事件，SSE 推送由转移产生而不是在业务代码里手写 `emitter.send`——否则事件与状态会漂移。
2. **同步与流式共用同一个编排核心。** `AgentStateMachine.run(query, token, EventSink)` 只认 `EventSink` 接口：`SseEventSink` 逐帧推，`EventSink.NOOP` 丢弃。两条端点行为一致是结构保证的，不是两份代码各自维护。
3. **SSE 事件契约固定为 10 个事件**（见 PLAN.md）：`meta` `status` `token` `done` `tool_executing` `tool_result` `slot_ask` `fallback` `duplicate_submit` `rate_limited`。未预期异常不新增事件类型，而是复用 `status` 并置 `state=ABORTED`——客户端判定失败的唯一依据是"没收到 `done`"。
4. **T0 规则层先行，判定不确定即 `dynamic`（fail-closed）。** 本切片只做 T0：实体正则（订单号 `\\d{5,8}`、运单号、手机号）+ 关键词表 + 所有格动作词表。宁可少缓存，不可错缓存。
5. **政策答案模板禁止断言式个性化结论。** 涉及"你是否适用"时输出引导语（ADR 0003），因为检索到的规则块不等于用户订单的真实状态。
6. **模型依赖三模式（ADR 0001/0012）**：`dev` 走 DashScope OpenAI 兼容端点（不引厂商 SDK）、`local` 走 Ollama `qwen2.5:3b`、`perf` 走 `MockLlmClient`（`perf-first-token-latency: 300ms` / `perf-total-latency: 500ms`）。token 日预算默认 200000，超限走 `LLM_BUDGET_EXCEEDED`。
   预算值可经 `SHOPPILOT_LLM_DAILY_TOKEN_BUDGET` 覆盖（`application.yml` 里这是最后一个写死的数值，而 180 条完整评测一轮就要约 19 万，撞线时如果没有配置口子，唯一的出路就是改仓库里的文件——那是把运维动作伪装成代码改动，详见 ticket 16 第 7 条）。
7. **首版调试页只有 60 行原生 JS**（`fetch()` + `ReadableStream` 收 POST SSE，`innerText` 追加），用来当场暴露浏览器编码与流式切包问题；ticket 15 的完整控制台替换了它，同一份 `static/index.html`。

**你需要能当场回答的三个追问**

- *Q：为什么用 SSE 不用 WebSocket？* A：单向服务端推流 + 断线重连是客服场景的全部需求，SSE 走 HTTP/1.1 与现有网关鉴权、限流、`SseEmitter` 天然复用，不需要协议升级与第二套连接管理。
- *Q：`done` 事件里的 citations 从哪来？* A：检索层返回的规则块 `ruleId` 列表，随 `CacheEntry.sourceRuleIds` 一起缓存。命中缓存的回答仍带原引用，所以引用不是"只有实时链路才有"的装饰。
- *Q：这条竖切和最终架构差多少？* A：只差 ES 那一路检索（ticket 08）、L2 语义缓存（ticket 09）、工具闭环（ticket 10/11）。状态机骨架、事件契约、身份注入、三模式模型依赖从第一天起就是最终形态，没有临时脚手架。

**验证记录**

`scripts/stream.ps1` 与 `scripts/chat.ps1` 对运行中的网关验证：逐字打字机输出、`done` 含非空 citations、同步端点返回同一结果。ticket 15 的 Playwright 验收（15 项）再次覆盖同一条页面路径。

**已知缺口**：网关侧没有 JVM 内的 `@SpringBootTest` 端到端用例（网关侧 6 个测试类共 39 项均为单测或 ArchUnit，`@SpringBootTest` 只有 biz-mock 侧那 2 个类共 8 项）。端到端验证依赖 `scripts/verify-*.ps1` 对活体服务跑，好处是真跨进程，代价是 `mvn test` 不覆盖它。README 的已知限制里写明。
