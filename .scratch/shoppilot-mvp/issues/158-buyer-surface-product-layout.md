# 158 买家端产品化布局：对话主区 + 服务侧栏

**Status:** implemented（2026-10-25）

**Claimed by:** zcode session 2026-10-25（ADR 0074）

> 依据：[ADR 0074](../../../docs/adr/0074-round39-buyer-surface-product-layout.md)、
> [ADR 0070](../../../docs/adr/0070-round36-product-surface-completion-reopen-basis.md)。

## What to build

1. 登录后页面改成双栏产品布局：
   - 主区：欢迎态 / 对话流 / 输入区；
   - 侧栏：本次服务工单进度、服务范围、店铺保障。
2. 欢迎态从「标题 + 三个文字按钮」升级为有客服身份、服务说明与快捷问题卡片的入口。
3. 工单进度从对话下方移到侧栏，首屏可见；空态文案保持原样。
4. 保留既有 gate、登录、SSE、重试、游客限制与工单抽屉行为。

## Blocked by

—

## 范围与验收

- 既有选择器 `.store-card` / `.gate` / `.welcome` / `.thread` / `.tickets` / `.composer` 仍存在。
- 1440px 下形成清晰双栏；390px 下不横向溢出，侧栏落到主区下方。
- 快捷问题可点击并进入对话；工单侧栏空态文案不变。
- `npm run typecheck`、`npm run build` 通过；产物入库。
- 180 条 gold 零改动；JVM verify 全绿。

## Non-goals

- 不改后端契约。
- 不做订单 / 商品浏览页。
- 不做会话持久化或富文本。

## Verify

- `cd frontend-buyer && npm run typecheck && npm run build`
- 浏览器：`/buyer/?store=T001`，1440px 与 390px 各一次。

## Handoff notes

**关键决策**

1. 买家端从单列长页升级为**对话主区 + 320px 服务侧栏**：主区放欢迎态 / 对话 / 输入，
   侧栏放我的工单、服务范围与保障说明。工单进度从“要滚动才能看到”变成首屏可见。
2. 欢迎态补上客服身份、服务说明和三项能力标签；快捷问题仍复用既有 `QUICK_QUESTIONS`，
   不改后端问题集。
3. 所有既有选择器与行为保持不变：`.store-card` / `.gate` / `.welcome` / `.thread` /
   `.tickets` / `.composer` 仍在，SSE、重试、游客限制、工单抽屉与空态文案未改。
4. 视觉只用 `frontend-shared` 令牌，不引组件库、不加运行时依赖。
5. 960px 以下回落单列，390px 无横向溢出。

**验证落点**

- `npm run typecheck`：通过。
- `npm run build`：通过；`static/buyer/` 产物已重建。
- 浏览器活体验证 **12/12**：页面加载、店铺身份卡、店名不是内部租户号、双栏布局、
  桌面两栏（818px + 320px）、欢迎态、工单侧栏、服务范围卡、快捷问题、点击进入对话、
  390px 无横向溢出、无页面异常。
- 截图：[欢迎态](../../../docs/buyer-product-welcome.png)、[对话态](../../../docs/buyer-product-layout.png)。
- JVM verify 全绿；180 条 gold 零改动。

**未达成 / 边界**

- 既有 `scripts/verify-buyer.mjs` 未在本轮重跑；选择器保持兼容，但正式门禁的活体复跑仍待清场日。
- 本轮未做订单 / 商品浏览、会话持久化与富文本，均为非目标。

**三个现场追问**

1. **为什么工单放侧栏而不是留在对话下面？** 售后进度是买家回来最想看的东西，首屏可见比“往下翻”更符合产品；
   对话仍是主区，不抢注意力。
2. **为什么不做订单浏览页？** 仓里没有买家读端点，要做先加 biz-mock 读面与跨买家隔离判据，属于新功能，不在本轮夹带。
3. **为什么只改布局不改交互？** 现有 SSE、重试、游客限制和工单抽屉已经验证过；这轮目标是产品观感与信息层次，
   不拿交互稳定性去换视觉。
