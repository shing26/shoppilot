# 119 `POLICY_HOTEL` 四处同步 + JVM 用例

**Status:** implemented

## What to build

新增共享枚举值 `Intent.POLICY_HOTEL` 并把「加一个意图要同步哪四处」落到实处。

- `shoppilot-tool-api/.../tool/Intent.java`：新增 `POLICY_HOTEL`；缓存分区（`cacheAdmissible()` 走
  `isPolicy()`）与工具裁剪（`isAction()`）自动生效。
- `T0RuleLayer`：新增 `HOTEL_WORDS` 与分支，**放在实体 / 第一人称判定之前**——否则「我订的房能
  免费取消吗」命中 `POSSESSIVE` 判成 `ACTION_ORDER`，挂上全套电商工具并被踢出缓存准入。
- `MarkdownChunker`：`hotel-` 前缀 → `POLICY_HOTEL` 入库映射。
- `intent-samples.json`：加 `POLICY_HOTEL` 样例句（质心由样例句现场算，不写死向量）。
- JVM 用例：`T0RuleLayerTest`（酒店词表优先于第一人称 / 无酒店词时回落到动态 / 酒店意图可准入缓存）、
  `MarkdownChunkerTest`（`hotel-` 映射且租户受限）。

## Blocked by

[117](117-round32-adr-and-spec.md)。

## 口径

- **工具挂载的机制**以 `AgentStateMachine.toolsFor` 为准：政策意图与 `UNKNOWN` 都不按动作意图下发
  工具，只有 `isAction()` 才挂 `actionDescriptors()`（注释与实现对齐，不写未经核实的机制）。
- 判据面零改动；限流统计按意图计数自动生效。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/verify_eval_judge.py
git diff --check; git status --short
```

## Handoff notes

- **关键决策**：酒店域是**纯咨询**，所以 T0 词表硬优先级放在第一人称之前是必须的（不是顺手）——
  代价是将来真做酒店办理域时必须重排这条顺序，照登。
- **「四处同步」的核实更正**：ADR 0007 把「限流统计」列为共享枚举的四处之一，但按仓核实**这一处
  不成立**——`ratelimit/RateLimitService` 的键是租户 + 买家/IP，没有意图维度，也没有 `intent`
  tag 的 meter。`POLICY_HOTEL` 实际需要同步的是：**缓存分区（`isPolicy()` 自动）/ 工具裁剪
  （`isAction()` 自动）/ 检索过滤（`HybridRetriever` 按意图）/ 评测集（新增分区）**。本轮**不
  声称限流统计自动生效**；ADR 0066 决策 8 已照登这是既有文档缺口，不改 ADR 0007 原文。
- **验证落点**：`T0RuleLayerTest`（酒店词表优先 + 回落 + 缓存准入）、`MarkdownChunkerTest`
  （`hotel-` 映射）；`.\mvnw.cmd -B -ntp verify` 绿。
- **三个现场追问**：
  1. 酒店词表与电商四条政策词表**零重叠**是本轮选的；若将来做酒店办理域，词表与管理工具如何共处？
  2. `POLICY_HOTEL` 进 `intent-samples.json` 后，T1 质心集合从 9 变 10——标定重跑（票 121）前，
     旧阈值是否可能让某条电商样本漂到酒店质心？
  3. 限流统计按意图计数的口径是否需要为「非电商意图」单列一栏？
