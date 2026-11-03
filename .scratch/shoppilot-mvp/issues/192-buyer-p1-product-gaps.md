# 票 192：买家侧 P1（游客可见、对话持久化、IME、单层滚动、退出中止流）

Round: 46（spec：[round46-spec-ux-p1-fixes.md](../round46-spec-ux-p1-fixes.md)）
ADR: 0081　Status: implemented（2026-11-03）　Blocked by: 无

## What to build

1. **游客态展示自己的订单与工单**（P1-1）。删掉 `refreshOrders` / `refreshTickets` 里的
   `isGuest` 短路与 `SideActivity` 的游客提示、刷新按钮条件；转人工文案不再写「游客模式不展示工单」。
   数据权限仍由服务端按令牌判定，前端不加任何买家号参数。
2. **对话按标签页持久化**（P1-2）。`sessionStorage` 存 `{tenantId, conversation, messages}`：
   进入时恢复（租户必须与当前店铺一致），发问、回答结束、反馈成功时写回；刷新恢复时把
   `pending` 回答标成「刷新时中断，可重试」；换店（店铺码不一致）与退出登录时清除。
3. **输入法回车**（P1-4）。`Composer` 改为 `keydown.enter` + `isComposing`/keyCode 229 判断。
4. **订单卡单层滚动**（P1-6）。删掉 `.order-list` 的 `max-height: 360px; overflow-y: auto`，
   由 `.buyer-side` 统一滚动。
5. **退出中止流**（P1-7）。`signOut()` 第一步 `stopAnswer()`。

## Blocked by / Verify

无。

```powershell
cd frontend-buyer; npm run typecheck; npm run build; cd ..
$env:NODE_PATH="D:\ShopPilot\.tools\node_modules"; node .scratch/ux-probe/verify-p1.mjs
git diff --check
git status --short
```

## 验收项

- 游客 T001：订单列表非空、工单计数可见；转人工不再出现「游客模式不展示工单」。
- 发问后刷新：消息、回答、会话 ID 都在；点踩仍回指同一会话；换店/退出后不恢复上一家对话。
- 组合中的回车不发送；组合结束的回车发送。
- 展开订单卡：只有一个滚动容器（侧栏），卡片完整随外层滚动。
- 流式中退出：SSE 请求被中止。

## Handoff notes

### 关键决策

- **游客不另开数据面**：只删前端 `isGuest` 短路，请求仍带登录/游客令牌，归属由服务端判定。
  转人工的降级文案不再提「游客模式」——那句话本身就是把权限猜测写进产品话术。
- **对话存 `sessionStorage`，键 `shoppilot.buyer.chat`**，形状 `{tenantId, conversation, messages}`。
  恢复时租户必须等于当前店铺，否则直接丢弃；写入点：发问前、每个早退分支、回答结束、点踩成功。
  上限 100 条（`CHAT_LIMIT`）——超出只截前端存档，不截页面上的当前会话。
- **中断态由前端标注**：存档里 `pending: true` 的回答在恢复时改成「刷新时中断，可以重试」，
  保留原 `retryQuery`，不发任何补拉请求（网关没有会话历史读口，见现场追问 1）。
- **退出先中止再清状态**：`signOut()` 第一句 `stopAnswer()`，随后清 session/chat 存档；
  顺序反过来会留下一个还在往已清空的 `messages` 里写的流。

### 验证落点

- `frontend-buyer`：`npm run typecheck`、`npm run build`（产物入库
  `static/buyer/index-CkPDYnWA.js`）。
- 活体 `.scratch/ux-probe/verify-p1.mjs`（vite dev 5175 → 真网关 8082）实测：
  - 游客 T001：订单卡 2 张、页签「订单20」、无游客提示、`.order-list` `overflow-y: visible` /
    `max-height: none`、展开卡底部 800 < 侧栏底部 900（单层滚动，未被裁切）。
  - 刷新：2 条消息、会话 `buyer-1793682580906` 原样恢复、欢迎页不再出现；
    注入的 pending 回答变成「刷新时中断，可以重试」并带重试按钮。
  - IME：组合中回车 0 条消息、输入值保留；组合结束后回车 2 条消息。
  - 退出：`/api/v1/support/chat/stream` 记为 `net::ERR_ABORTED`，登录门出现，两个 storage 均为 null。

### 三个现场追问

1. **中断的回答在服务端可能其实已经完成。** 本轮只在前端标注，不向服务端核对（网关没有
   「按 conversationId 读历史」的口）。要真正对齐，需要先开一个只读回放口——登记为后续触发项，
   不夹带进本票。
2. **同一浏览器两个标签页会各存一份对话。** `sessionStorage` 是标签页级的，两个窗口各自开一个
   `conversation`，服务端也各记一条。是否需要在多标签间合并/接管，取决于产品是否把「同一买家多窗口」
   当真实场景；本轮选择不合并（合并就要引入跨标签同步通道）。
3. **100 条上限之后，页面上仍只有当前会话。** 更早的消息既不在存档也不在页面，用户无法回看，
   点踩回指的 `conversationId` 仍有效。若要「加载更早」，同样要等服务端会话历史口。
