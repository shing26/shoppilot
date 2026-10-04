# round26 Spec：结果回流买家（工单结单 → 渠道出站）

> 状态：**已开轮（2026-10-04）**，票 86-90。
> 依据：[`program-remaining-blocks.md`](program-remaining-blocks.md)（三块计划）、[ADR **0059**](../../docs/adr/0059-outbound-first-producer-is-ticket-resolution.md)、[ADR 0054](../../docs/adr/0054-event-backbone-redis-streams.md)、[ADR 0053](../../docs/adr/0053-four-domain-service-split.md)、[ADR 0035](../../docs/adr/0035-channel-adapter-normalizes-inbound-to-one-contract.md)。
> 前一轮：[round25](round25-spec-identity-domain.md)（身份域，票 80-85）。

## 0. 这一轮补的是哪一格

program 主线第 3 节的最后一句：**处理结果回流给买家**。

今天它是断的：`resolve` 之后，除了工单表多一行 `RESOLVED`，买家侧什么都收不到。
买家问 → 转人工落单 → 坐席在工单台上处理若干分钟到若干小时 → **然后没有然后**。

## 1. 一个必须先说清的更正

**不要把这一轮当成「把邮件发出去」。** 查证结果：

- `web` / `app` / `miniapp` 的答案是**同步 HTTP 返回**的（`ChannelController` 把 `answer` 放进响应体）；
- `email` 按 ADR 0035 **本来就没有实时回包通道**，交付物是回执工单。

所以今天每个渠道都已经有交付路径，`channel.outbound` 没有生产者**不是缺陷，是还没有一个真的异步答复**。
本轮制造的就是那个异步答复（ADR 0059）。

## 2. 硬边界

- **一条判据都没动**：gold 180、阈值、`judge()`、降级枚举 10 / 降级 9、CI 九步的既有步骤全部原样。
- **不引 SMTP**：email 出站只落投递口，不真发（ADR 0059 第 7 条）。
- **不为出站起第五个 JVM**：消费端在网关进程内（ADR 0053 已把渠道出站适配划给网关）。
- **`ddl-auto: validate` 全档不变**：`tickets` 新增两列必须走 Flyway（ticket 服务 `V2`）。
- **`ChannelOutboundEvent` 的既有 7 个字段不改**：需要补的（`ticketId`）走**新增字段 + 旧构造**，
  与票 82 给 `AuditEvent` 加认证位同手法（追加在尾部，默认值往严的一边倒）。
- **ADR 0035 的入站归一不动**：新增的 `callbackUrl` 是**可选**字段，
  没它就不发事件（照登，不假装）。

## 3. 票序

| 票 | 范围 | Blocked by |
|---|---|---|
| [86](issues/86-ticket-channel-and-contact-columns.md) | 工单带渠道与目标（`tickets` 加两列 + `TicketView` 加两格 + 网关建单时带上） | — |
| [87](issues/87-outbound-publish-on-resolve.md) | 工单服务在 resolve 时发 `channel.outbound`（生产端） | 86 |
| [88](issues/88-outbound-consumer-and-delivery.md) | 网关消费端：按渠道投递 + 幂等 + 失败落回执工单 | 87 |
| [89](issues/89-outbound-gate-and-metrics.md) | 投递门禁与三个计数（发/成/败），照登真实投递的边界 | 88 |
| [90](issues/90-round26-closeout.md) | 收口（EVIDENCE / CODE_MAP / CONTEXT / 审计常数 / tracker） | 86-89 |

## 4. 验收里必须有的两条「不成立也算数」

- **投递是真的发生过**：门禁必须看到**下游真的收到了一次 POST**，而不是「事件发出去了就算」。
  这正是 round23 抓到的「队列空也判过」同族假绿——本轮的门禁承重部分就是「买家侧真的收到了」。
- **失败不丢**：投递目标不可达时，重试耗尽后**那张回执工单必须存在且可查**。
  只验成功路径的话，「失败就丢」的实现也能全绿。

## 5. 资源与验证分档（round23 裁定 A 的延续）

本机可用内存约 1.9 GB，全栈档只在清场日。**本轮默认只交 JVM 层证据**，活体读数按未达成登记；
确需活体时由所有者安排清场日，**不得为跑全栈去停别的项目容器**。

**本轮的一处特殊约束**：JVM 层要验「投递」，得有一个真的 HTTP 端点接——门禁里用
`HttpServer`（JDK 自带）起一个本地回声端点即可，**不需要起全栈**，所以这一格不必拖到清场日。