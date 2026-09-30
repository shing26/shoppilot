# 71 事件骨干：Redis Streams 三 topic 族 + 消费组 ACK + 幂等消费

**Status:** ready-for-agent

## What to build

按 ADR 0054 落地事件面。本轮**只做 `ticket.routing` 的生产与消费契约**，`channel.outbound` 与 `audit` 立契约与 topic、不做投递实现（登记不执行，见 round23 spec §2）。

- 事件 schema 进契约库 `shoppilot-tool-api`（`TicketRoutingEvent`、`AuditEvent`、`ChannelOutboundEvent` 三个 record + 事件名常量）。
- 生产端：工单创建/分派后发 `ticket.routing`；规则表改动发 `audit`。
- 消费端：消费组 + ACK + **pending 列表可查**；**至少一次投递 → 消费端幂等**（幂等键 = 事件 id，落库唯一约束）。
- Streams 积压治理：给 `ticket.routing` 定容量上限（`MAXLEN`），超限丢弃要发告警（复用 0051 的告警规则面）。

## Blocked by

[69](69-unified-ticket-entity.md)、[70](70-routing-rules-and-priority.md)。

## 0 token 门禁纪律（本票最容易违反的一条）

CI 上没有 Redis 实例，**门禁不得依赖真实 Streams**。做法：契约与幂等逻辑用内存 fake broker 做 JVM 测试（自检 ≥6 条），真实 ACK/pending 行为按**未达成登记**，在本机清场日活体验收。**不得为了让门禁绿而把 Streams 换成内存实现上线。**

## 验收

- 同一事件被投递两次，消费端只生效一次（唯一约束生效，有用例）。
- 事件 schema 变更有版本字段；`audit` 事件能查到"谁改了规则表"。
- `MAXLEN` 生效且有丢弃计数。
- 消费端不可用时生产端**不阻塞**（有 JVM 用例：消费端挂起时生产者仍能返回）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/retrieval_gate.py
python .scratch/shoppilot-mvp/round3-closeout-audit.py
```

## Handoff notes

（收口时补；真实 ACK/pending 读数若未跑，按未达成登记，不摘红。）
