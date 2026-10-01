# 71 事件骨干：Redis Streams 三 topic 族 + 消费组 ACK + 幂等消费

**Status:** ready-for-agent

## What to build

按 ADR 0054 落地事件面。**范围由所有者裁定收窄（2026-10-01，见 spec §0 的 C 与 D）**：

- **只建 `audit` 一条 topic**，生产端接本轮已存在的真实动作：退款放行/驳回（`reviewRefund`）、反馈复核完成（`markReviewed`）、规则表变更。消费端落审计查询与对账。
- `channel.outbound` 与 `ticket.created` **只落契约**（record + topic 名常量 + 事件 schema 版本号），不写生产端与消费端——它们目前**没有消费者**，为它们造代码是本仓明禁的 speculative generality。触发条件 = 真有下游（渠道出站落地、统计看板）。
- 投递语义是**至少一次** → 消费端幂等（幂等键 = 事件 id）。
- Streams 积压治理：给 `audit` 定 `MAXLEN`，超限丢弃发告警（复用 0051 的告警规则面）。
- `ticket.created` 虽不建，但**落单走 API、事件走 Streams 的分工要写进代码注释**：落单要返回值、要一致，所以走 API；事件只做异步通知。

## 0 token 门禁纪律（裁定 D：接口 + 测试用内存实现）

生产实现走 Spring Data Redis Streams；**另有一个内存实现只存在于测试包**，用来在 CI 上钉死「发布 → 消费 → ACK → 幂等 → pending 归零」这条链路的语义。两点纪律：

1. **内存实现不进生产包**（放测试源集），生产路径不可能误用它；
2. 「真实 Redis 上的行为」（ACK 时序、pending 积压、MAXLEN 丢弃）**本轮无法验证，照登不摘红**——CI 没有 Redis，本机也没有余量起全栈（裁定 A）。

**不得**为了让门禁绿而把生产实现换成内存实现。

## Blocked by

[69](69-unified-ticket-entity.md)、[70](70-routing-rules-and-priority.md)。

## 验收

- 审计事件能被查询端点按「动作 + 对象」列出（退款放行/驳回、复核完成、规则变更各至少一条可查）。
- 同一事件被投递两次，消费端只生效一次（唯一约束生效，有用例）。
- 事件 schema 带版本号；`MAXLEN` 生效且有丢弃计数。
- 内存实现与 Redis 实现跑**同一组**语义用例（同一份契约测试跑两遍），生产实现不是「只跑通就行」。
- 契约库里有另外两条 topic 的 record 与常量，但**没有**它们的实现（这一点有测试钉住：实现类不得出现）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python .scratch/shoppilot-mvp/round3-closeout-audit.py
```

## Handoff notes

（收口时补；真实 Redis 行为与 pending 读数若未跑，按未达成登记，不摘红。）
