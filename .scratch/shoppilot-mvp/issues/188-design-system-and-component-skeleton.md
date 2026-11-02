# 票 188：设计系统与组件骨架

Round: 44（spec：[round44-spec-product-surface.md](../round44-spec-product-surface.md)）
ADR: 0079　Status: ready-for-agent　Blocked by: —

## What to build

1. **设计令牌补齐**（`frontend-shared/src/tokens.css`）：
   - 新增 `--radius-xs/sm/md/lg/xl/full`、`--space-1..8`、`--text-xs..2xl`、`--shadow-1/2/3`、
     `--ring`（focus-visible）、`--ease`、`--dur-1/2`。
   - 把页面里硬编码的色值收敛成语义令牌三件套（实色 / soft / line）：吸收现在散落在两个
     `App.vue` 里的 `#b7ddd8` `#eef8f6` `#e7f4f2` `#c9e6e1` `#fff7ed` `#fed7aa` `#f0faf8`
     `#f4faf9` `#d9eeeb` `#edf7f5` `#fbfefd` 等。
   - 收编已声明未用的 `--radius` / `--font`。
   - 只加**跨端一致**的原子：`:focus-visible` 环、`.chip`、`.kpi`、`.timeline`、
     `@media (prefers-reduced-motion: reduce)`。布局类一律留在各自页面。
   - 更新文件头注释：把"什么放这里、什么不放"写成可照着判的边界。
2. **共享纯函数去重**（`frontend-shared/src/index.ts`）：合并两端各有一份的 `formatTime`、
   `slaText`、`shortId`、`countLabel`；`minutesLeft` / `priorityRank` / `STATUS_LABEL` /
   `PRIORITY_LABEL` 原样不动。若某个函数两端语义确实不同（例如时间格式的秒级精度），
   保留差异并在注释里写明为什么不能合。
3. **买家端拆分**（`frontend-buyer/src/App.vue` 1239 行 → 根壳 + 8 SFC + 1 lib）：
   `TopBar.vue` / `StoreCard.vue` / `LoginGate.vue` / `ChatThread.vue` / `Composer.vue` /
   `SideActivity.vue` / `ServiceCard.vue` / `TicketDrawer.vue`，纯函数进 `src/lib/format.ts`。
4. **工作台拆分**（`frontend-workspace/src/App.vue` 1627 行 → 根壳 + 10 SFC）：
   `TopBar.vue` / `LoginCard.vue` / `SideNav.vue` / `QueueView.vue` / `ConversationsView.vue` /
   `RefundsView.vue` / `ChannelsView.vue` / `QualityView.vue` / `ResolveDialog.vue` /
   `RefundDialog.vue`。
5. **状态归属铁律**（拆分正确性的判据，逐条守）：
   - 状态全部留根组件，子组件只 `props` / `emits`，**不复制 ref**。
   - 流式回答**原地修改消息对象**（`text += chunk`、`trace = [...]`），必须按引用传递，
     不许 clone 或整体替换。
   - 工作台 `selectView(next)` 带懒加载副作用 → 导航组件只 `emit`，由根组件执行。
   - `inFlight` / `asking` / `refundInFlight` / `conversationInFlight` 是跨组件按钮锁，留根。
   - SSE 流、`Notification` 权限、`AudioContext`、push 订阅、`conversationUnread` 组装留根。
   - 保留 `.thread` / `.msg` / `.bubble` / `.ask` 类名（`scripts/verify-buyer.mjs` 手工可用）。
6. **顺带修掉的既有缺陷**：买家 `App.vue` 的 30 秒 `now` 定时器无句柄、无 `onUnmounted` 清理
   （真泄漏），拆分时补上并在 Handoff 记为"顺带修掉的既有缺陷"。
7. **dev 代理**：两个 `vite.config.ts` 增加 `server.proxy`（`/api`、`/auth`、`/widget` →
   `http://127.0.0.1:8082`）。仅 dev server，不动 `build.outDir` / `base` / 产物。
8. **CI 未跟踪产物门禁**（`.github/workflows/ci-subset.yml` 第 9 步）：在
   `git diff --exit-code` 之后追加
   `git status --porcelain -- shoppilot-gateway/src/main/resources/static/workspace shoppilot-gateway/src/main/resources/static/buyer`
   非空即失败（`git diff --exit-code` 看不见新增未跟踪文件，而这正是动态 import 会制造的东西）。
9. **禁止代码分割**：本轮不用 `defineAsyncComponent`、不用动态 `import()`，产物保持单 bundle。

## Blocked by / Verify

无。Verify：

```powershell
cd frontend-buyer; npm run typecheck; npm run build; cd ..
cd frontend-workspace; npm run typecheck; npm run build; cd ..
.\mvnw.cmd -B -ntp verify
git diff --check
git status --short
```

## 验收项

- **零行为变更**：拆分后页面观感、文案、交互与拆分前一致；`git diff --check` 干净。
- 两端 `typecheck` / `build` 绿；`static/buyer` 与 `static/workspace` 产物**整目录**（新增、
  修改、删除）入库——只提交源码不提交产物 = round30 的 CI 红会重演。
- `.\mvnw.cmd -B -ntp verify` 绿，`StaticEntryExemptionTest` 与
  `ServedConsoleHidesDevDefaultsTest` 不受影响。
- 新增 SFC 全部落在 `src/components/`，被 `tsconfig.include` 自动收进 typecheck（无别名、无配置改动）。
- CI 第 9 步包含未跟踪产物检查；`git status --short` 中没有本机日志、崩溃日志、过期快照。

## Handoff notes

（收口时补：关键决策 / 验证落点 / 三个现场追问）
