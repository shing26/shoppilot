# 转人工落点为持久化工单，降级原因可枚举

Context: 转人工是大促服务可用性痛点的唯一兜底出口，若只推 SSE 事件与日志则无可查证落点，等于假功能。决定：`biz-mock` 侧建 `tickets` 表（`id, tenantId, customerId, reason, transcript, status, createdAt`），转人工即落单，`ticketId` 经 SSE 回执给前端；调试台右侧事件时间线下方加工单抽屉展示队列与状态流转，不做独立坐席页面。

降级触发条件与 `reason` 一一映射，使降级可枚举、可测，而不是一个 catch：`LLM_TIMEOUT` / `LLM_CIRCUIT_OPEN`（模型侧不可用）、`TOOL_UNAVAILABLE`（biz-mock 熔断打开，现场以 `failRate=1.0` 注入触发）、`INTENT_UNRESOLVED`（UNKNOWN 且模型无 tool_call 且检索 score 低于阈值）、`RATE_LIMITED`（超租户或买家配额）、`SLOT_UNRESOLVED`（追问一次仍缺槽位）。

Consequences:
- 每种 reason 都需要对应的自动化用例或故障注入脚本，否则"降级能力"无法在面试中举证。
- 工单含 transcript，需按知识库纪元与租户隔离规则存储，跨租户不可见。
