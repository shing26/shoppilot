# 131 round34 收口

**Status:** implemented

## What to build

round34 收口：活体复测读数、门禁接入（矩阵 29 步 add-only）、ADR/EVIDENCE/tracker/CHANGELOG/
`registered-debt` 登记，以及 QA 走查新发现的「登记不执行」入账。

## Blocked by

[129](129-cross-store-cache-sharing.md)、[130](130-empty-recall-deterministic-exit.md)。

## 口径

- 交付层读数换代：JVM **`10 + 69 + 423 + 45 = 547`**；ADR **67 篇**（新增 0068）；票 **131 张**；
  矩阵 **29 步**；CI 九步不变；gateway 覆盖率 **67.42%**（门槛 54.0）。
- **一条既有判据都没改**：gold / 阈值 / 指标分母 / 验收断言零触碰；新门禁是 add-only 一步。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/check_coverage.py
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
pwsh -NoProfile -File scripts/run-acceptance.ps1 -Tier full -Only isolation -SkipBuild
git diff --check; git status --short
```

## Handoff notes

- **活体复测（修前 → 修后，同一栈同一批问句）**：
  - 修前（本机 `logs/qa-store-repro-20261017/run1.txt`、QA 走查，不入库）：T002/T003 命中 T001
    的平台桶答案（120 字逐字节相同）；顺序效应把 T001 自己的 15 天答案顶掉；T004 零引用自由发挥
    （「死蟹全额退款」）且不落工单。
  - 修后（`run2-postfix.txt`）：T002「死蟹怎么赔付」引 `fresh-08-shop-t002-dead-crab`（3/5 条）；
    T004/T001/T002/T003 的跨域问题全部 `INTENT_UNRESOLVED` + 工单；同店答案不再被别店顶掉。
  - `isolation` 步 **5/5 PASS**（`logs/acceptance-run-20261017-205046.log`，本机产物）。
- **变异对照两条**：平台共享判定恒真 → 恰 1 条新回归红；空召回出口条件置 false → 恰 1 条新用例红
  （且已给模型站桩答案，红的是断言而非 NPE）。
- **未达成照登**：① 全量 **29 步矩阵未跑**（只跑 `-Only isolation` + 针对性探针）——`logs/acceptance/`
  里 build/unit 仍是旧落点，收口审计 G6 会因此判红（**不为消红改判据**）；② **多店混合流量的命中率
  影响未测量**（单店压测读数不受影响）；③ **180 条 gold 活体重跑未做**（空召回出口换代的影响未验证，
  需 dev 额度越 ADR 0012 闸门）；④ 修复前写下的 L1 条目没有 `sourceDocs`（命中路径 `citationDetails.doc`
  为空串，内容正确，随 TTL 自然换代）；⑤ **CI 未跑**（本机未推）。
- **QA 走查新发现（登记不执行，触发条件见 `registered-debt.md`）**：R6 词表/质心覆盖扩充、
  R7 定责类请求的转人机制与零召回相关性下限、R8 输入侧提示注入防护；U8 降级对调用方不可见。
- **收口审计实跑（读数换代，判据一字未动）**：提交前 **`PASS 74 / FAIL 12 / SKIP 10`**（相对 round33 推送后基线
  多的一条红是 `A2` 工作树未提交）；提交后 `A2` 转绿、`A1`（origin/main == HEAD）转红——**本地领先 3 个提交、未推送**，
  与 round33 的「推送后 75/11/10」同形。其余 10 条红与 round33 逐条相同：`B7` 既有窗口红（命中清单
  不含本轮任何文件）、`G6` 常数 536 ≠ 旧矩阵日志 448（本轮没重跑带 build/unit 的矩阵）、E2/E6b/E6c/F1c/H5b/H8/H8b/H10 为先前轮次文档漂移。
  **不许为消红改判据。**
- **三个现场追问**：
  1. 探针按**意图**粒度挡共享（如 `POLICY_FRESH` 里任何门店有细则，全意图不跨店）——
     要不要细化到文档主题粒度？那会引入第二份模糊判定，本轮按「粗粒度挡多不挡错」定案。
  2. 旧污染条目靠「读侧跳过」自愈，但**残留条目仍占 Redis 空间**（L1 TTL 到期自清）——
     要不要在升级时给运维一个「按 domain 清理平台桶」的显式动作？
  3. `INTENT_UNRESOLVED` 的首问出口让「确实没有这条政策」的问句立即落单，人工队列长度影响
     **未测量**；若可观，选项是按 `reason` 在坐席台过滤而不是改出口。
