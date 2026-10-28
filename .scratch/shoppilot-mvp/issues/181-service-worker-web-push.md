# 181 Service Worker / Web Push 通知

**Status:** implemented（2026-10-29）

**Claimed by:** zcode session 2026-10-29

> 票 180 是页面内 Notification，页面关掉就收不到。本票注册 Service Worker，并用 Web Push（VAPID）把新买家消息推到浏览器关闭后仍可展示的通知。

## 目标

- 注册 `/workspace/sw.js`，接管 push 与 notificationclick；
- 浏览器订阅 push，后端按租户存 endpoint/p256dh/auth；
- 新买家消息（unread>0、最后一条 role=user）由后端发 Web Push；
- SW 收到 push 调 `showNotification`；点击聚焦/打开工作台并回会话；
- 没有 Push 能力时回退票 180 的页面内通知。

## 验收

- SW 注册 active，scope=/workspace/；
- VAPID 公钥接口可用，订阅接口写入 Redis；
- 页面内通知回退仍工作；
- 前端 typecheck/build、全量 JVM 全绿；
- 真实推送发送/浏览器关闭验收有明确边界。

## Non-goals

- 完整推送运维（VAPID 轮换、推送限流）；
- 多实例订阅同步（Redis 已共享）；
- iOS Safari 兼容性专项。

## 已实现

- `frontend-workspace/public/sw.js`：install/activate、push 展示通知、notificationclick 聚焦/打开工作台并回会话；
- Vite 把 sw.js 复制到网关 static/workspace；
- 前端注册 SW；有 Push 能力时订阅并调订阅接口；没有则回退页面内通知；
- 通知开关可关闭：注销后端订阅并 `subscription.unsubscribe()`；
- 后端 `ChannelNotificationController`：VAPID 公钥、subscribe/unsubscribe；订阅 endpoint 必须是 HTTPS；
- `ChannelPushSubscriptionStore`：Redis hash `shoppilot:channel-push:{tenant}`，7 天 TTL；
- `WebPushNotifier`：按未读+最后买家消息发 Web Push；404/410 删除失效订阅；
- EventBus 发布端调用 notifier，远端 listener 不调用，避免多实例重复推送。

## 验证

- 前端 typecheck/build 通过；
- SW 注册：scope `http://127.0.0.1:8082/workspace/`，active=activated，pushManager=true；
- VAPID 公钥接口返回 87 字符；订阅接口返回 subscribed=true；
- 页面内通知回退仍捕获到 title/body/tag；
- 全量 **732 tests / 124 suites** 全绿；
- 截图 docs/workspace-conversations-service-worker.png。

**缺口**：headless 浏览器没有可用 push service，未完成真实 Web Push 发送/浏览器关闭后的系统通知验收；真实推送链路需要 HTTPS/localhost + 可用 FCM 等推送服务。

## Handoff notes

**关键决策**

1. 用标准 Web Push + SW，而不是页面内 Notification，才能覆盖页面关闭；
2. VAPID 未配置时启动生成临时密钥，方便演示；生产必须固定 env，否则重启后旧订阅失效；
3. 只在发布端调用 notifier，Redis listener 不调用，避免多实例重复推；
4. 有 push 订阅时页面内通知让位给 SW，避免双弹。

**验证落点**

- SW 注册/激活；
- 公钥与订阅 API；
- WebPushNotifierTest / ChannelPushSubscriptionStoreTest；
- 全量 logs/verify181b.log。

**三个现场追问**

1. 为什么不用页面 Notification？页面关闭后 JS 不运行，必须 SW + Push；
2. 怎么避免多实例重复推送？notifier 在 publish 端调用一次，远端 listener 只 fan-out SSE；
3. 为什么没看到真实推送？headless 没有 push service，且没有外网推送端点；这是验收边界，不是代码路径。
