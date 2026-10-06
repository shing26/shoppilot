# 110 前端：workspace 退款审核面板 + console 只读

**Status:** implemented（2026-10-06）

## What to build

前端退款审核面板。

### workspace（frontend-workspace）

1. 加退款审核面板：待审列表 + 放行/驳回 + 确认对话框。
2. 用坐席登录令牌调 `/api/v1/support/ops/refunds/pending` 和 `/review`。
3. 新增 API 函数：`listPendingRefunds`、`reviewRefund`。

### console（shoppilot-gateway/src/main/resources/static/index.html）

1. 退款面板改只读：保留待审列表，移除放行/驳回按钮。
2. 加提示「退款审核请用坐席工作台」。

## Blocked by

[109](109-gateway-bizmock-hardline.md)

## 口径

- 判据面零改动；前端行为零变化（workspace 加面板，console 改只读）。

## Verify

```powershell
# 前端构建（如果有）
# 活体验证：workspace 退款审核面板可用，console 退款面板只读
```

## Handoff notes

**关键决策**：
- workspace 新增退款审核面板：待审列表 + 放行/驳回 + 确认对话框，用坐席登录令牌调 API。
- console 退款面板改只读：保留待审列表，移除放行/驳回按钮，加提示「审核请去坐席工作台」。
- 新增 API 函数：`listPendingRefunds`、`reviewRefund`。

**验证落点**：
- 前端代码已修改：`frontend-workspace/src/App.vue`、`frontend-workspace/src/api.ts`、`shoppilot-gateway/src/main/resources/static/index.html`。
- 活体验证待进行（需要启动全栈服务）。

**三个现场追问**：
1. 为什么 console 不直接去掉退款面板？—— 保留只读列表可以让运维看到有待审核的退款，但操作必须去坐席工作台。
2. 确认对话框是否足够？—— 足够，资金放行是不可逆的，确认对话框是必要的防护。
3. 坐席工作台的令牌是否安全？—— 令牌存 sessionStorage，关掉标签页就登出，比 localStorage 安全。
