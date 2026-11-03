# 票 193：三处弹层的键盘可访问性

Round: 46（spec：[round46-spec-ux-p1-fixes.md](../round46-spec-ux-p1-fixes.md)）
ADR: 0081　Status: implemented（2026-11-03）　Blocked by: 无

## What to build

1. `frontend-shared` 增加 `trapDialogFocus(container, onClose)`（**DOM 函数，不是组合式函数**：
   共享包没有 `vue` 依赖，见 ADR 0081 决定 4）：打开时记录触发控件并把焦点送进层内，
   Esc 关闭，Tab/Shift+Tab 在层内循环，释放时把焦点还给触发控件。
2. 三处弹层接入（各加 `ref` 与 `tabindex="-1"`）：
   - 买家 `TicketDrawer.vue`；
   - 工作台 `ResolveDialog.vue`、`RefundDialog.vue`。
3. 点击遮罩关闭的既有行为保留。

## Blocked by / Verify

无。接入后两端 `npm run typecheck` + `npm run build`；活体用 Playwright 验 Esc 与焦点回还。

## 验收项

- 打开弹层：`document.activeElement` 在层内。
- Tab 从最后一个可聚焦元素回到第一个；Shift+Tab 反向；不跑到背后的页面。
- Esc 关闭；关闭后焦点回到打开它的按钮/卡片。
- 遮罩点击仍关闭。

## Handoff notes

### 关键决策

- **共享包只放 DOM 函数，不放组合式函数**：`frontend-shared` 没有 `vue` 依赖（两端以 `file:`
  符号链接接入、自身无 node_modules），写 `ref/onMounted` 会让它反向依赖框架。所以导出
  `trapDialogFocus(container, onClose): () => void`，由各 SFC 在 `onMounted` 调起、
  `onUnmounted` 释放。ADR 0081 决定 4 已按实现改写。
- **可聚焦元素按可见性过滤**（`getClientRects().length > 0`）：弹层里常有条件渲染的按钮，
  把 `display:none` 的元素算进循环会让 Tab 停在一个看不见的地方。
- **初始焦点**：优先 `[autofocus]`，否则第一个可聚焦元素，再否则容器本身（`tabindex="-1"`）。
  三处弹层的首个可聚焦元素都是文本域，所以退款框的初始焦点天然落在备注框——不是「确认放行」，
  一次误敲回车不会提交资金动作。
- **Esc 在 `document` 捕获阶段监听**并忽略 `isComposing`，避免输入法取消候选时把弹层关掉。
- **焦点回还只在触发元素仍连着 DOM 时执行**（`previous?.isConnected`），列表刷新后触发卡片
  可能已经不在，此时不把焦点硬塞回一个游离节点。

### 验证落点

- 两端 `npm run typecheck`、`npm run build`；产物入库（buyer `index-CkPDYnWA.js`、
  workspace `index-C5lmBgoK.js`）。
- 活体 `.scratch/ux-probe/verify-p1.mjs`，两个段落共 9 项实测：
  - `ticketDrawerKeyboard`：2 张工单卡；打开后焦点在 `.drawer` 内；12 次 Tab 未逃出；
    Esc 关闭且焦点回到工单卡；遮罩点击仍关闭。
  - `workspaceDialogs`（现造一张 OPEN 工单与一笔待审退款后）：结单框焦点在文本域、6 次 Tab +
    6 次 Shift+Tab 未逃出、Esc 关闭且焦点回到「处理完成」；退款框焦点在备注框、5 次 Tab 未逃出、
    Esc 关闭且焦点回到「放行」。截图 `.scratch/ux-probe/shots/94-workspace-dialogs.png`。

### 三个现场追问

1. **只验了键盘路径，没验屏幕阅读器。** `role="dialog"` + `aria-modal="true"` +
   `aria-labelledby` 已就位，但层打开时背景内容没有 `aria-hidden`/`inert`，读屏仍可能读到背后页面。
   是否补 `inert` 取决于产品的无障碍目标，本轮不做。
2. **焦点回还依赖触发元素仍在 DOM。** 若关闭前队列刷新把那张卡换掉，焦点会落回 `body`；
   当前没有「关闭后按 id 找回等价卡片」的逻辑。
3. **两层弹层同时打开会抢 Esc。** 监听是 `document` 级的，两个 release 都会响应同一个 Esc。
   现在的产品形态（抽屉/结单/退款互斥）不会出现；若以后叠出第二层，需要改成栈式管理。
