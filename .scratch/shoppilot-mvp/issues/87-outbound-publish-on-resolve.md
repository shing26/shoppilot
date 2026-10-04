# 87 工单服务在结单时发出站事件（生产端）

**Status:** implemented（2026-10-04）

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

### 落点

- `ChannelOutboundEvent` **追加** `ticketId` 在尾部 + 保留七参构造（落到 null）。
- `AuditTopics` 的类注释改成实话：**`CHANNEL_OUTBOUND` 是三条里第一条真正落地的**，
  `TICKET_CREATED` 仍然只有契约（工单服务自己就是它唯一的读者，走事件绕一圈没有消费者）。
- 新增 `ticket/audit/OutboundPublisher`（与 `AuditPublisher` 同形：软依赖 + 带 `channel` 标签的计数）。
- `WorkItemService.resolve` 在**结单成功之后**调用它。

### 三处顺序/取向，都是有意选的

1. **事件在落状态之后发**：反过来的话，消费者可能拿到一条「结论已送达」而工单其实没结成。
2. **web 渠道按规则不发**：它有 `channel` 没有 `contact`（买家就在浏览器里等）。
   但这一条必须**计数**——否则「发出去了 0 条」与「都发成功了」在面板上长得一样。
3. **发布失败只记 warn、绝不抛给业务**：与 `AuditPublisher` 同一条取舍。
   坐席点了「处理完成」却因为消息发不出去而回滚，是不可接受的；丢一条出站是缺口，而缺口会被计数看见。

### 两条变异对照都实测为真

| 变异 | 结果 |
|---|---|
| 「有目标才发」改成无条件发 | `doesNotPublishWithoutATarget` 红（`MeterNotFound` 那次除外，见下） |
| 把 `resolve` 里的 publish 整段删掉 | **2 条红**（`resolvePublishesWithTheResolutionNote`、`resolveWithoutNoteStillSendsSomething`） |

### 一个断言写法（值得记，因为它红得读不懂）

「没发出去的路径上发布计数一个都不该有」最初写成
`registry.get("shoppilot_outbound_published_total").counters()).isEmpty()`——
`get()` 在找不到 meter 时**抛 `MeterNotFoundException`**，而那一格虽然也是红，读起来却像「测试环境有问题」。
改成 `registry.find(...)`：找不到返回空列表，断言断的就是「一个都没注册」这件事本身。

### 两条实现坑

- **`StringRedisTemplate` 的 HK/HV 是 `String` 不是 `Object`**（它 `extends RedisTemplate<String,String>`），
  测试里把 `StreamOperations` 声明成 `<String,Object,Object>` 时，`add(topic, Map<String,String>)` 的类型推断直接编不过。
  绕了两轮才对，记在这里。
- **带标签的 Counter 一旦注册就不能再加标签**：对已注册实例调 `.tag(...)` 会抛异常。
  所以用 `registry.counter(name, "channel", value).increment()`，它自带按标签组合的缓存。

### 读数

| 项 | 值 |
|---|---|
| JVM 全仓 | `5 + 10 + 57 + 325 + 32 = 429`（ticket 26 → **32**，+6） |
| 覆盖率 | ticket **73.65% → 76.03%**（门槛 68.00），`COVERAGE OK modules=4` |
| 变异对照 | 两条，均实测为真 |

**一条判据都没动。** **未达成照登**：真实 Redis 上的发布一条没跑（本轮全在 mock 上验证），
`RedisAuditChannel` 那条零覆盖的老账在出站这条链上原样再犯一遍——票 89 的门禁要把这一格补上。

### 现场三问

1. **为什么 ticketId 要追加到事件里？** 消费端对账、失败落回执工单都要指名是哪一张单的事；
   只有 `eventId` 时「这条结论来自哪张单」只能靠 body 里的文本猜。
2. **为什么 web 渠道不发？** 买家就在浏览器里等，不存在「送回去」这件事；
   而真要发，下一票的买家端前端会提供「自读」这条路，比推一条事件更简单。
3. **为什么发布失败不抛？** 见上第三点。这不是宽容，是「让人卡住是事故」。