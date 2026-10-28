# 180 渠道会话：浏览器通知

**Status:** implemented（2026-10-29）

**Claimed by:** zcode session 2026-10-29

> 收件箱已有 SSE 未读，但坐席离开工作台标签页就看不到。本票加浏览器通知开关与点击回会话。

## 目标

- 渠道页提供“开启通知”按钮，调用 Notification.requestPermission；
- 权限/开关存 localStorage，刷新后保持；
- 新买家消息（unread>0 且最后一条 role=user）弹通知，body 用 lastMessage；
- 当前选中会话且页面可见时不弹（自动已读）；
- 点击通知聚焦窗口、切到渠道会话、选中该会话并已读；
- 权限拒绝/浏览器不支持给出明确文案。

## 验收

- 权限 granted 后开关显示已开启；
- SSE 新消息触发 Notification（标题+body+tag=conversationId）；
- 坐席回复/接管/unread=0 不弹；
- 点击通知回会话；
- 前端 typecheck/build 通过，旧 JVM 不回退。

## Non-goals

- Service Worker 离线通知；
- 声音；
- 浏览器关闭后的推送。

## 已实现

- 渠道页「开启浏览器通知」按钮，调用 Notification.requestPermission；
- 权限/开关存 localStorage，刷新保持；
- SSE conversation 事件且 unread>0、最后一条 role=user 时弹通知；
- 当前选中会话且页面可见不弹；
- 点击通知聚焦窗口、切到渠道会话、选中会话并已读；
- 权限拒绝/不支持有明确文案。

## 验证

- 工作台 typecheck/build 通过；
- Playwright 用伪造 Notification 验证：按钮变“通知已开启”；SSE PUBLISH 后捕获 `{title:'新的渠道消息 · feishu', body:'浏览器通知测试', tag:'feishu:chat:oc_ui_notify'}`；调用 onclick 后切到渠道会话并选中。
- 截图 docs/workspace-conversations-notification.png。
- 未做：真实 OS 通知授权弹窗/桌面通知展示（headless 环境用假 Notification 验证逻辑）。

## Handoff notes

**关键决策**

1. 只在 unread>0 且最后一条是客户消息时通知，坐席回复/接管不弹；
2. 选中且页面可见不弹，避免自己正在看的会话重复提醒；
3. 点击通知回会话并调用已读；
4. 权限与开关 localStorage 持久化。

**验证落点**

- Playwright notification 捕获；
- 截图 docs/workspace-conversations-notification.png；
- 前端 typecheck/build。

**三个现场追问**

1. 为什么用假 Notification？headless 无法展示 OS 通知，但能验证构造参数、权限与点击回调；
2. 为什么选中可见不弹？坐席正看着该会话，未读会自动清零，再弹是噪音；
3. 浏览器关闭还能通知吗？不能，这是页面内 Notification，不是 Service Worker 推送。
