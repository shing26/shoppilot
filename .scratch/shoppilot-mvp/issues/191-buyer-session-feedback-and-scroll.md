# 票 191：买家换店会话、点踩入口与对话滚动

Round: 45（spec：[round45-spec-ux-p0-fixes.md](../round45-spec-ux-p0-fixes.md)）
ADR: 0080　Status: implemented　Blocked by: 无

## What to build

真实用户走查（[`ux-probe/REPORT.md`](../../ux-probe/REPORT.md)）的三处 P0。三处都只补产品面的线，
**不动后端语义、不新增接口**。

1. **同一标签页换店不再沿用上一家的会话**（`frontend-buyer/src/App.vue` `readStoredSession`）。
   URL 有店铺码时校验 `stored.tenantId === STORE_CODE`；不一致就清掉这条存储并返回 `null`，
   由既有 `onMounted` 分支重新走 `AUTO_GUEST` 或登录门。没有店铺码时不做判断。
2. **买家点赞 / 点踩入口**（`frontend-buyer/src/api.ts` `answerFeedback`、`App.vue` 状态与投递、
   `components/ChatThread.vue` 呈现）：
   - `POST /api/v1/support/chat/feedback`，body `{conversationId, verdict, reason?}`；
   - **会话 ID 记在生成它的那条回答上**（`ChatMessage.conversationId`），不用页面级变量；
   - 「有用」直接投；「没用」先展开可选原因框（提交 / 取消），原因随反馈进复核队列；
   - 成功后回执留在消息下：UP「已记下：这条回答有用」，DOWN「已记下：会进入人工复核」；
     失败把下游原话留在同一位置，状态退回未评、按钮可再点；
   - 提交中锁按钮；`pending / failed / stopped` 的回答不显示评价格。
3. **对话自动滚动**（`components/ChatThread.vue`）：线程贴底（`scrollHeight - scrollTop - clientHeight
   <= 96px`）时跟随消息增量与流式文本；用户自己发的新消息及其占位回答强制跟随；用户往回翻时不拽回。

## Blocked by / Verify

无。Verify：

```powershell
cd frontend-buyer; npm run typecheck; npm run build; cd ..
$env:NODE_PATH="D:\ShopPilot\.tools\node_modules"; node .scratch/ux-probe/verify-p0.mjs
.\mvnw.cmd -B -ntp verify
git diff --check
git status --short
```

## 验收项

- 同一标签页 T001 → T004：店卡、快捷问题、存储里的 `tenantId` 三者一致；直开 T004 同读数。
- 回答下出现「有用 / 没用」；点踩提交返回 200 且 `reviewQueued=true`，同一条 `feedbackId`
  出现在工作台复核队列（PENDING）；页面出现回执、按钮消失。
- 390×844 连问 3 题后线程贴底、最后一条在视口内；手动上翻后新回答不抢滚动。
- 无验收判据、阈值、gold、指标分母改动；后端零改动。

## Handoff notes

### 关键决策

1. **店铺码是身份边界，不是显示参数。** `readStoredSession` 只在 URL 带店铺码时校验；没有店铺码
   直接打开 `/buyer/` 时沿用上次身份——那是「刷新」，不是「换店」。丢弃后不清空对话之外的东西
   （本来也还没加载），走既有 `AUTO_GUEST` 分支重新登录，页面不会卡在空白登录门。
2. **反馈的会话 ID 随消息走，不随页面走。** 一次打开里可能问好几轮；用页面级 `conversation`
   会让第三轮的赞记到第一轮头上。`ChatMessage.conversationId` 在 `ask()` 生成占位回答时写入，
   重试（`runAnswer`）清掉该条的 `feedback*` 字段但保留会话 ID。
3. **点踩先写原因，再提交。** 原因正是复核人要读的那句话；不填也能提交（`reason` 可选，
   网关与 biz-mock 的既有契约）。成功后才把 `feedback` 写进消息对象——先写再发会让失败看起来像成功。
