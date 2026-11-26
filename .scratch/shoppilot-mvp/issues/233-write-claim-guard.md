# 票 233：动作意图忠实性守卫（未派发写工具不得声称已办理）

Round: 62（产品行为回归修复；ADR 0008 / ADR 0031）
ADR: 0008（不猜不骗）、0031（回归修复）、0009（降级原因可枚举）
Status: blocked（2026-11-27 冻结契约；Blocked by 票 231 合并）　Claimed by: —　Blocked by: 231

契约：[plans/233-write-claim-guard.md](../../plans/233-write-claim-guard.md)（2026-11-27 冻结）

## What to build

`PlanExecuteHandler` 对写意图只纠偏一次；模型第二次仍不调工具时，正文被当最终答案。
本地 3B 实测会输出「我已为您发起退款申请」而业务库无退款单（5 次采样 4 次）。

在 `expectedWrite != null && !expectedWriteDone` 且已纠偏的收尾点加一道判定：

1. 正文在追问缺失参数 → 原样返回。
2. 正文声称已办理 → 走 `TOOL_ROUNDS_EXHAUSTED` 降级 + 工单，正文不得透出。
3. 其余 → 原样返回。

## 边界

- 不新增/删除降级枚举，不改 `FallbackReason` 文案与计数口径。
- `expectedWriteDone`（工具真的成功派发）仍是唯一「办成了」判据；正文只是触发器。
- gold / 阈值 / 分母 / `judge()` / 既有测试断言零改动。

## Verify

见契约 AC1–AC8；活体 10 次采样要求「零谎报」。

## Handoff notes

**开票时事实（2026-11-27）**

- 触发证据：`logs/acceptance-run-20261126-043649.log` 的 `refund` / `console`；
  5 次采样 1 次 `applyRefund` 成功；失败样本 trace 只有
  `corrective=applyRefund`，无 `applyRefund=` 行。
- 现有代码：`PlanExecuteHandler` 的 `nudged` 分支直接 `break`；
  `unresolvedToolFailure` 只覆盖读工具③类失败。
- 与 R14-A 的区别：R14-A 修的是「读工具失败被吞成普通回答」；本票修的是
  「写动作根本未派发却被正文冒充完成」。
- 编号：原拟 232，与并发任务的 `232-commerce-kernel-promotion` 冲突，改用 233。

**现场追问（三条）**

1. 完成声明词表按「已为您/已帮您 + 发起/提交/办理/完成/退款/取消/修改/成功」
   起步，是否接受这种有界词表？漏判的兜底是人工复核，不是再放宽。
2. 复用 `TOOL_ROUNDS_EXHAUSTED` 会让该计数桶混入「写动作未执行」；
   是否接受，还是另开票加独立 reason/metric（要同步 README 计数）？
3. 如实拒绝（「抱歉，这个动作我办不了」）不带问号也不带完成词时原样透出；
   是否要求这类也转人工（更保守，但会把正常拒答也变成工单）？
