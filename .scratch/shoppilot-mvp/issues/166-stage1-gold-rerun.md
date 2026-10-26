# 166 阶段 1 收口：180 gold 复跑

**Status:** blocked — 缺有效 LLM key（2026-10-27）

**Claimed by:** zcode session 2026-10-27（等待环境解锁）

> 依据：[TUNING_PLAN](../TUNING_PLAN.md) 阶段 1 Gate、票 162 / 163 / 164 / 165。

## What to build

1. 在有效 dev 模型端点下复跑 180 gold。
2. 核对四个分区：ACTION_LOGISTICS / ACTION_ORDER / ESCALATE / UNKNOWN。
3. 复跑 local UX 30（或至少 G-006 / G-026 / G-029）。
4. 把前后对照写入 EVIDENCE；不到 95% 按失败分类回炉，不调阈值。

## Blocked by

**没有有效的 SHOPPILOT_LLM_API_KEY / 计费额度。**

- 本机 .env 只有占位/空值；容器里虽然有旧 key，但未经所有者确认不拿来计费。
- 180 条约 43 万 token（历史读数），需要明确额度。
- 旧产物是 2026-10-20 的；本轮代码已改 T0 与工具绑定，不能拿旧产物声称“四个分区已达标”。

## 解锁步骤

1. 在 .env 填有效 SHOPPILOT_LLM_API_KEY、SHOPPILOT_LLM_BASE_URL、SHOPPILOT_LLM_MODEL。
2. 把 SHOPPILOT_LLM_DAILY_TOKEN_BUDGET 抬到至少 1,300,000（历史单跑 435,495）。
3. 起 dev 栈后执行：
   - python scripts/run_tool_eval.py --tag stage1-rerun
   - python scripts/run_golden_set.py --base http://127.0.0.1:8082 --tag stage1-local --mode local
4. 对照 eval/results/ 新产物与 2026-10-20 基线。

## 本票已完成的无 key 部分

- 四个修复的代码、JVM、活体证据已分别收口在票 163 / 164 / 165。
- 全量 JVM **697 tests / 117 suites** 全绿。
- 已确认本机 run_tool_eval.py --dry-run 的 scorer selfcheck **16 项通过**。

## Non-goals

- 不拿 demo 档冒充 dev 模型读数。
- 不调 gold / 阈值 / 分母。
- 不为了凑 95% 改判据。

## Handoff notes

**为什么 blocked 而不是继续改代码**

阶段 1 的四个行为修复已完成，但“四个分区到 95%”只能由 180 复跑证明。继续改代码会变成拿猜测定标。

**解除阻塞后的第一件事**

先跑 180，不先改代码。把结果按票 162 的五类失败重新分类，再决定是否回炉。
