# 86 工单带渠道与目标

**Status:** ready-for-agent

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

（收口时补）