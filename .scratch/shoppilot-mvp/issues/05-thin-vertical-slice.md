# 05 — 最细竖切：一句话进，政策答案出

**What to build:** 带 token 的买家问"生鲜坏了怎么赔"，网关判定为政策咨询、检索规则块、调 DashScope、经 SSE 打字机吐出带引用的答案；同步端点返回同一结果。这是第一条贯穿全链路的 tracer bullet。

**Blocked by:** 02 — mock JWT 发签与身份注入；04 — 政策知识库离线入库脚本

**Status:** ready-for-agent

**Verify:** 在页面问"生鲜坏了怎么赔" -> 逐字打字机输出、`done` 事件含非空 citations、同步端点返回同一结果。

- [ ] 状态机骨架按 ADR 0008 的十个状态显式枚举，本切片先允许 `RETRIEVE` 只走 Qdrant dense，ES 那路在 ticket 08 接入
- [ ] T0 规则层：实体正则（订单号/运单号/手机号）+ 关键词表 + 所有格动作词表，产出 `cacheable` / `dynamic`，判定不确定即 `dynamic`（fail-closed）。本切片只做 T0，T1 质心与 T2 模型在 ticket 07 落地
- [ ] 每次状态转移落 trace（state、时间戳、输入输出摘要），SSE 事件由转移驱动，不手写推送逻辑
- [ ] SSE 事件契约按 `PLAN.md` 落地：`meta` `status` `token` `done`，`done` 含 `citations` 与 token 用量
- [ ] 同步端点 `POST /api/v1/support/chat` 与流式端点 `/chat/stream` 共用同一编排核心，仅输出适配不同
- [ ] 政策答案模板禁止断言式个性化结论，涉及"你是否适用"时输出引导语（ADR 0003）
- [ ] `dev` profile 接 DashScope `qwen-plus`，OpenAI 兼容协议，客户端不引厂商 SDK；token 日预算熔断默认 20 万，超限走 `LLM_BUDGET_EXCEEDED`
- [ ] 顺手塞一个 60 行以内的原生 JS `index.html`：`fetch()` + `ReadableStream` 收 POST SSE，把文本 `innerText` 追加到页面。它只负责证明打字机通信畅通，不做布局、不做时间线、不做工单（完整控制台在 ticket 15）
- [ ] 集成测试：一条政策咨询请求返回非空答案且 citations 非空

## Handoff notes

（收尾时填写：关键决策 + 你需要能当场回答的三个追问）
