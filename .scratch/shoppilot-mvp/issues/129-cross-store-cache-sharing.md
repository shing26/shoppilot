# 129 跨店缓存共享安全：平台桶加「本行业该意图无店铺级条款」前置条件

**Status:** implemented

## What to build

修「同一个问题在不同店铺回答一模一样」的缓存侧根因：`PLATFORM:<DOMAIN>` 桶的共享判定从
「引用全为平台条款」收紧为「引用全为平台条款 **且** 本行业该意图不存在任何店铺级条款」，
读写两侧同源判定；同批把 `sourceDocs` 补进缓存条目（命中路径引用详情缺出处的契约错位）。

- 新增 `knowledge/ShopRuleIndex`：按 `scope=SHOP + domain + intent + 当前纪元` 查词法索引（size=1），
  结果按 (纪元, 行业, 意图) 记忆，纪元换代即作废；探针失败 **fail-closed**（当存在店铺级条款）。
- `CacheService`：注入探针；`buckets()` 对存在店铺级条款的意图只返回本店桶（读侧跳过平台桶——
  旧污染条目自愈）；`prepareWrite` 的平台桶判定加同一前置条件；`SCOPE_PLATFORM/SCOPE_SHOP`
  改为引用 `RuleChunk` 常量（不再两处各写一份字面量）。
- `CacheEntry` 补 `sourceDocs`（与 `sourceRuleIds`/`sourceHeadings` 同源同序）；
  `AgentStateMachine` 命中路径与穿透复用路径的 `citationDetails` 带出处；写回 trace 行带 `scope=`。
- `RuleChunk` 落 `SCOPE_PLATFORM`/`SCOPE_SHOP` 常量。

## Blocked by

无（独立缺陷修复）。

## 口径

- 不是拆平台桶：没有店铺级条款的意图（如 `POLICY_SHIPPING`）仍跨店共享（ADR 0004 的平台级共享保留）。
- 宁可多挡：按意图粗粒度的过度保守方向是「多存几份本店答案」，不是串店答案。
- 命中率代价照登：`POLICY_RETURN`/`POLICY_PROMO`/`POLICY_FRESH` 在多店流量下不再跨店共享，
  单店压测读数不受影响、多店混合影响**未测量**。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/verify-store-isolation.py   # 需活体栈（第 29 步）
git diff --check; git status --short
```

## Handoff notes

- **关键决策**：共享安全性由知识库**实测**（探针），不按写方引用猜——写方在租户隔离下看不见别家的
  店铺级条款，「引用全为平台条款」从来不足以证明「这份答案对各店都成立」。读侧跳过与写侧同源，
  否则修复前写下的平台桶条目会继续被复用（自愈性）。
- **验证落点**：`CacheServiceTest`（新增两条回归：存在店铺级条款 → 平台桶不可读不可写、
  无店铺级条款 → 照常跨店共享；`l1HitNeedsNoEmbedding` 等旧用例语义不变）、
  `ShopRuleIndexTest`（过滤维度 / 纪元记忆 / fail-closed / null 意图）、`RuleChunk` 常量同源用例；
  全仓 `verify` 绿。**变异对照**：`platformShareAllowed` 恒真 → 恰一条新回归红。
- **活体**：`python scripts/verify-store-isolation.py` 第 1/2/4 条断言（门店细则在场、顺序效应、
  跨店共享仍活着 + 命中路径带出处）见票 131 读数。
- **三个现场追问**：
  1. 探针按意图粒度挡共享——`POLICY_FRESH` 里 T002 的死蟹细则是店铺级，于是连 "水果坏了怎么赔"
     这类**与门店细则无关**的生鲜问句也不跨店共享了。要不要细化到「文档主题」粒度？那会引入
     第二份模糊判定，本轮按「粗粒度挡多不挡错」定案。
  2. 命中率代价没有多店压测读数（本机资源账），要不要把 `ladder` 压测扩一个「两店交替」参数组？
  3. 探针在 ES 抖动时 fail-closed，等于平台桶随 ES 一起抖——要不要给它一个「上次已知答案」的
     短 TTL 缓存（stale-ok）？本轮不回退：隔离比命中率值钱。
