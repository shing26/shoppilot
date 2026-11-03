# 票 197：工作台——筛选/详情一致、结单需领取、移动端导航

Round: 47（spec：[round47-spec-ux-p2-fixes.md](../round47-spec-ux-p2-fixes.md)）
ADR: 0082　Status: implemented　Blocked by: 195（标签映射）

## What to build

1. **筛选与详情一致**（`frontend-workspace/src/App.vue`）：
   - `selected` 改为从 `filtered` 取，而不是整份 `tickets`；
   - `setFilter` 后若当前选中不在结果里，自动选第一条；结果为空则清空选中；
   - `refresh` 的保留选中判断同样用 `filtered`（保留筛选后仍在列表里的那张）。
2. **结单前必须领取**：
   - `QueueView.vue`：「处理完成」在 `!selected.assignee` 时禁用，并显示
     「先领取这张工单，再处理完成」；
   - `App.vue` 的 `openResolve` 再做一次守卫：未领取时写 `actionError` 并返回，
     不进确认框。
   - **不改后端状态机**：`OPEN → RESOLVED` 仍是合法迁移。
3. **移动端导航（≤640px）**：`SideNav.vue` 从单列改三列两行，与 641-960px 一致；
   导航项 `min-height: 40px`，隐藏的标题/页脚保持隐藏。
4. **工作台顶栏点击目标（≤640px）**：`TopBar.vue` 的「刷新队列」与「退出」最小高度 40px。
5. **知识五步条的「人工复核」不再恒为 0**：
   - `App.vue` 在拉取 REVIEWED 反馈时保留**服务端总数** `reviewedTotal`，
     与「已复核 · 可附稿」列表（附稿后会减少）分开；
   - `KnowledgeView.vue` 的 `steps` 用 `reviewedTotal`，不用 `reviewed.length`。

## Verify

`npm run typecheck` + `npm run build`（产物入库）；`git diff --check`、`git status --short`。
有栈时跑 `verify-p2.mjs` 的 `queueFilter` / `resolveNeedsClaim` / `workspaceMobile` 段。

## 验收项

- 筛到「已超时」且 0 张时，右侧不再显示上一张单；有结果时详情属于当前档。
- 未领取时「处理完成」不可点；领取后可点；守卫在根组件同样生效。
- 390×844 下导航三列两行、队列在首屏可见，导航项与顶栏按钮 ≥40px。
- 附稿之后「人工复核」的计数不回零（仍等于服务端 REVIEWED 总数）。
- 既有工作台门禁断言（队列非空 / 优先级排序 / 确认 / 领取人）不回归。

## Handoff notes

**关键决策**

- `selected` 从 `filtered` 取而不是 `tickets`：筛选是「当前工作集」，详情不属于工作集就不该留在屏幕上。`setFilter` 在同一处把选中挪到第一条或清空，避免出现「列表 0 张、右侧还有一张单」。
- 结单前置只做在**产品面**：`QueueView` 禁用 + 说明，`openResolve` 再挡一次。后端 `WorkItemService.resolve` 对 `assignee == null` 仍放行，`OPEN → RESOLVED` 仍是合法迁移——那是既有契约，改它会动验收面；已登记为下一轮触发项。
- ≤640px 的导航直接沿用 641-960px 的三列两行，不再单独写单列。六个视图在 390px 下是 3×2，队列回到首屏。
- 「人工复核」用 `reviewedTotal`（服务端 `status=REVIEWED` 的完整长度），与 `reviewedFeedback`（去掉已附稿的「可附稿」列表）分开；附稿只缩短后者。

**验证落点**

- 两端 `npm run typecheck` + `npm run build` 绿；工作台产物 `index-DOhAw4q6.js` / `index-wGEHTq37.css`。
- 静态核对：`App.vue` 的 `selected` / `setFilter` / `refresh` / `openResolve` / `reviewedTotal`，`QueueView.vue` 的禁用与 `.action-hint`，`SideNav.vue` 的 ≤640px 规则，`TopBar.vue` 的 40px 规则，`KnowledgeView.vue` 的 `reviewedTotal`。
- 既有工作台 JVM 门禁随全量 verify 一起绿（四模块 773 tests / 129 suites）。
- **未实跑**：`verify-p2.mjs` 的 `queueFilter` / `resolveNeedsClaim` / `workspaceMobile` / `reviewedCount` 段未执行（8082 网关未启动）；空档筛选、领取后详情消失、390px 导航排列都未实测。

**三个现场追问**

1. 筛选到「等待处理」后，坐席领取当前单会让它离开结果集、详情随即消失。这是「详情跟随筛选」的必然结果，但操作手感可能像「点完就跳走」；要不要在领取后自动切回「全部」？
2. 后端 `resolve` 的领取前置要不要立项？触发条件已写进 round47 spec §4：有人需要从 API 侧保证「未领取不可结单」，或演示数据出现无法归责的结单。
3. `reviewedTotal` 统计的是全部 REVIEWED（含已附稿、含 REJECTED 修订的反馈）。如果产品希望五步条只数「还没附稿的复核」，口径要换回列表长度——但那就回到了附稿后归零的老问题。
