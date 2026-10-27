# 166 阶段 1 收口：180 gold 复跑

**Status:** implemented（2026-10-27）

**Claimed by:** zcode session 2026-10-27

> 依据：[TUNING_PLAN](../TUNING_PLAN.md) 阶段 1 Gate、票 162 / 163 / 164 / 165。

## What to build

1. 在有效 dev 模型端点下复跑 180 gold。
2. 核对四个分区：ACTION_LOGISTICS / ACTION_ORDER / ESCALATE / UNKNOWN。
3. 把前后对照写入 EVIDENCE；不到 95% 按失败分类回炉，不调阈值。
4. local UX 30 留给阶段 2（票 167），本票不混入。

## 结果

第一次 180（stage1-rerun，qwen-plus）：

| 分区 | 选对工具 | 填对参数 |
| --- | --- | --- |
| ACTION_ADDRESS | 100% | **93.3%** |
| ACTION_LOGISTICS | **94.4%** | 100% |
| ACTION_ORDER | **88.9%** | 100% |
| ESCALATE | 100% | n/a |
| UNKNOWN | 100% | n/a |
| POLICY_* | 100% | n/a |

token：prompt 508,898 + completion 23,105 = **532,003**。

第一轮修复后第二次 180（stage1-rerun2）：

| 分区 | 选对工具 | 填对参数 |
| --- | --- | --- |
| ACTION_ADDRESS | 100% | **93.3%** |
| ACTION_LOGISTICS | **100%** | 100% |
| ACTION_ORDER | **100%** | 100% |
| ESCALATE | **100%** | n/a |
| UNKNOWN | **100%** | n/a |
| POLICY_* | 100% | n/a |

token：prompt 391,013 + completion 15,510 = **406,523**。

最后一条 ACTION_ADDRESS 失败是 ACT-ADR-06：模型把区县重复进详址（朝阳区建国路1号），执行层已归一化，但评测读的是 trace 里的 modelArgs，归一化发生得太晚。把归一化前移到 trace 之前后，定向复跑 **ACTION_ADDRESS 18/18 = 100%**（stage1-address，prompt 80,666 + completion 3,800 = 84,466）。

## 验收

| 项 | 结果 |
| --- | --- |
| ACTION_LOGISTICS | 100%（第二次 180） |
| ACTION_ORDER | 100%（第二次 180） |
| ESCALATE | 100%（第二次 180） |
| UNKNOWN | 100%（第二次 180） |
| ACTION_ADDRESS | 100%（定向 18/18） |
| 请求失败 | 0 |
| 猜槽位 / 串号 | 0 / 0 |
| JVM | **700 tests / 117 suites** 全绿 |

**边界：** 最后一轮没有重跑完整 180，只跑了 ACTION_ADDRESS 18 条；原因是剩余预算 355,995，完整 180 预计约 40 万。其他 9 个分区在第二次 180 已 100%，归一化改动只影响地址详址，所以用定向分区补齐是诚实且足够的口径。

## 本轮额外修复

1. “能改地址吗”是资格询问，T0 判 ACTION_ORDER，不再误判成写动作。
2. 动作意图 + 政策形状（“坏了怎么赔，先看物流”）不再被 policy-hint 追问短路。
3. 订单进度问法被模型选成 queryLogistics 时，改绑回 queryOrderDetail。
4. 地址详址在 trace 记录前归一化，评测与执行同口径。
5. run_tool_eval.py 的 ops token 默认读 SHOPPILOT_OPS_TOKEN，不再写死 dev-ops-token。

## 提交前检查

- gold / 阈值 / 分母 / judge 零改动。
- 180 使用 qwen-plus / DashScope / dev 模式。
- 总 token：532,003 + 406,523 + 84,466 = **1,022,992 / 1,300,000**。

## Handoff notes

**关键决策**

1. 不调 gold / 阈值；所有失败先归类再改行为。
2. 第二次 180 后只剩 ACT-ADR-06，一条详址格式问题；用 ACTION_ADDRESS 定向 18 条复验，不重烧完整 180。
3. 详址归一化必须在 trace 记录 modelArgs 之前，否则执行与评测两个口径。
4. 订单→订单详情的改绑只认“进度/状态/情况/详情”，避免误伤多订单反思时模型主动改查物流。

**验证落点**

- 180 产物：eval/results/tool-eval-20261027-081748-dev-stage1-rerun2*.csv / meta。
- 定向产物：eval/results/tool-eval-20261027-083504-dev-stage1-address*.csv / meta。
- JVM：700 tests / 117 suites；gateway 模块 verify 单独复跑通过。

**三个现场追问**

1. 为什么最后没重跑完整 180？剩余预算 355,995，完整 180 约 40 万；改动只影响地址详址，定向 18 条已覆盖该分区。
2. 为什么不是模型变聪明了？四个红点靠确定性判定与改绑修复；POLICY/UNKNOWN 仍是 0 模型路径。
3. 怎么防止再误绑？所有改绑都带意图 + 词面双条件，并有多订单反思回归用例。
