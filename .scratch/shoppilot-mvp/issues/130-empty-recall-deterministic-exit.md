# 130 空召回确定性出口：政策意图 0 条不再让模型自由发挥

**Status:** implemented

## What to build

政策意图在可见范围内检索到 0 条且未降级时，确定性转人工（`INTENT_UNRESOLVED` + 可查工单 +
本店负标记），不调模型。检查位置在检索之后、组装 Prompt 之前。

- `AgentStateMachine.runModelPath`：`intent.isPolicy() && retrieved.empty() && !degraded()` →
  `fallback(RETRIEVE, INTENT_UNRESOLVED, "可见范围内没有该诉求的政策条款（空召回）")`，
  负标记写本店桶（`lookup.normalizedQuery()` 非空才写）。
- 同批清理：runModelPath 末尾「空召回且未降级」的负标记分支随之成为死代码，只保留
  `degraded` 的 `negative-suppressed` 分支。

## Blocked by

无（独立缺陷修复）。

## 口径

- **首问与复问同口径**：此前首问自由发挥、复问（负缓存）才转人工；现在首问即转人工。
- **降级不落此出口**：`degraded` 的空结果仍走模型，防止把一次中间件抖动固化成批量转人工
  （2026-09-08 mix80 压测的教训）。
- 复用既有 `INTENT_UNRESOLVED` 枚举，**降级枚举 10 / 降级 9 的公开口径不动**。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/verify-store-isolation.py   # 第 3 条断言：T004 问电商问题必须转人工+工单
git diff --check; git status --short
```

## Handoff notes

- **关键决策**：出口放在检索之后、模型之前——省一次模型调用，且让"模型是否听话"不再参与这条
  语义（提示词第 1 条明写「不要编造」，活体 3B 照编「死蟹全额退款」，机器保证只能由代码给）。
- **验证落点**：`GatewayMainPathJvmTest` 新增两条——空召回确定性出口（响应 `fallbackReason`/`ticketId`、
  负标记被写、`verifyNoInteractions(llm)`）与 `degraded` 空结果仍走模型（不写负标记）；
  全仓 `verify` 绿。**变异对照**：把出口条件改成 `false` → 恰一条新用例红（且已给模型站桩答案，
  红的是断言而不是 NPE）。
- **活体**：`python scripts/verify-store-isolation.py` 第 3 条断言（T004 问「死蟹怎么赔付」→
  `INTENT_UNRESOLVED` + 工单号）见票 131 读数。
- **三个现场追问**：
  1. 「确实没有这条政策」的问句现在立即落工单——人工队列会因此变长吗？本机没有多店真实流量，
     该影响**未测量**；若可观，选项是把这类单进 `DEFAULT` 队列并在工作台按 `reason` 过滤。
  2. 180 条 gold 里空召回样本的活体读数未重跑（需 dev 额度）——落地前可否先跑一个"空召回样本
     分桶"脚本从既有 dev 明细里数出影响面？
  3. 该出口对 `POLICY_HOTEL` 在电商租户、`POLICY_*` 在酒店租户都成立——它同时把 round33
     T0 注释里「最终落到转人工」那句话变成真的；要不要在 `docs/CODE_MAP.md` 的隔离面里点名它？
