# 162 调优基线复现与失败分类

**Status:** implemented（2026-10-26）

**Claimed by:** zcode session 2026-10-26（调优计划阶段 0）

> 依据：[TUNING_PLAN](../TUNING_PLAN.md) 与 [ADR 0031](../../../docs/adr/0031-interview-feedback-is-the-sixth-reopen-trigger.md) 的 owner policy 覆盖。

## What to build

1. 复核 180 gold（qwen-plus）与 local UX 30 的最新入库产物。
2. 把每条红点归到：意图/工具边界、升级动作、多意图、答案保真、环境。
3. 输出阶段 1 的修复归属与验收建议。
4. 不改代码、不改判据、不调阈值。

## Blocked by

—

## 范围与验收

- 每条红点有 ID、query、期望、实际、失败项。
- 每条红点有根因分类与修复归属。
- 明确复跑边界：没有 key / 当前 demo 档时，不把旧产物说成“已复跑”。
- 阶段 1 优先级有调整依据。

## Non-goals

- 不修代码。
- 不跑 180（本机无有效 key）。
- 不调 gold / 阈值 / 分母。

## Verify

- 阅读 docs/TUNING_BASELINE.md。
- 对照 eval/results 下两份原始产物。

## Handoff notes

**关键决策**

1. 本轮只做**证据复核**，不把“旧产物可归因”说成“已重跑”。
2. 阶段 1 顺序调整为：**先升级动作，再工具准入，再多意图，最后答案保真**。
3. 最重要的新发现：ESC-14 / ESC-18 不是“没识别为 ESCALATE”，而是用户已表达转人工、系统话术也说转，但**没有实际升级动作**；这是信任问题，优先于工具准确率。
4. 物流红点（ACT-LOG-06/09）根因在 T0 意图入口，不在模型；应优先用确定性词表修复。
5. UNKNOWN 三条越界请求都调了 queryOrderDetail，根因是动作工具准入没有“订单上下文/动作证据”门槛。
6. 本地 3B 三条失败意图都对、引用都有，问题是答案没带出 48 小时 + 政策问题被带成要订单号；归入答案保真，不是路由。

**验证落点**

- 180 产物：eval/results/tool-eval-20261020-125346-dev-gold-qwenplus-r7r14.csv
- UX 产物：eval/results/golden-ux-20261024-132519-local-epoch24.json
- 汇总：[TUNING_BASELINE.md](../../../docs/TUNING_BASELINE.md)
- 未重跑原因：本机 .env 无有效 LLM key，当前网关为 demo 档；不消耗未确认额度。
- JVM 基线：684 / 117，未改代码。

**未达成 / 边界**

- 180 gold 与 local UX 30 都**没有本轮重跑**；正式修复前必须在有 key 的环境复跑并刷新基线。
- 当前 180 产物早于 2026-10-26 的 H1-H4 / Agent Run / 复合任务改动；这些改动不改通用模型链路，但不能替代复跑。

**三个现场追问**

1. **为什么不直接修代码？** 基线归因已经显示优先级变了：先修“假转人工”比修工具选择更急。
2. **为什么不跑 180？** 本机无有效 key；拿容器里的旧 key 未经确认计费不合适。
3. **为什么旧产物还能用？** 它足以定位失败类型和代码路径；但所有“修复后是否变好”的结论必须用新复跑证明。
