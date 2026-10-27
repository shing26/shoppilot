# 168 多意图退款优先级：显式退款压过物流查询

**Status:** implemented（2026-10-27）

**Claimed by:** zcode session 2026-10-27

> 发现于票 167 补验 180：ACT-RFD-13 稳定复现，非模型方差。

## 问题

查询：90002 到哪了？太慢了直接给我退款

- T0 判 ACTION_REFUND；
- 模型却先选 queryLogistics，回复“签收后不支持仅退款”，退款没有执行；
- gold 期望 applyRefund；
- 定向复跑 ACTION_REFUND 18 条仍 94.4%，失败同一条。

## 修复

在 bindToolToIntent 增加退款优先改绑：

- intent == ACTION_REFUND；
- 模型选 queryLogistics（只收窄到物流查询，不动 queryOrderDetail 轮次）；
- query 是显式退款请求（退款/退钱 + 直接/给我/申请/我要/马上/立刻）；
- 改绑 applyRefund，参数沿用 orderNo（reason/amountFen 可选）。

## 结果

| 项 | 修复前 | 修复后 |
| --- | --- | --- |
| ACTION_REFUND | 17/18 = 94.4% | **18/18 = 100%** |
| ACT-RFD-13 | queryLogistics | applyRefund |

产物：eval/results/tool-eval-20261027-110143-dev-refund-168b*.csv / meta。

## 验证

- ACTION_REFUND 定向 18/18 = 100%（最终代码，tag refund-168b）。
- JVM 全量 verify 绿（702 tests / 117 suites）。
- 未改 gold / 阈值 / 分母。

## Handoff notes

**关键决策**

1. 只改绑 queryLogistics，不改 queryOrderDetail；后者在“申请退款”预算耗尽测试里是有意连跑四轮读工具，不能被改成写动作。
2. 显式退款词表要求“退款/退钱 + 直接/给我/申请/我要/马上/立刻”；“能退款吗”不触发。
3. 改绑后仍走既有幂等 / HITL / 审计，不绕过写工具风险面。

**验证落点**

- 单测：GatewayMainPathJvmTest#explicitRefundRebindsAwayFromLogisticsLookup（captor 断言 applyRefund）。
- 活体：ACTION_REFUND 18/18，产物 refund-168b。

**三个现场追问**

1. 为什么不改 T0？T0 已经正确判 ACTION_REFUND，错在模型选读工具，改绑定层最小。
2. 为什么只改物流？改订单详情会影响预算耗尽测试的“四轮读工具”语义；物流是实测失败形态。
3. 会不会把“先查物流再退款”误改？只有显式“直接/给我/申请/我要/马上/立刻”才触发。
