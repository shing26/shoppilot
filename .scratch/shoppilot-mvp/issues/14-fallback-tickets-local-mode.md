# 14 — 降级原因枚举、工单落点与 local 模式验证

**What to build:** 现场把物流接口打成全超时，用户收到模型组织的人话说明加一个可查证的工单号；把模型端点指向不存在的地址，`local` 降级链路真的能接管。落实 ADR 0009、0012、0015。

**Blocked by:** 12 — 写操作幂等与状态前置校验；13 — 双层限流

**Status:** ready-for-agent

**Verify:** 注入 `failRate=1.0` -> 用户收到 `TOOL_UNAVAILABLE` 工单而非 500；把模型端点指向不存在地址 -> 走 LLM 降级；`local` profile 下政策问答与业务办理各跑通一条。

- [ ] 降级原因枚举齐备且一一映射：`LLM_TIMEOUT` `LLM_CIRCUIT_OPEN` `LLM_BUDGET_EXCEEDED` `TOOL_UNAVAILABLE` `INTENT_UNRESOLVED` `RATE_LIMITED` `SLOT_UNRESOLVED`
- [ ] 每种 reason 有对应自动化用例或故障注入脚本，能稳定复现，不靠"运气不好才会触发"
- [ ] 转人工在 biz-mock 落 `tickets`（含 transcript，按租户隔离），`ticketId` 经 SSE `fallback` 事件回执
- [ ] 工单支持状态流转（`OPEN -> ASSIGNED -> RESOLVED`），网关侧只读查询端点 + 状态变更端点
- [ ] Resilience4j 熔断配置：biz-mock 侧以 `failRate=1.0` 注入可打开熔断，恢复后半开关闭合
- [ ] `local` profile 指向 Ollama（`qwen2.5:3b` + `bge-m3`），做一次端到端 smoke：政策问答与业务办理各一条，记录 3B 模型下的真实表现与失败模式
- [ ] 用例：`failRate=1.0` 时用户收到 `TOOL_UNAVAILABLE` 工单而非 500

## Handoff notes

（收尾时填写：关键决策 + 你需要能当场回答的三个追问）
