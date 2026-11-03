# 票 196：买家端——移动端点击目标、回对话、`role="log"`、游客项复核

Round: 47（spec：[round47-spec-ux-p2-fixes.md](../round47-spec-ux-p2-fixes.md)）
ADR: 0082　Status: implemented　Blocked by: 195（标签映射）

## What to build

1. **移动端点击目标 ≥40px（≤640px）**：
   - 顶栏「退出」（`TopBar.vue` 的 `.link`）与侧栏「刷新」（`SideActivity.vue` 的
     `.section-head .link`）：`min-height: 40px`，补水平内边距；
   - 订单卡「问问这单」（`OrderCard.vue` 的 `.order-ask`）：`min-height: 40px`；
   - 订单/工单 tab（`SideActivity.vue` 的 `.side-tab`）：`min-height: 40px`。
2. **窄屏「问问这单」回到对话**：`App.vue` 给聊天主区加 ref；`askOrder` 发起提问后，
   若视口 ≤960px，把聊天主区滚到视口顶部。宽屏不滚。
3. **对话可访问性语义**：`ChatThread.vue` 的 `.thread` 从 `aria-live="polite"` 改为
   `role="log"` + `aria-live="polite"` + `aria-relevant="additions text"` +
   `aria-atomic="false"`，让读屏器只播报新增内容。
4. **游客转人工死路复核**：不写代码。核对 `isGuest` 短路与「游客模式不展示工单」
   文案已被 round46 票 192 删除；转人工后 `refreshTickets()` 回填侧栏，详情抽屉可开。
   结论与证据写进 Handoff。

## Verify

`npm run typecheck` + `npm run build`（产物入库）；`git diff --check`、`git status --short`。
有栈时跑 `verify-p2.mjs` 的 `buyerMobile` / `ariaLog` / `guestTransfer` 段。

## 验收项

- 390×844 下四处目标高度 ≥40px。
- 390×844 下点「问问这单」，视口回到 `.buyer-main` 顶部；对话出现新回答。
- `.thread` 的 role/aria 属性符合上述口径。
- 游客转人工：消息不再出现「游客模式不展示工单」，侧栏工单页签能读到该单。
- 端到端无未捕获异常；既有买家功能不回归。

## Handoff notes

**关键决策**

- 40px 只在 `@media (max-width: 640px)` 放宽，桌面 1440×900 的密度与既有截图口径不变；用 `inline-flex` + `min-height` 让行内链接也能吃高度，不改字号。
- 「问问这单」的滚动阈值用 960px（与 `.buyer-layout` 的单列断点同一个数），不是 640px：641-960px 也是单列，按钮下方才是侧栏，不滚回去同样看不到回答。
- 对话容器保留 `aria-live="polite"`，只把粒度改成 `role="log"` + `aria-relevant="additions text"` + `aria-atomic="false"`；流式期间没有加 `aria-busy`，避免读屏器整段静音。
- **P2-12 复核结论：不成立，不写代码。** `grep -rn "isGuest|游客模式不展示工单" frontend-buyer/src frontend-shared/src` 只剩一处过时注释（已顺手改成「喂登录门」），无短路逻辑、无旧文案；转人工后 `runAnswer` 写「进度在下方『我的工单』里」，`body.ticketId` 存在时调用 `refreshTickets()` 回填侧栏。这是 round46 票 192 的既有结果，本轮不重复实现。

**验证落点**

- 两端 `npm run typecheck` + `npm run build` 绿；买家产物 `index-BvWEJ2tR.js` / `index-Bd1Rwo5g.css`。
- 静态核对：`TopBar.vue` / `SideActivity.vue` / `OrderCard.vue` 的 ≤640px 规则、`App.vue` 的 `buyerMain` ref 与 `askOrder` 滚回、`ChatThread.vue` 的四个 ARIA 属性。
- 游客项证据：`App.vue:487-497` 的转人工文案与 `refreshTickets()`、`grep` 无 `isGuest` 短路。
- **未实跑**：`verify-p2.mjs` 的 `buyerMobile` / `ariaLog` / `guestTransfer` 段未执行（8082 网关未启动）。390×844 的实际高度、滚动位置与读屏播报都未实测。

**三个现场追问**

1. 窄屏滚回对话用了 `scrollIntoView({ block: 'start' })`，sticky 顶栏会盖住 `.buyer-main` 顶部约 50px；如果实测发现欢迎语被切，需要加 `scroll-margin-top`。
2. `role="log"` 在流式 token 逐字追加时，部分读屏器仍会逐字播报；是否需要在流式期间改成 `aria-busy="true"`、结束后一次性播报，取决于目标读屏器。
3. 侧栏「查看全部订单/工单」（`.side-more`）与订单卡里的物流单号链接仍在 40px 以下，本轮只修了报告点名的四处；要不要把触屏目标做成全局最小尺寸规则？
