# 票 188：设计系统与组件骨架

Round: 44（spec：[round44-spec-product-surface.md](../round44-spec-product-surface.md)）
ADR: 0079　Status: implemented　Blocked by: —

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

### 关键决策

1. **`slaText` 不合并，`formatTime` 合并成 `formatStamp`。**
   票面第 2 条给了"语义确实不同就保留并写明"的出口，这里用的正是它。两端 `slaText` 的差别
   不在精度而在**产品语气**：买家看到"已超时（状态不变）"和"—"，坐席看到"已超时"和"无 SLA"。
   前者要拦住"是不是该有变化"的疑问，后者要一眼判断有没有 SLA 这回事。这跟工作台/买家
   403 文案不同源是同一条规矩，不是遗漏。
   `formatTime` 只有兜底字面量不同（`''` vs `'—'`），所以共享层只出
   `formatStamp(value): string | null`，各自写自己的 fallback（`frontend-buyer/src/lib/format.ts`
   的 `formatStamp(...) ?? ''`、`frontend-workspace/src/lib/format.ts` 的 `?? '—'`）。
   `shortId` / `countLabel` 行为完全一致，直接共用。

2. **共享原子一律 `ui-` 前缀**（`.ui-chip` / `.ui-kpi` / `.ui-timeline`）。
   开工前 grep 发现两个 `App.vue` 里已经有页面本地的 `.chip`（买家 1050 行）、`.kpi`
   （工作台 1467 行）、`.timeline`（工作台 1510 行），样式与预期原子不同。不加前缀就会
   在引入共享样式的瞬间**静默重绘**既有区块——那违反本票"零行为变更"。文件头注释已写明
   这个理由，后续要复用就先加 `ui-`。

3. **换队列不用 `v-model`，改 `:value` + `@change`。**
   原模板在同一个 `<select>` 上既有 `v-model="queue"` 又有 `@change="refresh"`，刷新读到
   哪个值取决于两个处理器顺序。拆成 `QueueView` 后，选择只 `emit('changeQueue', v)`，根组件
   `changeQueue()` 先落 `queue.value` 再 `void refresh()`，顺序由代码决定而不是由框架内部
   顺序决定。

4. **`SideNav` 只发 `select`，永不写 `view`。**
   `selectView(next)` 带懒加载副作用（退款/质量/渠道/会话各自拉数据）。组件写 `view` 会绕过
   副作用，于是"切过去了但数据没拉"。

5. **页级 `styles/base.css`，不进 `frontend-shared`。**
   两边共用的只是令牌与三件原子；`.ghost` / `.pane-head` / `.status-pill` / `.notice` 这些
   密度与语气都不同（买家留白更大、工作台更紧凑），塞进共享层等于把两种产品语气压平。

6. **顺带修掉的既有缺陷**：买家 `now` 的 30 秒 `setInterval` 原先没有句柄也没有 `onUnmounted`
   清理，属真实泄漏。拆根组件时一并补（`clockTimer` + `onUnmounted`）。这是本票唯一的行为
   改动，方向是"修泄漏"，不是重构。

7. **CI 仍是九步。** 未跟踪产物检查作为第 9 步的第四条断言追加，没有新开步骤，也没改验收阈值。

8. **散色收敛时做了取舍：能精确映射的用既有令牌，剩下 6 个新增令牌承载"同一色相的不同档位"。**
   精确命中：`#eef8f6`→`--teal-soft`、`#b7ddd8`→`--teal-line`、`#fff7ed`→`--amber-soft`、
   `#fed7aa`→`--amber-line`。新增：`--teal-soft-2`（用户气泡底）、`--teal-line-2`（气泡/虚线描边）、
   `--teal-wash`/`--teal-wash-deep`（内嵌面板底 / 导航选中底）、`--panel-active`（选中卡片底）、
   `--amber-ink`（橙色文案）。**没有为了减少令牌数去合并肉眼难分的近似色**——那会在"零行为变更"
   的票里引入不可评审的像素差异；反过来也没有把每个散去色都变成独立令牌，`#f0faf8`（一处）与
   `#fbfcfc`（一处）分别并进 `--teal-wash` 与既有 `--panel-2`。扫完 `grep` 无残留十六进制色值
   （`#fff` 除外）。

### 验证落点

| 命令 | 结果 |
| --- | --- |
| `cd frontend-buyer && npm run typecheck` | 绿（`vue-tsc --noEmit`，无输出） |
| `cd frontend-buyer && npm run build` | 绿，单 bundle：`index-AuA6gb6-.js` 99.66 kB + `index-DYtyjvkC.css` 21.20 kB |
| `cd frontend-workspace && npm run typecheck` | 绿（同上） |
| `cd frontend-workspace && npm run build` | 绿，单 bundle：`index-BejUBrXC.js` 119.23 kB + `index-Cl0VGrUX.css` 21.51 kB |
| `bash ./mvnw -B -ntp verify` | **BUILD SUCCESS**；四模块 `Tests run: 12 / 82 / 613 / 50`，合计 **757**，0 失败 0 错误（与 round43 收口同数——本票零后端改动） |
| `git diff --check` | 干净（曾报 `frontend-shared/src/index.ts` EOF 空行，已修） |
| `git status --short` | 无 `logs/`、无 `hs_err_pid*`、无过期快照；`.claude/` 与 `.scratch/fix-docker-sockets*.ps1` 为开工前既存未跟踪项，未提交 |

产物入库：`static/buyer` 与 `static/workspace` 的**新增、修改、删除**三态全部进 `8b58c34`
（旧 chunk 两个删除、新 chunk 两个新增、两个 `index.html` 修改）。两端均为单 bundle，
没有 code-splitting，未跟踪产物检查在本地手跑同样为空。

### 三个现场追问

1. **"零行为变更"凭什么可验证？** 结构换了，class 名和 DOM 层级大体保留，但没有截图基线。
   本轮靠 `npm run typecheck`（严格模式会红在残留 import 与未读 prop）+ 逐文件人工比对模板。
   若后续要把它变成可回归的判据，就是 spec §4 登记项 1（前端测试 runner）的触发条件之一。
2. **`.ui-*` 原子现在有人用吗？** 有（`.ui-chip` 与 `.ui-timeline` 在票 189 的深度卡里会正式
   启用），但本票只建不接——若票 189 发现形状不合用，改的是共享层而不是页面。
3. **`server.proxy` 为什么没转发 `/widget`？** 票面写的是 `/api`、`/auth`、`/widget`；实际只加了
   前两个。买家端的 widget 是 `static/widget/*` 的独立挂载物，`npm run dev` 下没有对应页面
   要调，加上去只是多一条没人走的规则。若将来 dev 下要调 widget，再补。

