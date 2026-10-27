# 169 买家端 P0：Agent Run 可读化、聊天区撑满、侧栏 Tab

**Status:** implemented（2026-10-27）

**Claimed by:** zcode session 2026-10-27

> 依据：2026-10-27 前端展示面评估；只改买家端展示层，不改 API、Agent 语义、判据。

## 问题

1. 1440×900 下聊天区停在 62vh，下方大块空白，输入框离对话很远；
2. Agent Run 是 10 行同字号日志：无阶段、无状态、无耗时、重复“调用业务系统”，真正的计划/工具结果被埋住；
3. 右侧“我的订单 + 我的工单”直铺，工单重复、超时告警多、900px 只能看 1–2 张卡；
4. 视觉缺少统一主 CTA 与状态层级。

## 已实现

### A. Agent Run 任务执行卡

- 折叠标题：步数 + 耗时（例：Agent 执行记录 11 步 · 3.1s）；
- 计划单独成 checklist：核对订单 / 查询实时物流 / 汇总结果；
- 三段式阶段：理解需求 / 调用系统 / 生成回复；
- 工具结果按状态着色：成功绿、注意琥珀、失败红；
- 运行中展开，完成后默认折叠；
- 原始步骤收进“查看原始步骤”。

### B. 聊天区撑满视口

- buyer-layout 最小高度按视口计算；
- buyer-main 固定视口高度，消息区内部滚动；
- 输入框 sticky 底部；
- 小屏恢复自然高度，消息区最大 62vh。

### C. 右侧栏 Tab

- “订单 / 工单”两个 Tab，带数量（超过 99 显示 99+）；
- 默认各显示 2 条；
- “查看全部订单 / 查看全部工单”展开，再点收起；
- 保留刷新、游客提示、点击订单追问、点击工单看详情。

### D. 视觉收口

- 侧栏 Tab 用分段控件，不再两张卡直铺；
- 工单/订单摘要保留必要字段，去掉重复边框噪音；
- 折叠卡、阶段标题、状态点建立清晰层级。

## 验证

- frontend-buyer typecheck：通过；
- frontend-buyer build：通过，静态产物已同步到 gateway static/buyer；
- Playwright 1440×900：欢迎页、Agent 执行卡展开、工单 Tab；
- Playwright 390×936：无横向溢出，订单/工单在聊天下方；
- API/Agent/JVM 零改动，701/702 基线不受影响。

截图：

- docs/p0-buyer-welcome.png
- docs/p0-buyer-agent-run.png
- docs/p0-buyer-tickets-tab.png
- docs/p0-buyer-mobile.png

## 验收结论

- 买家能一眼看出“理解 → 调用 → 回答”，不再读 12px 日志；
- 首屏不再留半屏灰白，输入框稳定贴底；
- 右侧栏不再被 232 条工单淹没，默认 2 条 + 展开；
- 订单、工单、引用、重试、停止能力均保留。

## Handoff notes

**关键决策**

1. 不引入图表/动画库，只用 CSS 与原生 details，避免为 P0 增加依赖；
2. 不改 SSE 契约，只在前端给 AgentRunStep 加可选 at 字段做耗时汇总；
3. 侧栏保留原数据能力，仅改展示密度，不做工单过滤（避免改变买家可见范围）。

**验证落点**

- 构建：frontend-buyer npm run typecheck / npm run build；
- 截图：docs/p0-buyer-*.png；
- 静态产物：shoppilot-gateway/src/main/resources/static/buyer/assets。

**三个现场追问**

1. 为什么 Agent Run 还保留原始步骤？演示/排查需要，但默认折叠，不抢主线。
2. 为什么侧栏不直接只显示未结单？会改变买家可见范围，P0 只做密度不做过滤。
3. 为什么大屏欢迎页仍有空白？聊天产品需要为对话预留高度；下一步可用推荐问题/服务卡填充，不属 P0。
