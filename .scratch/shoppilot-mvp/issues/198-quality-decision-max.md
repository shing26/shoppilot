# 票 198：质量报表的「最大」改读累计值

Round: 47（spec：[round47-spec-ux-p2-fixes.md](../round47-spec-ux-p2-fixes.md)）
ADR: 0082　Status: implemented　Blocked by: 无

## What to build

1. **累计最大**（`shoppilot-gateway/.../agent/AgentStateMachine.java`）：
   - 新增 `AtomicLong handoffDecisionMaxNanos`；
   - 每次记录 `handoffDecisionTimer` 时同时 `accumulateAndGet(elapsed, Math::max)`；
   - 注册 Gauge `shoppilot_handoff_decision_seconds_max`（值 = nanos / 1e9 秒）。
2. **sidecar 优先读 Gauge**（`quality/QualitySidecarService.java` 的 `timer()`）：
   - `registry.find(name + "_max").gauge()` 存在且 `count > 0` 时用它覆盖
     `maxSeconds`；不存在则回退 `Timer.max()`；
   - `count` / `meanSeconds` 保持原口径。
3. **测试**（`QualitySidecarServiceTest`）：
   - gauge 值覆盖会衰减的 `Timer.max()`；
   - gauge 缺失时仍可用 `Timer.max()`，不抛错；
   - 无流量时 `maxSeconds == 0`。
4. **登记**：README 的源码口径指标名 72 / 74 → **73 / 75**，并在本票 Handoff 与
   EVIDENCE 写明新增的是 Gauge 名、既有指标未动。

## Verify

`bash ./mvnw -B -ntp verify -Dspring-boot.repackage.skip=true`（本机 fat jar 被运行中的服务锁住，
repackage 必跳）；`git diff --check`、`git status --short`。

## 验收项

- 质量报表在空闲一段时间后 `maxSeconds` 仍非 0（只要本进程记录过样本）。
- 累计最大只增不减；同一进程重启后归零是预期。
- 无流量时仍报 0，不伪造读数。
- 新增 2-3 条 JVM 用例，既有 771 条不回归。

## Handoff notes

**关键决策**

- 根因已用 `javap` 钉死：`io.micrometer.core.instrument.cumulative.CumulativeTimer.max()` 委托 `TimeWindowMax.poll(unit)`，所以空闲一个时间窗就归零；`mean()` 是累计值。调大时间窗只是把「永远 0」推迟成「偶尔 0」，不修根因。
- 累计最大用 `AtomicLong.accumulateAndGet(elapsed, Math::max)` + `Gauge` 单独暴露，不改 `Timer` 的桶与 SLO；`count` / `meanSeconds` 口径一字未动。
- Gauge 名 `shoppilot_handoff_decision_seconds_max` 是**新增的指标名**，不是 `Timer.max()` 的别名；sidecar 读不到 Gauge（老注册表、测试替身）时回退 `Timer.max()`，不抛错。
- Gauge 值是进程内累计，重启归零；这是「最大一次」的正确语义，不跨进程补历史。

**验证落点**

- `bash ./mvnw -B -ntp verify -Dspring-boot.repackage.skip=true`：BUILD SUCCESS，四模块 **12 / 92 / 619 / 50 = 773 tests / 129 suites**、0 失败；gateway 617→619。
- 新增用例：`QualitySidecarServiceTest.prefersCumulativeMaxGauge`（Gauge 0.75s 覆盖 `Timer.max` 0.25s）、`fallsBackToTimerMaxWhenGaugeMissing`（无 Gauge 时 0.25s）；`reportsZeroesWhenNoTraffic` 补 `maxSeconds == 0.0`。
- 指标名复点：`grep -rhoE "shoppilot_[a-z0-9_]+" .../src/main | sort -u` → 三模块 **73**、四模块 **75**；README 与 CODE_MAP 已换代，既有指标一个未动。
- **未实跑**：没有起 8082 网关，未在真实 `/api/v1/support/ops/quality/report` 上观察「空闲一段时间后 maxSeconds 仍非 0」；时序衰减的现场复现停留在 `javap` + 单元测试这一档。

**三个现场追问**

1. 质量报表的 `scope` 仍写 `process-local-cumulative`，但现在 `mean` 与 `max` 的累计语义并不完全一致（mean 是 Timer 累计、max 是 Gauge 累计）；要不要把 `maxSeconds` 单独标成 `sinceProcessStart`？
2. 其他用 `Timer.max()` 的地方（embedding 延迟、LLM 延迟等）有同样的时间窗衰减；是否统一走「累计 Gauge + sidecar 优先读」的模式，还是只修被点名的这一处？
3. 进程重启后累计最大归零，报表上无法区分「还没发生过」与「重启后还没发生」；要不要在报表里带 `startedAt` 或进程启动时长作为读数的上下文？
