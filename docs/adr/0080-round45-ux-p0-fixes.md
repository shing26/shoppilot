# round45 开轮依据：真实用户走查的三处 P0 缺陷修复

Date: 2026-11-03　Status: **Accepted**（2026-11-03 所有者指令「先把 P0 三项修掉」）

Owner basis: **缺陷修复，不是新功能**。所有者先要求「模拟真实用户使用体验这个产品，找出缺陷与不足」，
走查报告 [`ux-probe/REPORT.md`](../../.scratch/ux-probe/REPORT.md) 给出三处 P0；随后指令修掉这三项。
v1.0.0 冻结后**新功能票**必须引用 ADR 0030 / 0031 的触发条件；本票不新增能力面、不新增后端接口、
不改验收判据，按 ADR 0068 的先例（所有者报障触发的缺陷修复）开轮，不按政策覆盖登记。

Tickets: [191](../../.scratch/shoppilot-mvp/issues/191-buyer-session-feedback-and-scroll.md)。
Spec: [round45-spec-ux-p0-fixes.md](../../.scratch/shoppilot-mvp/round45-spec-ux-p0-fixes.md)。

## Context

走查用 5 个 Playwright 脚本按真实用户路径操作三个入口（买家游客态与一键体验、工作台、门户），
全程 0 未捕获异常、0 console error。三处 P0 都有可复现读数：

1. **同一标签页换店沿用上一家的会话**。`/` → 「星选生活旗舰店」→ 返回 → 「云栖酒店」：
   地址栏 `?store=T004`、店卡显示「星选生活旗舰店」、快捷问题是酒店问题、回答是
   「我们是星选生活旗舰店，不是酒店哦～」。新标签页直开 T004 完全正确。
   根因是 `readStoredSession()` 只读 `sessionStorage`，`onMounted` 只要 `session` 非空就沿用，
   **从不校验 `session.tenantId` 与 URL 店铺码**。数据层没有越权（令牌是上一家的真令牌），
   错在页面把「换店」当成了「刷新」。
2. **买家页没有点赞 / 点踩入口**。`grep` 在 `frontend-buyer/src/` 下 0 命中 `feedback`；
   回答下面只有「重试」。`README.md` 写着「回复后可点踩/点赞」，而 round43/44 的知识飞轮
   在产品里没有真实起点——工作台门禁脚本是直接 `fetch` 造反馈，那层绿把断链盖住了。
3. **对话不自动滚动**。390×844 连问 3 个问题后 `.thread` 的 `scrollTop=0`、
   `clientHeight=535`、`scrollHeight=908`，最后一条在 y≈919，完全不可见。
   全前端 `scrollIntoView / scrollTop / scrollHeight / nextTick` 0 命中。

## Decision

三处都按「入口补线」修，不动后端语义：

1. **店铺码即身份边界**：`readStoredSession()` 在 URL 有店铺码时校验
   `stored.tenantId === STORE_CODE`，不一致就丢弃并重新走 `AUTO_GUEST` / 登录门；
   没有店铺码时不做判断（直接打开 `/buyer/` 沿用上次身份比默默登出更合理）。
2. **反馈入口回到产品里**：新增 `answerFeedback()` 调既有 `POST /api/v1/support/chat/feedback`；
   **会话 ID 记在生成它的那条回答上**，不在页面级「最后一次问」；点踩先展开可选原因
   （原因正是复核人要读的那句话），成功后把回执写在消息下面（UP「已记下：这条回答有用」，
   DOWN「已记下：会进入人工复核」），失败把下游原话留在同一位置、状态退回未评，可再点。
3. **滚动跟随是「贴底才跟」**：`ChatThread` 内以 `scrollHeight - scrollTop - clientHeight <= 96px`
   判断是否贴底；用户自己发的新消息（及其占位回答）强制跟随，其余增量只在贴底时跟随，
   用户往回翻时不被拽回底部。

## Considered Options

- **用页面级 `conversation` 变量投反馈**：否决。一次打开里可能问好几轮，页面级变量只能指到最后一次，
  会把第三轮的赞记到第一轮头上；会话 ID 必须随消息走。
- **`watch(messages, scrollIntoView)` 无条件滚到底**：否决。流式期间每个 token 都触发，且会把
  正在往回翻旧对话的用户硬拽回底部；「贴底才跟」是聊天产品的基本礼让。
- **读回会话不符时主动 `signOut()`**：否决。等价于丢弃 + `AUTO_GUEST` 重新登录，但多一次
  按固定值渲染的闪烁；直接返回 `null` 走同一条既有分支。
- **同时修 P1-3（复核后工单不销账、REVIEWED 反馈刷新即失联）**：否决。那需要新的读口或 actor 字段，
  是接口面动作，本票面只批准三处 P0；留在 `REPORT.md` 与现场追问里。

## Consequences

- 买家页第一次有了知识飞轮的入口：点踩 → PENDING → 工作台复核队列（活体已确认同一条
  `feedbackId` 出现在 `/ops/feedback/review-queue`）。
- 未修项保留在 [`ux-probe/REPORT.md`](../../.scratch/ux-probe/REPORT.md)：P1-3 是下一条真实断点
  （复核完成后复核工单仍 OPEN，REVIEWED 反馈刷新后不可见），触发 = 知识飞轮进入演示清单或所有者点名。
- 运行中的 fat jar 仍提供旧产物；本票证据取自「新构建 + 本机 dev server 代理真网关」，
  与提交入库的 `static/buyer` 是同一次 `npm run build` 的产物。重建 jar 后由网关直接提供。
