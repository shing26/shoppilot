# 票 195：共享层——可读枚举、401 自动登出、对比度

Round: 47（spec：[round47-spec-ux-p2-fixes.md](../round47-spec-ux-p2-fixes.md)）
ADR: 0082　Status: implemented　Blocked by: 无

## What to build

1. **四张可读名映射**（`frontend-shared/src/index.ts`，与既有 `STATUS_LABEL` /
   `PRIORITY_LABEL` 同源）：
   - `FEEDBACK_VERDICT_LABEL`：`UP → 有用`，`DOWN → 没用`；
   - `REFUND_STATUS_LABEL`：`PENDING_REVIEW → 待审核`，`PROCESSING → 已放行（待通道）`，
     `REJECTED → 已驳回`；
   - `CONVERSATION_STATUS_LABEL`：`BOT → 智能助手`，`AGENT → 坐席接管`；
   - `SERVICE_FLAG_LABEL`：`SEVEN_DAY_RETURN → 七天无理由`，`FRESH_GUARANTEE → 生鲜保障`。
   未知值原样透出。
2. **消费四张映射**：
   - 工作台 `KnowledgeView.vue` 的反馈票（`item.verdict`）；
   - 工作台 `RefundsView.vue` 决策路径的当前态（`row.refund.status`）；
   - 工作台 `ConversationsView.vue` 会话卡片的 `c.status`；
   - 买家 `OrderCard.vue` 的 `detail.serviceFlags`。
3. **401 自动登出钩子**（`frontend-shared/src/index.ts`）：
   - `setUnauthorizedHandler(handler | null)`；
   - `request()` 只在**带了令牌**且响应 401 时调用钩子；
   - 买家 `api.ts` 的 SSE 入口（`streamAnswer`）返回 401 时也通知同一钩子（导出一个
     内部通知函数，不扩大公共面）。
   - 买家 `App.vue`：注册钩子 → 停流、清会话、回登录门、写明「登录已失效，请重新登录」；
     **保留本标签页对话与工单**（令牌过期不等于丢对话）。
   - 工作台 `App.vue`：注册钩子 → 停渠道 SSE、清会话与队列、回登录卡、写明原因。
4. **对比度**：`frontend-shared/src/tokens.css` 的 `--ink-3` 由 `#6b7280` 改为 `#5c636e`。
   不改字号、不加粗。

## Verify

两端 `npm run typecheck` + `npm run build`（产物入库）；`git diff --check`、`git status --short`。
有栈时跑 `verify-p2.mjs` 的 `labels` / `unauthorized` / `contrast` 段。

## 验收项

- 四个位置不再出现原始枚举；未知值原样显示。
- 401（带令牌）自动回登录面并给出原因；登录请求的 401 不触发登出。
- `--ink-3` 对 `--bg` / `--line` / 白底 ≥4.5:1（实测 5.46 / 4.67 / 6.06）。
- 既有 771 条 JVM 用例不回归；两端构建产物与源码一致。

## Handoff notes

**关键决策**

- 四张映射与 `labelOf` 放 `frontend-shared`，与 `STATUS_LABEL` / `PRIORITY_LABEL` 同源；未知值原样透出。没有在每个视图里写 `if`，也没有把后端枚举搬进前端类型。
- 401 钩子只在**带令牌**的请求上触发：登录/一键体验也回 401（密码错、端点未开），那不是会话失效。买家 SSE 是裸 `fetch`，经 `notifyUnauthorized` 复用同一钩子，避免「轮询会登出、流式不会」的半截行为。
- 买家收到 401 只清登录态与进行中的流，**不清对话与工单**：令牌过期不等于这些数据作废；工作台没有可保留的本地会话，清空与主动退出同口径。
- 对比度改根因色值而不是逐处加字号/加粗；桌面密度与字号一格未动。

**验证落点**

- 两端 `npm run typecheck` + `npm run build` 绿；产物整目录更新（买家 `index-BvWEJ2tR.js` / `index-Bd1Rwo5g.css`，工作台 `index-DOhAw4q6.js` / `index-wGEHTq37.css`）。
- `bash ./mvnw -B -ntp verify -Dspring-boot.repackage.skip=true`：BUILD SUCCESS，四模块 **12 / 92 / 619 / 50 = 773 tests / 129 suites**（本票后端零改动，gateway 的两条新增属票 198）。
- 对比度按 sRGB 相对亮度手算：`#5c636e` 对 `#f2f3f5` 5.46、对 `#dfe2e7` 4.67、对 `#fff` 6.06。
- **活体补跑（2026-11-03 晚）**：起栈后实跑 probe。对比度浏览器实测 **5.46 / 4.67 / 6.06**（`--bg`/`--line`/`--panel`），与手算一致；四张映射的真实数据渲染零原始枚举（probe 报的 `DOWN`/`AGENT` 命中的是页面固定说明文案，非数据）；401 自动登出经真实请求路径实测：带令牌请求返 401 → 回登录门、会话清除、显示「登录已失效，请重新登录」。买家 SSE 入口的 401（`streamAnswer` → `notifyUnauthorized`）本轮未单独触发（需要真实令牌过期的流式请求），静态阅读与共享钩子单一路径不变。

**三个现场追问**

1. 令牌过期时是否也要保留买家的**已发送但未回答**那条消息？现在保留的是整份对话，重登后会带着旧会话继续；如果产品希望回登录面即开新会话，需要另定。
2. 后端新增枚举值时，页面上原样透出（本票口径）还是落一条前端告警？透出保证不吞数据，但会把 `NEW_STATUS` 这类字面量直接给用户看。
3. 401 钩子要不要覆盖 `EventSource` 型连接？当前渠道 SSE 用的是 `fetch` 流，钩子已覆盖；将来若换 `EventSource`，它拿不到状态码，需要另设心跳探测。
