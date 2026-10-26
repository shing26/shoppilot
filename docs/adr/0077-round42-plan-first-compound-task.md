# round42 开轮依据：路线 A 第二刀 · 计划先行与复合任务

Date: 2026-10-26　Status: **Accepted**（2026-10-26 所有者「继续往这个方向走」）
Owner basis: 路线 A 第一刀已让 Agent 执行可见；所有者要求继续，验证“有计划的执行者”而不只是事后调用日志。
Tickets: 实现票 [161](../../.scratch/shoppilot-mvp/issues/161-plan-first-compound-task.md)（票面即本轮 spec）。

## Context

ADR 0076 把已有 SSE 状态与工具事件渲染成 Agent Run，但轨迹仍然是“做完之后回看”。
缺少执行前的计划，也没有一个真正的多步任务来证明 Agent 能连续推进目标。

## Decision

1. 在 SSE 增加 `plan` 事件，状态机在**执行前**推送稳定计划：
   - 普通动作意图：核对 → 执行 → 回报；
   - 物流意图：核对订单 → 查询物流 → 汇总。
2. 只做一个确定性复合任务：查询同时包含订单号、物流、详情/状态时，
   执行 `queryOrderDetail` → `queryLogistics` → 确定性汇总。
3. 复合任务不经过模型规划：计划由代码生成，执行顺序固定，失败沿用既有 fallback / 追问出口。
4. 未命中该场景的请求完全走原状态机；不改变通用链路、gold 与工具授权。
5. 买家端把 `plan` 事件渲染为轨迹第一条，后续步骤按真实执行追加。

## Non-goals

- 不做通用 plan-act-observe-replan 循环（路线 B）。
- 不让模型生成或修改计划。
- 不扩展到写动作复合任务。
- 不改 HITL、工具轮次、判据。

## Consequences

- `EventSink` / `SseEventSink` 增加 `plan` 事件；`AgentStateMachine` 增加计划生成与一个复合读分支。
- 复合分支新增指标 `shoppilot_compound_tasks_total{result=COMPLETED}`。
- 买家端轨迹显示“计划：查询订单详情 → 查询实时物流 → 汇总订单与物流结果”。
- 前端产物重建入库；浏览器验证需覆盖计划出现、两步工具执行、汇总答案。
