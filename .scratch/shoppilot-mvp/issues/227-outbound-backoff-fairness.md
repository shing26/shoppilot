# 票 227：出站跨轮退避与 PEL 公平读取

Round: 57（回归修复；按 ADR 0031）　
ADR: 0087 §4（跨轮补齐，不改结论）　
Status: in-progress（2026-11-23 所有者指令实现）　Claimed by: root　Blocked by: 无

契约：[plans/227-outbound-backoff-fairness.md](../../plans/227-outbound-backoff-fairness.md)（2026-11-23 冻结）

## What to build

1. 给 `OutboundDeliveryService` 增加跨轮退避：`handle()` 返回 `false` 时按事件记录
   失败轮次与下次可重试时间；未到期时跳过且不 ack，到期后重新投递。
2. 每轮分别读取 PEL（`0`）与新消息（`>`），各自最多 `BATCH`，按 `RecordId` 去重；
   PEL 满 `BATCH` 时新消息仍在本轮被处理。
3. 新增 `RoundBackoffPolicy`，默认 5s / ×2 / 5m / ±20% 抖动，全部可配置。
4. 补窄测：退避窗口内不重试、到期重试、PEL 满时新消息仍处理、同轮去重、
   跨轮状态有界、既有 ack 语义不回归。

## 边界

- 不改单次投递内的 `RetryPolicy`，不改 ack 语义、gold、阈值或覆盖率门槛。
- 不退避后丢弃事件；不新增生产依赖；不动飞书连接、EgressPolicy、身份或前端。
- 进程内退避，不承诺跨进程/多实例共享；重启后立即重试一次，登记为边界。

## Verify

```powershell
.\mvnw.cmd -B -ntp -pl shoppilot-gateway -am -Dtest=OutboundDeliveryTest,RoundBackoffPolicyTest test
.\mvnw.cmd -B -ntp verify
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
git diff --check
git status --short
```

活体（本机栈可用时）：

```powershell
$env:SHOPPILOT_GATEWAY_BASE = "http://127.0.0.1:8082"
node scripts/verify-channel-hot-reload.mjs
```

## Handoff notes

（实现与独立 Verifier 完成后补：关键决策、验证落点、三个现场追问）
