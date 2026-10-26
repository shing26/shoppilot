# 163 显式升级独立裁决

**Status:** implemented（2026-10-27）

**Claimed by:** zcode session 2026-10-27（调优阶段 1，票 162 之后）

> 依据：[TUNING_PLAN](../TUNING_PLAN.md)、[TUNING_BASELINE](../../../docs/TUNING_BASELINE.md)、票 162。

## What to build

1. 让口语化“要真人 / 要拍板 / 机器办不了就叫人 / 没个活人”在 T0 直接定案为 ESCALATE。
2. 裁决必须发生在工具选择与模型之前，不依赖最终 intent。
3. 命中后走既有 USER_REQUESTED 出口，真实落可查工单；不进入工具/模型。
4. 新增否定反例，避免“别叫人”“不想找真人”被误判。

## Blocked by

—

## 范围与验收

- ESC-03 / 05 / 14 / 18 对应的四句话在 T0 定案为 ESCALATE。
- 否定形式不触发。
- 主链路测试：真实 T0 结果 → fallback USER_REQUESTED → 工单号返回 → 模型/工具零调用。
- 不改 gold、阈值、分母、FallbackReason 枚举。

## Non-goals

- 不重写升级系统。
- 不改情绪门优先级。
- 不做工具准入（票 164）。
- 不重跑 180（本机无有效 key，按票 162 边界登记）。

## Verify

- T0RuleLayerTest / GatewayMainPathJvmTest
- ./mvnw.cmd -B -ntp verify（PATH 需前置 JDK 21）
- 活体：demo 档发四句话，检查 fallbackReason=USER_REQUESTED 与 ticketId

## Handoff notes

**关键决策**

1. 不新增第二套升级系统：T0 的显式升级谓词本来就是“先于实体、动作、行业门”的独立裁决；缺的是四个口语表达。
2. 只加带上下文的短语（真人给我 / 找真人 / 要真人 / 没个活人 / 拍板 / 叫人），不加裸“真人/活人”，否则否定句会误判。
3. “没个活人”必须整短语收录：裸“活人”会被紧邻否定词“没”拦掉。
4. 命中后仍走既有 USER_REQUESTED 出口，不新开 fallback 分支；工单来源仍是 DEGRADE。
5. 测试同时钉 T0 判定与主链路落单，避免只测词表或只测 mock。

**验证落点**

- 定向：T0RuleLayerTest + GatewayMainPathJvmTest，**87 条全绿**。
- 全量：**689 tests / 117 suites** 全绿（新增 5 条，gateway 546 → 551）。
- 活体（demo 档，真实 HTTP）：`转人工` + 四句目标表达共 **5/5** 返回
  `fallbackReason=USER_REQUESTED` 与工单号；五张工单均可按买家身份查回，
  `source=DEGRADE`、`reason=USER_REQUESTED`、状态 `OPEN`。
- 工单号：`T1793036235337-0`（基线“转人工”）、`T1793036235892-1`（真人给我）、
  `T1793036235940-2`（拍板）、`T1793036235981-3`（叫人）、`T1793036236044-4`（没个活人）。

**未达成 / 边界**

- 180 gold 未重跑（本机无有效 key）；票 162 已登记。
- 只覆盖四个失败表达对应的口语类型，不承诺覆盖所有自然语言升级说法。

**三个现场追问**

1. **为什么不另写升级检测器？** T0 已是独立、0 token、先于模型的入口；再加一层会制造两个真源。
2. **为什么不用裸“真人”？** “我不想找真人”会被判成升级；只收上下文短语能同时保住召回与否定。
3. **为什么先修这个？** 用户要真人、系统只说不做，是信任问题，比工具选错更严重。
