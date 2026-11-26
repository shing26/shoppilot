# 票 233：动作意图忠实性守卫（未派发写工具不得声称已办理）

Round: 62（产品行为回归修复；ADR 0008 / ADR 0031）
ADR: 0008（不猜不骗）、0031（回归修复）、0009（降级原因可枚举）
Status: implemented（2026-11-27；Verifier 两轮 P1 已整改，待复验）　Claimed by: codex/233-write-claim-guard　Blocked by: 231（已合并）

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

**实现与验证（2026-11-27，代码 HEAD `034d4ce`）**

- 新增 `WriteClaimGuard` 纯函数，判定顺序是
  **已完成声明 → 强承诺（先于追问时）→ ASK → REFUSAL → 一般承诺 → 弱确认**：
  已完成声明优先，避免「已为您发起退款，请问还需要其他帮助吗？」因为带问号被放行；
  强承诺按位置优先（「我将直接为您发起……请告诉我订单号」拦下，
  「请提供订单号，我这就为您提交」仍按追问放行）；如实拒绝（「无法为您发起」）
  与否定句（「尚未为您办理退款」）先于承诺排除。初版词表里的裸词
  「多少 / 是否 / 什么时候」已剔除——「退款已提交，是否到账以银行为准」
  这类完成声明此前会被误放行。
- **独立 Verifier 首轮 P1 已整改**：①「完成声明 + 礼貌问句」曾被 ASK 无条件
  放行；② CLAIM 词表漏了「已为您完成/退款/取消/修改」。修复后补了
  「已为您完成退款」「已为您退款」「已为您取消订单」「已为您修改收货地址」
  「我将直接为您发起……请告诉我订单号」「尚未/还没有为您办理」等 0-token 断言，
  AC1 的 handler 用例也改用「完成声明 + 问号」形态。
- **独立 Verifier 第二轮 P1 已整改**：① 裸完成声明
  （`已退款` / `已完成退款` / `已完成` / `已取消` / `已处理完毕` /
  `已给您退款` / `已经给您退款` / `已经替您退款` / `已向您退款` /
  `已为您全额退款` / `已为您原路退回` / `已为您加急处理`）漏判；
  ② 强承诺 / 条件句误伤拒绝与政策说明（「我办不了……我会为您说明退款政策」
  「退款成功后款项会原路退回」「提交成功后系统会通知您」）。
  修复：补裸声明与「已给您/已向您/已经给您/已经替您」前缀；
  强承诺忽略「说明/解释/查询/核实/确认/政策/规则/流程」等非动作语境；
  「成功后 / 如果… / 若…」按条件句排除。
- `PlanExecuteHandler` 只在 `expectedWrite != null && !expectedWriteDone && nudged`
  且正文为 `CLAIM` 时收口：`TOOL_ROUNDS_EXHAUSTED` + 可查工单，正文不透出，
  trace 增 `write-claim-blocked write-pending=<tool>`；`ASK` / `NONE` 原样返回。
  未新增降级枚举、未改 `FallbackReason` 文案、gold / 阈值 / 分母 / `judge()` 零改动。
- 窄测 **14/14**（`PlanExecutionTest` 11 + `WriteClaimGuardTest` 3）；全量
  `.\mvnw.cmd -B -ntp verify` **BUILD SUCCESS，885 tests / 145 suites**
  （tool-api 12 + biz-mock 98 + gateway 725 + ticket 50）。
- **AC7 活体**（HEAD `034d4ce`，local `qwen2.5:3b`，每轮先 reset demo、新会话、
  新幂等 token）：**7 `PENDING_APPROVAL` + 1 `TOOL_ROUNDS_EXHAUSTED` + 工单
  + 2 次合法追问、0 次无 tool 事件却声称已办理**
  （`logs/ac7-233-20261127j.log`，本机 gitignored）。两次追问分别是
  「请问收件人姓名和手机号？」与「请告诉我订单号，我来帮您处理」，
  属 AC2 明确允许的缺失参数追问，不是完成声明。累计活体读数：
  i 轮 **8 PENDING + 2 DEGRADED + 工单、0 谎报**（降级分支两次）；
  h 轮 **10/10 PENDING、0 谎报**；e 轮 **9 PENDING + 1 DEGRADED、0 谎报**。
- 边界：活体每轮都会 reset demo，但未清 Redis 回放索引，以新幂等 token 规避；
  早期三轮采样因问句无空格、订单不存在、trace 大小写匹配错误作废，未计入读数。
- 文档检查：`python scripts/gen_status.py --check`、`python scripts/verify_doc_truth.py`、
  `python scripts/check_doc_links.py`、`git diff --check` 全绿。
