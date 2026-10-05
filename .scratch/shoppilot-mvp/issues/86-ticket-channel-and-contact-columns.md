# 86 工单带渠道与目标

**Status:** implemented（2026-10-04）

## What to build

结果回流的**前置**：工单必须记得买家从哪个渠道来、那个渠道的回我地址是多少（ADR 0059 第 2 条）。

- **`tickets` 加两列**（`shoppilot-ticket` 的 Flyway `V2__ticket_channel_contact.sql`）：`channel` / `contact`。
  两列都可空：不是所有工单都有渠道（系统内部造的、复核单、退款审批单都没有）。
- **`TicketView` 追加两个字段**在尾部（不改动既有反序列化契约，与 ADR 0055 当初的加法同手法）。
- **网关建单时带上**：
  - 降级单：渠道取 `ChannelContext.current()`，目标取适配器 `normalize()` 出来的 `contact`；
  - 邮件回执单：`EmailReceiptWriter` 把 `【回执渠道】{contact}` 从 transcript 里**提到列上**，
    **transcript 那一行保留**（人类可读的历史文本，去掉它等于改既有内容）。
- **跨租户与跨来源的口径不变**：这两列是**投递提示**，不是隔离依据。

## Blocked by

无。round25 全部收口（[85](85-round25-closeout.md)）。

## 口径

- `ddl-auto: validate` 不变：新表/新列只能走 Flyway，否则启动红（ADR 0041）。
- **不新增任何判据**：`verify-channel.ps1` 的五条断言一字不动。
- **不把 `contact` 当身份**：它是一个投递地址，`ADR 0005 防线一` 不管它，但它也**不能**进缓存键或会话归属。

## 验收

- JVM 用例覆盖：降级工单带渠道与目标；邮件回执单两列有值且 transcript 仍含那一行；
  没有渠道的工单（复核单/退款审批单）两列为空且**不影响任何既有行为**；
  跨租户读不到别店的工单（既有口径不因新增两列而松）。
- **变异对照**：建单时不带 `channel` → 上述前两条用例必须红。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

### 落点

- `shoppilot-ticket` 的 Flyway **`V2__ticket_channel_contact.sql`**：`tickets` 加 `channel` / `contact` 两列，**刻意不加索引**（见下）。
- `Ticket` 实体两格 + **`attachDelivery(channel, contact)`**。
- `TicketView` **追加**两格在尾部 + **保留十三参构造**（落到「无渠道、无目标」），
  与 ADR 0055 当初追加那五格、票 82 给 `AuditEvent` 加认证位同一手法。
- `WorkItemService.create(...)` / `createFromReason(...)` 各增一个带渠道与目标的重载（旧的留着，无渠道的调用方不用改）。
- 网关：`ChannelContext` 多带一格 `contact`；`FallbackService.escalate` 与 `EmailReceiptWriter.writeReceipt` 带上两格。

### 为什么实体上是方法而不是构造器参数

构造器已经有 13 个位置参数，再加两个**都是 `String` 的相邻参数**时，
`channel` 与 `contact` 传反**编译器不会报错**，而那等于把结论发到错误的地址去。
命名方法让这个错误在读代码时就能看见——位置参数越多，这个理由越成立。

### `ChannelContext` 要设两次（不是笔误）

`ChannelController` 先 `set(tag)` 再 `normalize()`，而 **contact 是归一之后才有的**。
所以归一成功后要**再设一次**补上 contact，且必须排在 `admission.check()` 之前——
限流那条路也会落工单，它同样要带上目标。第一版差点漏掉这个顺序，那会让 429 落的那张单没有渠道。

### 我自己的迁移改掉了一条既有守卫（这一条最要紧）

`V2` 第一版写了 `create index idx_ticket_channel on tickets (tenant_id, channel)`，
理由是「按渠道查这家店有多少单等着回流」。它当场把
`TicketQueryPlanTest.unboundedListStillHasNoTenantTimeIndex` 判红：
H2 拿它去满足无上界的 `where tenant_id = ?`，于是「无上界就是扫表」这条守卫失效。

**删掉它有两个理由，第二个才是决定性的**：
① 既有守卫不该被一次顺手迁移改掉（那是 round18 当年留下的实测结论）；
② **这条索引根本没有消费者**——回流是按工单号取单，不是按渠道扫全表。
这与 round18 那次否决索引同族：**给没有查询要用的查询面加索引，等于花写入成本换一次计划变化。**
判据一个字没改（那条用例现在照原样绿），改的是我的迁移。

### 一处断言写法（不是实现问题，但值得记）

本服务配了 `spring.jackson.default-property-inclusion: non_null`，所以 null 字段**整格不出现在 JSON 里**。
最初写 `created.path("channel").isNull()` 是错的——那一格根本不存在，`path()` 给回 `MissingNode`，
`isNull()` 为 false，于是断的是序列化器的一个实现细节而不是业务语义。
改成断语义（`asText().isEmpty()`，对 null 与 missing 都成立），并把理由写在用例里。

### 读数

| 项 | 值 |
|---|---|
| JVM 全仓 | `10 + 57 + 325 + 26 = 418`（gateway 322 → **325**，ticket 22 → **26**） |
| 覆盖率 | gateway **64.16%** / ticket **73.65%** / biz-mock 76.92% / tool-api 46.32%，`COVERAGE OK modules=4` |
| 变异对照 | 回执单的 `channel` 置空 → `emailReceiptCarriesBothColumnsAndKeepsTheTranscriptLine` 当场红，还原即绿 |

**一条判据都没动**：`verify-channel.ps1` 的五条断言一字未改（只读代码，没跑活体）。
**未达成照登**：跨服务活体链路（网关建单 → 工单服务两列落库）本轮仍只有 JVM 层证据。

### 现场三问

1. **为什么 transcript 里那一句不删？** 那是人类可读的历史文本。为加一列而删它等于改既有内容——
   而 ADR 0059 要的只是「有一个可以当投递目标用的字段」。
2. **为什么 web 渠道不带 contact？** 买家就在浏览器里等，不存在「送回去」这件事；
   票 87 的「没有目标就不发」正是靠这一格为空的。
3. **为什么投递目标不进隔离口径？** 它是投递提示，不是身份。ADR 0005 的三条防线一条都不看它，
   用例 `contactDoesNotWeakenTenantIsolation` 钉住了这一格。