# 票 227：出站跨轮退避与 PEL 公平读取

Round: 57（回归修复；按 ADR 0031）　
ADR: 0087 §4（跨轮补齐，不改结论）　
Status: implemented（2026-11-23 独立 Verifier PASS；待所有者签收）　Claimed by: root　Blocked by: 无

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

### 关键决策

- **跨轮退避只在 `handle()==false` 这一支生效**：也就是回执工单没落库、事件必须留在
  PEL 的路径。`RetryPolicy` 的单轮 3 次曲线（500ms / ×2 / 5s / ±20%）一字未改；
  新增 `RoundBackoffPolicy` 管跨轮时间轴，默认 5s / ×2 / 5m / ±20%，四个键均可配置。
- **退避状态按 `eventId` 记录**（无 eventId 时用 `RecordId`），存在单线程消费者的
  有界 `LinkedHashMap`（最多 4096 条，最旧淘汰）；成功 ack 后清除。网关重启后状态清空，
  第一条 pending 会立即重试一次——这是显式取舍，不冒充跨进程退避。
- **PEL 与新消息拆成两次独立读取**：PEL `0` 与新消息 `>` 各最多 `BATCH=64`，按
  `RecordId` 去重。旧实现「PEL 读满 64 就不读新消息」被删掉；PEL 满时新消息仍在
  本轮进入处理，单轮最多 128 条候选。
- **不把退避做成丢弃**：未到期只跳过、不 ack；事件一直留在 PEL，直到回执落库。
  退避期间日志从每轮 WARN 降为一条 INFO + 命中时的 DEBUG，避免日志风暴。

### 验证落点

- 窄测：`.\mvnw.cmd -B -ntp -pl shoppilot-gateway -am "-Dtest=OutboundDeliveryTest,RoundBackoffPolicyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  → 首轮 **18/18 绿**；Verifier P1 整改后 **19/19 绿**（OutboundDeliveryTest 16、RoundBackoffPolicyTest 3）。
- 全量：`.\mvnw.cmd -B -ntp verify` → **BUILD SUCCESS**，四模块
  `12 + 98 + 718 + 50 = 878 tests / 144 suites`，0 失败。
  （首次全量被旧 biz-mock 进程占用 jar 挡住，`repackage` 无法重命名；停掉本项目旧进程后重跑成功。
  外部 OpsPilot `:8081` 未动。）
- 文档真值：`gen_status.py --check` → `STATUS ok`；`verify_doc_truth.py` →
  `877 tests / 144 suites` 一致；`check_doc_links.py` → 119 文件 / 234 链接；
  `git diff --check` 退出 0。
- 活体：未跑 `verify-channel-hot-reload.mjs`（需要 gateway + Redis + 演示口令的本机栈）；
  按契约留待独立 Verifier 判断是否具备条件，不具备则登记边界，不声称 PASS。

**首轮独立 Verifier FAIL 的整改（2026-11-23，HEAD `f476af4`）**

- P1-1 退避时钟：`nextAttemptAt` 从 drain 开始时刻改为**失败时刻**（`failedAt`），
  避免长轮次尾部事件窗口已过期。
- P1-2 抖动封顶：先加抖动再 `min(..., max)`，实际延迟不再超过 `max`；
  `jitterStaysWithinConfiguredBand` 断言改为 500–1000ms。
- P1-3 状态有界：`LinkedHashMap` 改为插入序（最旧淘汰），新增可注入上限的测试构造器，
  补 3 条事件、上限 2 的淘汰测试（最旧重试、较新仍退避）。
- P1-4 ack 断言：退避窗口内与失败轮均断言 `never().acknowledge`；已投递跳过断言 ack 一次。
- P1-5 归一化测试：用正 base/max 重写，`factor<1` 断言不回落到 5s，`jitter>1` 断言
  10s 基数的抖动上限仍是 20s。

**独立 Verifier 复验 PASS（2026-11-23，HEAD `3712c9f`）**

- 绑定：分支 `codex/227-outbound-backoff-fairness`，HEAD
  `3712c9ff00365b9728052f6884aa85b065dff45d`；复验前后 `git status --short` 为空。
- 亲跑窄测 **19/19**（OutboundDeliveryTest 16 + RoundBackoffPolicyTest 3），
  全量 **12 + 98 + 718 + 50 = 878 tests / 144 suites**、0 失败；`STATUS ok`、
  `verify_doc_truth` 878/144、`check_doc_links` 119 文件/234 链接、`git diff --check` 全绿。
- AC1–AC8 全 **PASS**；P0/P1 均为 **无**。复验特别确认：退避窗口内不 HTTP/不写回执/
  不 ack，到期重试；PEL 64 条时新消息同轮处理；同 RecordId 只处理一次；最旧状态被淘汰、
  较新条目仍退避；已投递跳过仍 ack；坏消息 ack 不投递；回执 empty/异常不 ack。
- 边界：活体 `verify-channel-hot-reload.mjs` 未跑（8082/8091/8092 未监听、演示口令未设置），
  按环境边界登记，未声称 PASS；Redis 16379 在但不构成全栈。

### 三个现场追问

1. 跨轮退避表是进程内状态；多实例共享退避需要 Redis 协调，本票不做。若网关频繁重启，
   pending 事件会立即重试一次，可能比稳态更早打到下游。
2. 单轮先处理 64 条 PEL 再处理新消息；如果 64 条都不在退避窗口内，新消息虽然被读取，
   仍会排在这 64 条之后。要彻底消除队头延迟，需要按处理预算交错取数，另票处理。
3. 退避上限 5 分钟意味着 ticket 服务长时间不可用时，回执重试最慢 5 分钟一次；
   若业务要求更快恢复，调小 `SHOPPILOT_OUTBOUND_ROUND_BACKOFF_MAX` 即可。
