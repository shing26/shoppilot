# 阶段 3：模型分级与成本 —— 离线路由表

**状态：** 离线评估完成（2026-10-27）。**未改线上**。

> 依据：[TUNING_PLAN](TUNING_PLAN.md) 阶段 3。
> 原始产物：dev `eval/results/tool-eval-20261027-104110-dev-dev-167.csv`；local `eval/results/tool-eval-20261027-114424-local-stage3-local180b.csv`。

## 一、方法

同一套 180 gold、同一知识库 epoch 28、冷缓存：

- dev：DashScope `qwen-plus`；
- local：Ollama `qwen2.5:3b`（CPU）；
- 通过口径：`checks_ok=true`（与正式 eval 一致）；
- token：只统计该次请求的 prompt + completion，不混入日预算口径。

> local 的 token 数由 Ollama 侧统计，绝对值与 qwen-plus 不可直接相加；本节比较的是“每组被路由到云端时省下的 qwen-plus token”。

## 二、逐意图结果

| 意图 | n | dev 通过 | local 通过 | dev token | local token | dev 平均延迟 | local 平均延迟 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ACTION_ADDRESS | 18 | 100.0% | 83.3% | 80,776 | 50,318 | 5.8s | 8.6s |
| ACTION_LOGISTICS | 18 | 100.0% | 94.4% | 84,690 | 34,865 | 6.0s | 5.2s |
| ACTION_ORDER | 18 | 100.0% | 83.3% | 81,821 | 38,340 | 5.4s | 5.4s |
| ACTION_REFUND | 18 | 94.4% | 50.0% | 72,472 | 53,559 | 4.6s | 4.7s |
| ESCALATE | 18 | 100.0% | 100.0% | 7,004 | 0 | 0.8s | 0.9s |
| POLICY_FRESH | 18 | 100.0% | 100.0% | 44,681 | 35,929 | 4.8s | 15.4s |
| POLICY_PROMO | 18 | 100.0% | 100.0% | 43,933 | 35,171 | 4.5s | 14.8s |
| POLICY_RETURN | 18 | 100.0% | 100.0% | 41,305 | 34,768 | 4.3s | 14.7s |
| POLICY_SHIPPING | 18 | 100.0% | 100.0% | 40,643 | 32,400 | 3.6s | 13.7s |
| UNKNOWN | 18 | 100.0% | 100.0% | 37,050 | 27,261 | 2.1s | 11.4s |

合计：dev 534,375 token / local 342,611 token；请求失败 0。

## 三、路由建议

| 组 | 建议路由 | 质量依据 | 代价 |
| --- | --- | --- | --- |
| ESCALATE | T0 / 确定性出口（无模型） | 100% vs 100% | 无 |
| POLICY_* + UNKNOWN | local 3B | 六组全部 100% vs 100% | 延迟 3.3s → 10.1s（约 3x） |
| ACTION_* | dev qwen-plus | local 仅 50–94.4%，动作参数不可接受 | 保留云端 token |

### 收益

- 政策组（5 个 POLICY + UNKNOWN + ESCALATE，共 126 条）从 dev 切到 local：
  - 省下 qwen-plus **214,616 token / 180 条**；
  - 占当前 dev 总 token 的 **40.2%**；
  - 质量 100% → 100%，无失败新增。
- 剩余动作组 72 条仍需 dev，约 319,759 token。

### 风险

1. **延迟**：政策组平均 3.3s → 10.1s，单条最慢 15.4s；若产品 SLA 是 5s，不能直接全量切。
2. **本地容量**：qwen2.5:3b CPU 单并发延迟高，并发上来会排队；需要压测后再定。
3. **动作质量**：local 在 ADDRESS/ORDER/REFUND 上明显掉点，不能为了省 token 把动作交给 3B。
4. 本表是 180 gold 单集；UX30 local 30/30 只能作旁证，不能替代线上分布。

## 四、下一步

1. 若接受 10–15s 政策延迟：开票实现“按意图路由”配置，默认关闭，先影子模式只记录不改变执行；
2. 若 SLA 不允许：先测 qwen2.5:7b / 更小量化模型，或只在低峰/异步政策解读使用 local；
3. 任何上线前用同一 180 + UX30 复跑，质量门槛不降。

> 本阶段结论只到“路由表可算、收益可测”，没有改任何线上路由代码。
