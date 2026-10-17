# round34 spec：跨店同问同答（平台桶共享安全）与空召回确定性出口

**Status:** in-progress（票 129/130 已落地，131 收口待活体复测读数）

触发：2026-10-17 所有者报障「同一个问题在不同店铺回答一模一样，即使问题与店铺无关也会根据问题回答」。
按其指令「帮我找出缺陷并优化」执行；依据 = **事实性缺陷修复**（round33 承诺的隔离在活体上不成立，
ADR 0009/0067 的承诺被实现违反）+ 所有者政策覆盖。**不自动续期**，本 round 收口即止。

## 范围（票 129-131）

| # | 票 | 内容 |
| --- | --- | --- |
| 129 | 跨店缓存共享安全 | 平台桶读写加「本行业该意图无店铺级条款」前置条件（新增 `ShopRuleIndex`）；同批修 `sourceDocs` 随条目落库 |
| 130 | 空召回确定性出口 | 政策意图空召回（未降级）不调模型，`INTENT_UNRESOLVED` + 工单 + 负标记 |
| 131 | 收口 | 活体复测读数、门禁接入（矩阵 28 → 29 步 add-only）、EVIDENCE/tracker/CHANGELOG 登记 |

## 判据红线（一条不动）

- gold 180 / 阈值 / 指标分母 / 既有验收脚本判据：**零改动**。
- 新增 `scripts/verify-store-isolation.py` 与 `run-acceptance.ps1` 的 `isolation` 步：add-only。
- 决策与代价（命中率、探针失败 fail-closed、活体 gold 未跑）见 **ADR 0068**。

## 复现与证据

- 修复前（活体，本机 `logs/qa-store-repro-20261017/`，不入库）：
  - `run1.txt`：T002/T003 命中 T001 的平台桶答案（120 字逐字节相同，`cache=L1`）；
    T004 零引用自由发挥（「15 天」「死蟹全额退款」），无降级无工单。
  - `probe2.txt`：换问法（避开 L1）后 T002 立刻检索到 `fresh-08-shop-t002-dead-crab`
    ——缺陷只在共享判定，不在检索。
- 修复后：`python scripts/verify-store-isolation.py`（第 29 步）与 JVM 回归用例，
  读数见票 131 与 `docs/EVIDENCE.md`。
