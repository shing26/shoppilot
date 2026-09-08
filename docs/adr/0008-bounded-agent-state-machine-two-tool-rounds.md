# 有界 Agent 状态机，工具循环硬上限 2 轮

Context: 任务书只给了"自研轻量 Agent 状态机"这个名字，没有状态、转移与终止条件。无上限的 agent 循环等于在 while 里塞模型，最坏路径不可预算，`TTFT < 500ms` 与 `QPS >= 1200` 会当场作废。决定：显式枚举的有界状态机 `INTAKE -> TRIAGE -> CACHE_READ -> RETRIEVE -> PLAN -> TOOL_EXEC -> SLOT_ASK -> REPLY -> CACHE_WRITE -> FALLBACK`，单次请求内工具循环硬上限 2 轮，超限强制 FALLBACK。

Consequences:
- 2 轮覆盖真实链式调用（缺订单号时先 `queryOrderList` 再 `queryLogistics`）；"查订单→查物流→改地址"这类三步链会被截断，产品上应拆成两轮对话完成。
- 工具失败不在模型层重试，而是把结构化失败事实 `{status, tool}` 回填给模型组织人话，降级表现为模型说明而非 500 或静默失败。
- 每次状态转移落 trace（state、时间戳、输入输出摘要），SSE 事件由状态转移直接驱动，不手写推送逻辑。
- 面试口径：不是"写了个 agent 框架"，而是"把 agent 循环约束成有界状态机，因为工具轮次与对话轮次必须可预算，否则 SLO 无法承诺"。
