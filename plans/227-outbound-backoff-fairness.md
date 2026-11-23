# 票 227 契约：出站跨轮退避与 PEL 公平读取

- Round: 57
- 类型: 回归修复 / 运行时缺陷（不新增产品面）
- 开票依据: 所有者 2026-11-23 指令「冻结 plans/227 → 实现 → 独立 Verifier」；
  票 225 独立 Verifier 登记的 P1「跨轮重投无退避」与「PEL 满 BATCH 时新消息等一轮」。
- ADR: 0087 §4（指数退避与跨重启幂等）；按 ADR 0031
  「回归、崩溃和现有门禁要求的修复不受冻结限制」执行，不改 ADR 0087 结论。
- 冻结时间: 2026-11-23
- Implementer: root（独立分支 `codex/227-outbound-backoff-fairness`）
- Verifier: 独立 subagent；先窄测后全量，验证命令由 Verifier 亲跑

## 问题

1. `OutboundDeliveryService.handle()` 返回 `false`（回执工单未落库）时，事件不 ack、
   留在 PEL；下一轮 `drain()` 仍按固定 `poll-interval`（默认 5s）重新执行整轮投递与
   回执写入。ticket 服务长时间不可用时，每条约每 6 秒重试，2 分钟约 18 次，
   没有跨轮退避。
2. `drain()` 先读 PEL 最多 `BATCH=64` 条，只有剩余容量才读新消息。PEL 恰好有 64 条时，
   本轮不读新消息，新事件至少等一轮（默认 5 秒）。

## 目标

- **跨轮退避**：只对 `handle()==false`（回执未落定）的事件生效；按事件记录失败轮次与
  下次可重试时间，未到时间时跳过且不 ack。默认 `base=5s`、`factor=2`、`max=5m`、
  `jitter=0.2`。事件始终留在 PEL，不因退避被 ack 或丢弃。
- **公平读取**：每轮分别读 PEL（offset `0`）与新消息（offset `>`），各自最多 `BATCH`，
  按 `RecordId` 去重；PEL 满 `BATCH` 时新消息仍在本轮被读取。单轮最多处理 `2*BATCH`
  条记录。
- **不改单次投递内的重试曲线**：`RetryPolicy` 的 3 次、500ms / ×2 / 5s / ±20% 保持不变。

## Allowed paths

- `plans/227-outbound-backoff-fairness.md`
- `.scratch/shoppilot-mvp/issues/227-outbound-backoff-fairness.md`
- `.scratch/shoppilot-mvp/README.md`
- `docs/EVIDENCE.md`
- `docs/STATUS.md`（仅由 `scripts/gen_status.py` 重生成）
- `docs/TUNING_PLAN.md`（仅测试数机械同步）
- `README.md`（仅测试数 badge / 复现段同步）
- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/channel/OutboundDeliveryService.java`
- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/channel/RoundBackoffPolicy.java`（新增）
- `shoppilot-gateway/src/main/resources/application.yml`
- `.env.example`
- `shoppilot-gateway/src/test/java/com/shoppilot/gateway/channel/OutboundDeliveryTest.java`
- `shoppilot-gateway/src/test/java/com/shoppilot/gateway/channel/RoundBackoffPolicyTest.java`（新增）
- `shoppilot-gateway/src/test/java/com/shoppilot/gateway/config/ConfigValidationTest.java`

## Acceptance criteria

- **AC1 回执失败保留 pending**：`handle()==false` 时记录不 ack，事件仍留在 PEL；
  跨轮退避状态按 `eventId`（无 `eventId` 时用 `RecordId`）记录。
- **AC2 退避窗口内不重试**：未到 `nextAttemptAt` 时再次 `drain()`，不得对该事件发起
  任何 HTTP 投递或回执写入；记录仍不 ack。
- **AC3 退避到期后重试**：时钟越过 `nextAttemptAt` 后，事件重新进入投递/回执路径；
  延迟按 `base * factor^(failedRounds-1)` 计算并受 `max` 封顶，抖动可配置。
- **AC4 PEL 满时新消息不被饿死**：PEL 已有 `BATCH` 条记录时，本轮仍读取新消息并处理；
  旧实现「PEL 满 → 不读新消息」的路径必须被测试钉红。
- **AC5 单轮去重**：同一 `RecordId` 同时出现在 PEL 与新消息读取结果时，只处理一次；
  成功时只 ack 一次，不产生第二次 POST。
- **AC6 既有语义不变**：坏消息 ack 但不投递；已投递 `eventId` 跳过并 ack；
  webhook 2xx 标记 delivered 并 ack；3 次失败后落回执；回执 `empty` 或异常仍不 ack。
- **AC7 状态有界**：跨轮退避表最多保留 4096 条，超出按最旧淘汰；成功 ack 后清除对应条目。
- **AC8 配置与文档一致**：四个新配置键有默认值并登记到 `CONFIG_PLACEHOLDERS`；
  `mvnw verify`、`gen_status --check`、`verify_doc_truth`、`git diff --check` 全绿，
  测试数变动同步到 README / TUNING_PLAN / STATUS，不删除或弱化任何既有断言。

## Verification commands

窄测（实现方与 Verifier 各跑一次）：

```powershell
.\mvnw.cmd -B -ntp -pl shoppilot-gateway -am -Dtest=OutboundDeliveryTest,RoundBackoffPolicyTest test
```

全量：

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
git diff --check
git status --short
```

活体（本机栈可用时必跑；不可用则登记边界，不得声称 PASS）：

```powershell
$env:SHOPPILOT_GATEWAY_BASE = "http://127.0.0.1:8082"
node scripts/verify-channel-hot-reload.mjs
```

## Forbidden

- 不改 `RetryPolicy` 的现有曲线、默认值或 `fast()` 语义。
- 不改 ack 语义：坏消息 ack、已投递跳过 ack、回执 `empty`/异常不 ack。
- 不把「跨轮退避」实现成「超限后 ack 丢弃」；事件必须保留到回执落库。
- 不删除、跳过或弱化任何现有测试断言；不改 gold、阈值、分母或覆盖率门槛。
- 不新增生产依赖；不改 ADR 0087 结论；不动飞书连接、EgressPolicy、身份、前端或无关模块。
- 不借本票顺手重构 `OutboundDeliveryService` 之外的代码。

## 设计约束（实现提示，不是额外 AC）

- 跨轮状态由单线程消费者持有，使用有界 `LinkedHashMap`；不要为它引入 Redis 依赖。
- 跨轮退避只在 `handle()==false` 后登记；成功、坏消息、已投递跳过都必须清除状态。
- `drain()` 的两读必须保留「先 PEL 后新消息」的顺序；按 `RecordId` 去重后再处理。
- 测试通过注入 `LongSupplier` 时钟与无抖动 `RoundBackoffPolicy` 避免真实等待。
- 退避期间不每轮打 WARN；用 debug/info 记录「尚未到期」，避免日志风暴。

## 现场追问

1. 跨轮退避状态是否要跨进程持久化？本票只做进程内；重启后立即重试一次，仍满足
   「不丢」。若要求多实例共享退避，需要另票（Redis 状态 + 多实例协调）。
2. 单轮最多处理 `2*BATCH` 条，极端情况下单轮耗时可能翻倍；若量级上升，应改为
   按处理预算取数，而不是继续扩大批量。
3. 退避只覆盖回执失败路径；webhook 3 次失败后成功落回执仍会 ack，不进入跨轮退避。