4. **滚动是「贴底才跟」，不是「永远到底」。** `nearBottom` 阈值 96px 吸收一张卡片的高度抖动；
   自己和自己的占位回答强制跟随，其余增量尊重用户上翻的位置。监听 `messages.length` 与最后一条
   的 `text.length`（流式逐 token 追加），`nextTick` 后落 `scrollTop = scrollHeight`。
5. **`defineModel('feedbackReason', { required: true })`** 承接原因输入；草稿（评哪一条、投什么票、
   写了什么）是页面级状态，按票 188 的状态归属铁律留在根组件，子组件只收 props / 发 emits。
6. **验证走 dev server 代理真网关**：运行中的 fat jar 里是旧产物（`index-BVVfRdyG.js`），
   `npm run dev` 的 `server.proxy`（round44 票 188 加的）让未打包源码连 8082 的真数据；
   提交入库的 `static/buyer` 与验证用的是同一次 `npm run build`。

### 验证落点

| 命令 / 操作 | 结果 |
| --- | --- |
| `cd frontend-buyer && npm run typecheck` | 通过（`vue-tsc --noEmit`，无输出） |
| `cd frontend-buyer && npm run build` | 绿；`index-CnaoOaa9.js` **109.53 kB** / `index-BSV93p3Z.css` **26.67 kB**，单 bundle |
| `.scratch/ux-probe/verify-p0.mjs`（dev server + 真网关代理） | 三项全过，读数见下 |
| 跨店：T001 → T004（同一页） | 店卡 `星选生活旗舰店` → `云栖酒店`；存储 `tenantId` T001 → **T004**；快捷问题切换为酒店三问 |
| 点踩：点「没用」→ 原因「回答没有给出退款时限」→ 提交 | 页面回执「已记下：会进入人工复核」；`POST /api/v1/support/chat/feedback` **200**，`{"feedbackId":"FB1793676981361-4","reviewQueued":true}`；评价格消失（`rate-btn` 0） |
| 工作台复核队列（T004 agent 令牌） | `GET /ops/feedback/review-queue` **200**，2 条 PENDING；第一条即 `FB1793676981361-4`，`reason` 与页面输入一致 |
| 滚动：390×844 连问 3 题 | `scrollTop=400`、`clientHeight=535`、`scrollHeight=935`，`atBottom=true`，最后一条在视口内 |
| `bash ./mvnw -B -ntp verify -Dspring-boot.repackage.skip=true` | **BUILD SUCCESS**；四模块 **12 / 89 / 617 / 50 = 768 tests**、0 失败。为什么不带原命令：运行中的 fat jar 被 JVM 锁住，第一次 `verify` 在 `spring-boot:repackage` 处报 `Unable to rename …jar`（测试已全绿，`shoppilot-ticket` 因此 SKIPPED）；跳过 repackage 后全模块跑完。**只在干净 runner 上成立的那一步**（重新打包）由 CI 覆盖，本机栈未重启。 |
| `git diff --check` / `git status --short` | 见收口提交；无 `logs/`、崩溃转储、过期快照 |

### 三个现场追问

1. **游客态能点踩，却看不到自己的订单。** 游客令牌是真实的 C001 令牌，反馈能落库；但
   `isGuest` 让订单与工单永久隐藏（P1-1）。首页承诺「我的订单、我的工单与政策引用都在同一页」，
   四个店铺链接却全落游客态。是让游客展示自己的订单，还是把首页承诺改成实话，需要所有者定。
2. **反馈与复核工单是两套账，互不销账。** 点踩同时开 `FEEDBACK_REVIEW` 工单，复核完成后
   工单仍 OPEN（`markReviewed` 不碰工单），而 REVIEWED 的反馈从队列里消失、刷新后不可见。
   收口需要「按状态列反馈」的读口或复核口返回刚复核的条目，外加 actor 字段——接口面动作，未做。
3. **刷新即丢对话，`conversationId` 只活在当前标签页。** 点踩回指依赖消息对象上的会话 ID；
   刷新后对话与工单关系一起断（工单还在，但用户不知道自己问过什么）。要做「关掉再打开继续」，
   得先定本地持久化的隐私边界（存什么、何时清），不是纯前端能拍的。
