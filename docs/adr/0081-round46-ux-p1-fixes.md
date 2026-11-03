# round46 开轮依据：真实用户走查的七条 P1 缺陷修复

Date: 2026-11-03　Status: **Accepted**（2026-11-03 所有者指令「继续修 P1 六项」）

Owner basis: **缺陷修复，不是新功能**（与 ADR 0080 同一依据，走查报告
[`ux-probe/REPORT.md`](../../.scratch/ux-probe/REPORT.md) 的 P1 清单）。报告实际列了 **7 条 P1**
（上一条汇报里写成「六项」，少算了一条）；本轮把 7 条全部收口，不拆分到下一轮。
其中 P1-3 需要一条增量接口（反馈按状态读 + 复核时结掉复核工单），其余为产品面修复。

Tickets: [192](../../.scratch/shoppilot-mvp/issues/192-buyer-p1-product-gaps.md) /
[193](../../.scratch/shoppilot-mvp/issues/193-dialog-accessibility.md) /
[194](../../.scratch/shoppilot-mvp/issues/194-review-loop-consistency.md)。
Spec: [round46-spec-ux-p1-fixes.md](../../.scratch/shoppilot-mvp/round46-spec-ux-p1-fixes.md)。

## Context

P1 的共同形态是「能力可达，路径断了」：

1. **游客态把订单与工单藏起来**（`isGuest` 短路 `refreshOrders/refreshTickets`），
   但游客令牌是真的 C001 令牌、助手也查得到订单；首页承诺「我的订单、我的工单……都在同一页」。
2. **刷新即丢对话**：`conversation` 是模块变量、`messages` 不落盘；刷新后工单还在，用户却
   不知道自己问过什么，点踩也失去回指对象。
3. **复核工单与复核状态是两套账**：`markReviewed` 从不碰复核工单（工单一直 OPEN）；
   复核队列只回 PENDING，REVIEWED 的反馈刷新后从 UI 消失、再也无法附稿；工单队列里点开复核单
   也没有「去复核」入口。
4. **中文输入法回车直接发送**：`Composer` 的 `@keyup.enter` 不看 `isComposing`。
5. **弹层不守键盘礼让**：工单抽屉、结单框、退款框都没有 Esc、焦点转移与焦点陷阱。
6. **订单深度卡被两层滚动裁切**：侧栏一个 `overflow-y: auto`，里面的 `.order-list` 又一个。
7. **退出登录不中止流**：`signOut()` 清理页面状态，但不 `abort()` 正在跑的 SSE。

## Decision

1. **游客展示自己的订单与工单。** 数据权限早就由令牌决定（C001 的真令牌），页面假装没有
   才是那个 bug；删掉 `isGuest` 短路、侧栏提示与转人工文案里的「游客模式不展示工单」。
   首页承诺不改——产品行为向承诺靠，而不是把承诺改小。
2. **对话按标签页持久化到 `sessionStorage`。** 存 `{tenantId, conversation, messages}`，与登录态
   同一生命周期：换店/退出即清；刷新后恢复消息与会话 ID，未完成的回答标成「刷新时中断，可重试」。
   不用 `localStorage`——同一浏览器两个窗口会互相覆盖，退出后还会残留。
3. **复核闭环以 `feedback.review_status` 为真源。**
   - 点踩时把复核工单号存进 `feedback.review_ticket_id`（Flyway **V11**，H2/PG 双侧）；
   - `markReviewed` 成功后调用工单服务 `POST /api/tickets/{id}/resolve` 结掉那张复核单；
     跨进程失败只记 **warn**、不阻断复核（工单是工作项，不是真源）；
   - `GET /feedback/review-queue` 增加 `status` 过滤（默认 `PENDING`，允许 `REVIEWED`），
     工作台刷新后从服务端取回 REVIEWED 列表，附过稿的按 `revisions.feedbackId` 过滤掉；
   - 工单队列里 `source=FEEDBACK_REVIEW` 且 `payload.feedbackId` 可解析时，详情面板给一个
     「去复核这条反馈」入口，切到知识视图。
4. **弹层可访问性做成共享 DOM 函数**（`frontend-shared` 的 `trapDialogFocus`）：Esc 关闭、
   Tab 在层内循环、打开时焦点进入、关闭后还给打开它的控件。三处弹层共用，不复制。
   **不是组合式函数**：`frontend-shared` 没有 `vue` 依赖（两端靠 `file:` 符号链接接入，
   自身无 node_modules），写 `ref/onMounted` 会让共享包反向依赖框架；因此它只收容器元素与
   `onClose`，由各 SFC 的 `onMounted/onUnmounted` 调起与释放。
5. **退出登录先 `abort()` 再清状态**；`Composer` 的回车在 `isComposing`（或 keyCode 229）时忽略。
6. **订单卡只留一层滚动**：删掉 `.order-list` 的 `max-height/overflow-y`，由侧栏统一滚动。

## Considered Options

- **游客保持隐藏 + 把首页承诺改小**：否决。游客令牌能读到的数据就在那里，助手也答得出订单号；
  改文案只是让产品面与自己的数据能力继续对不上。
- **`localStorage` 持久化对话**：否决。跨标签页共享会让两个窗口互相覆盖，且退出后残留私人对话。
- **在工单「处理完成」时反写反馈 REVIEWED**：否决。工单服务不能反向依赖反馈服务，
  且「内容复核」与「关闭工单」是两件事；正确方向是复核动作顺手结单。
- **只补前端「已复核」副本、不做服务端读口**：否决（那是 round44 的临时办法，刷新即失联，
  已在票 190 现场追问 1 记过）。
- **三处各自实现焦点陷阱**：否决。行为要求一致，复制三份一定会漂移。

## Consequences

- 接口面为**增量**：`feedback.review_ticket_id` 列（V11）、`status` 查询参数、
  `WorkItemClient.resolve` 端口与 `POST /api/tickets/{id}/resolve` 调用（接口早已存在）。
  既有客户端不传 `status` 时行为不变。
- `FeedbackView` 增加 `reviewTicketId` 字段（只增字段）。
- P2 清单（原始枚举进 UI、筛选与详情打架、结单可跳过领取、质量报表 max 0.00ms、对比度、
  移动端点击目标、移动端导航占首屏、401 不自动登出、aria-live 粒度、五步条计数、游客转人工死路）
  原样留在 `REPORT.md`，本轮不执行。
- 复核单被结掉后，工单队列里不再堆积「会话 … 的答复待复核」；历史已复核但工单仍 OPEN 的存量数据
  不做批量回填（演示库可重置），在票 194 里登记。
