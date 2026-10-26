# round41 开轮依据：路线 A · Agent Run 可见化

Date: 2026-10-26　Status: **Accepted**（2026-10-26 所有者选择「先走路线 A，低成本试验」）
Owner basis: 所有者判断当前项目更像“Java 项目嵌入 Agent 层”，要求先做低成本试验：
不推翻确定性运行时，把已有 Agent 行为显性化，验证产品观感是否能被拉升。
Tickets: 实现票 [160](../../.scratch/shoppilot-mvp/issues/160-agent-run-visible-trace.md)（票面即本轮 spec）。

## Context

H1-H4 与两个产品入口完成后，系统能力已经存在，但买家只看到聊天与侧栏：
状态、工具调用、观察结果、降级原因都留在 trace 与日志里。评审者因此看不到“Agent 在工作”。

## Decision

1. 本轮只做**已有 SSE 事件的可见化**，不改状态机、不改工具、不改判据：
   - 买家端读取既有 `status` / `tool_executing` / `tool_result` / `fallback` 事件；
   - 每条回答下渲染可折叠的 **Agent 运行轨迹**；
   - 连续重复状态去重；工具结果状态翻成中文；内部 state detail 不透传。
2. 轨迹是**只读观测**，不是干预面：不能改计划、不能重放工具、不能跳过人工。
3. 不新增后端接口，不新增运行时依赖，不改 SSE 契约（沿用既有事件名与字段）。
4. 这是路线 A 的第一刀；复合任务与真实自主规划留给后续单独评估。

## Non-goals

- 不把主链路改成 plan-act-observe-replan 循环（路线 B）。
- 不新增工具、不改 HITL、不改 gold 判据。
- 不向买家暴露内部路由/策略枚举原文。

## Consequences

- `frontend-buyer/src/api.ts` 的 SSE 解析增加面客轨迹回调；`App.vue` 增加轨迹收集与渲染。
- 前端产物重建入库；CI 逐字节校验继续生效。
- 浏览器验证需覆盖：轨迹出现、步骤可读、去重、工具状态中文化、无页面异常。
