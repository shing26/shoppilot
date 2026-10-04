# 87 工单服务在结单时发出站事件（生产端）

**Status:** ready-for-agent

## What to build

**第一生产者**（ADR 0059 第 1 条）：`shoppilot-ticket` 在 `resolve` 成功之后发一条 `channel.outbound`。

- `ChannelOutboundEvent` **追加**一个 `ticketId` 字段（尾部 + 旧构造保留，默认 null）——
  消费端要靠它回执与对账，只有 `eventId` 的话对账不到具体是哪张单。
- 工单服务新增一个 `OutboundPublisher`（与 `AuditPublisher` 同形：软依赖，**发不出去只记 warn 不抛给业务**——
  「坐席点了处理完成却因为出站发不出去而回滚」是不可接受的）。
- **只有带 `contact` 的工单才发**：渠道为 web 或 `contact` 为空 → 不发，**并留下计数**
  （「没有目标就不发」这件事本身要能被查，否则读数上「发出去了 0 条」和「都发成功了」长得一样）。

## Blocked by

[86](86-ticket-channel-and-contact-columns.md)。

## 口径

- **发布失败不抛给业务**（同 `AuditPublisher` 的既定取舍）：丢一条出站是缺口，让人卡住是事故。
- **`eventId` 仍是幂等键**，重投不产生第二次投递（ADR 0054 至少一次）。
- 「没有目标就不发」**必须留痕**（warn + 计数），不许静默跳过。

## 验收

- JVM 用例覆盖：带 contact 的工单 resolve 后发出一条事件且 `ticketId` 正确；
  web 渠道或空 contact **不发**且计数 +1；Redis 不可用时 **resolve 仍然成功**（不因出站失败而回滚）；
- **变异对照**：把「有 contact 才发」改成「无条件发」→ 第二条用例必须红；
- **变异对照**：把软依赖改成抛异常 → 第三条用例必须红。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

（收口时补）