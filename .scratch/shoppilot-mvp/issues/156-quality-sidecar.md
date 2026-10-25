# 156 Quality Sidecar：只读编排质量报表与告警

**Status:** implemented（2026-10-25）

**Claimed by:** zcode session 2026-10-25（ADR 0072 H4）

> 依据：[ADR 0072](../../../docs/adr/0072-agent-handoff-graph-and-role-kernel.md)、
> [ORCHESTRATION_PLAN](../../../docs/ORCHESTRATION_PLAN.md) H4。

## What to build

1. 只读 sidecar 报表 `GET /api/v1/support/ops/quality/report`：
   - handoff 计数 / blocked 分布 / blocked 比例 / decision 耗时
   - tool risk 尝试分布
   - fallback 总量与比例
   - friction 指标：工具成功但仍降级、重复追问槽位
2. 请求链路只补零 LLM 观测指标：
   - `shoppilot_handoff_blocked_total{from,to,reason}`
   - `shoppilot_handoff_decision_seconds`
   - `shoppilot_quality_tool_success_fallback_total`
   - `shoppilot_quality_slot_asks_repeated_total`
3. Prometheus 告警与 promtool 合成序列测试：
   - blocked handoff > 0
   - repeated slot ask > 0
4. sidecar 不改路由，不新增 LLM 调用。

## Blocked by

—

## 范围与验收

- `GET /ops/quality/report` 需要 ops 凭证；买家不能读。
- 报表指标缺失时返回 0，不猜数据。
- Prometheus 规则该响/不该响两侧测试。
- JVM verify 全绿；180 条 gold 零改动。

## Non-goals

- 不自动改路由。
- 不做实时质检 Agent。
- 不引入 MCP。
- 不给请求链路加模型调用。

## Handoff notes

**关键决策**

1. Sidecar 是只读报表，不是第二个路由器：不改角色、不落工单、不消费后自动处理。
2. 指标侧回答 Handoff / tool risk / friction；Redis audit 侧回答最近 15 分钟坐席动作。
   时间窗口径分两层：audit 在 sidecar 内算，Handoff / friction 交给 Prometheus rate/increase。
3. Redis 不可用时 `audit.available=false`，metrics 部分照常输出；不把“存储不可用”伪装成 0 流量。
4. 报表需要 `X-Ops-Token`；缺失凭证 403。
5. 告警只报异常模式，不做自动修复。触发后续 ADR 的证据由报表/告警读数登记，不在本票自动开轮。

**观测口径（评审收口）**

1. `shoppilot_handoff_decision_seconds` 是显式毫秒桶 histogram：1/5/10/25/50/100/250ms。
   from/to/reason 都是封闭枚举，不用自由文本标签。
2. `shoppilot_quality_tool_success_fallback_total` 埋在 fallback 出口，读**本请求已有 trace** 中的
   `TOOL_EXEC=OK / IDEMPOTENT_REPLAY / PENDING_APPROVAL`；不维护 per-request 额外状态，也不反查
   ToolDispatcher。counter 是进程内递增，没有远程调用。
3. `shoppilot_quality_slot_asks_repeated_total` 带 `phase` 与 `sameSlot`：
   - `phase=same_role`：session 尚无 handoff trail，重复追问大概率是买家补充/改主意，不告警；
   - `phase=cross_handoff,sameSlot=true`：Handoff 后仍重复问同一格，另计
     `shoppilot_handoff_context_loss_total{slot}` 并进入告警。
4. Redis audit 报表窗口 15 分钟，结果在 sidecar 内 30 秒缓存；轮询不会每次打到 Redis。
   读超时沿用网关 Redis client 的 `spring.data.redis.timeout=2s`，不是独立 200ms——这是刻意保留：
   sidecar 是人工排查面，不在请求路径上，避免为一次低频读再造一套 Redis client。
5. 报表只输出 audit **动作名计数**，不输出坐席 ID / actor；`X-Ops-Token` 复用运维面既有开关与轮转机制。

**验证落点**

- `mvnw verify` → 四模块 **679 tests / 116 suites** 全绿。
- `QualitySidecarServiceTest` 3 条：空注册表零值、metrics 聚合（含 phase / context-loss 分布）、Redis audit 15 分钟窗口与 30 秒缓存。
- `QualitySidecarControllerTest` 2 条：无凭证 403、ops 凭证可读报表。
- CI 新增独立 `Handoff golden set` job，Actions 列表可直接定位 ADR 0072 判据失败。
- Prometheus 新增 3 条质量告警，promtool 测试该响/不该响两侧均已入库；本机未装 promtool，以 CI 门禁为准。
- 180 条 gold 零改动。

**三个现场追问**

1. **为什么 sidecar 不直接改路由？** 质检是发现模式，不是逃生门；自动改路由会把观测器变成第二套策略引擎。
2. **为什么 audit 只算 15 分钟？** 审计流 MAXLEN=10k，只承诺“近期查证”；长窗口应走 biz-mock 审计表或 Prometheus。
3. **为什么 tools success 后 fallback 也告警？** 工具拿到结果但买家仍降级，说明答案表达 / 预期管理有问题；这类模式人工看报表比自动调 prompt 可靠。
