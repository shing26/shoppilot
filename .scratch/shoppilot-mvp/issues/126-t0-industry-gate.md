# 126 T0 行业门、动作优先与词表精度

**Status:** implemented

## What to build

修掉 T0 规则层在「动作 + 政策」同句上的优先级回归，并给酒店租户加一道行业门。

- `T0RuleLayer.classify(query, domain)`：判定顺序 = 显式转人工 → 行业门 → 实体/第一人称动作 →
  显式办理动作短语 → 酒店词表 → 电商政策词表；`classify(query)` 默认 `ECOMMERCE` 保持旧调用点行为。
- `classifyHotel`：酒店租户只认酒店词表，命中给 `POLICY_HOTEL`，否则返回 empty（交 T1 或零工具模型）；
  电商租户问酒店词仍给 `POLICY_HOTEL`，靠检索 domain 过滤拿不到条款、最终转人工。
- `EXPLICIT_ACTION_PHRASES`：显式办理动作短语（「我要退款 / 帮我申请退款 / 我要改地址」等）**优先于**
  政策词表，修 D4；纯政策问法（「退款政策是什么 / 能退吗」）不含意愿短语，仍走政策。
- 收紧词表：`FRESH_WORDS` 去掉「坏了 / 破损 / 变质 / 理赔」这类泛词，只留行业对象词；`HOTEL_WORDS`
  去掉独立「早餐」，改成「酒店早餐 / 含早 / 早餐券 / 早餐时间」，修 D6 的 T0 误判。
- `TriageEngine.triage(query, domain)` 转发 domain；`T0RuleLayerTest` 加动作优先 / 酒店行业门 / 泛词反证。

## Blocked by

[124](124-round33-adr-and-spec.md)。

## 口径

- 行业门只保护「酒店租户不被电商词表污染」；跨行业的最终隔离仍由检索/缓存 domain 兜底。
- 收紧词表不追求完备：不带对象词的生鲜问句改由 T1 兜——「宁可少拦 10% 流量，也不串一次号」（ADR 0007）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
git diff --check; git status --short
```

## Handoff notes

- **关键决策**：行业门放在**显式转人工之后、实体/第一人称之前**——「90001 这单搞错了，转人工」仍先要人；
  而酒店租户的「我要取消订单」不带酒店词，T0 返回 empty 后由 T1 给 `ACTION_REFUND`，但工具列表按 domain
  为空，**不会调电商工具**（活体实测 `toolUsed=false`）。
- **验证落点**：`T0RuleLayerTest` 30 条（含「生鲜坏了我要退款」→ `ACTION_REFUND`、「手机坏了怎么赔」T0
  不判 fresh、「早餐机坏了怎么赔」T0 不判 hotel、「退款政策是什么」仍走政策）。
- **活体**：T001「生鲜坏了我要退款」→ `ACTION_REFUND` + 追问订单号；补「90001」→ `ACTION_REFUND` +
  `toolUsed=true`，退款受理。T004「我要取消订单」→ `toolUsed=false`。
- **三个现场追问**：
  1. T0 泛词收窄后，「理赔审核要多久」这类不带对象词的生鲜问句会落到 T1——T1 质心把它判成 `POLICY_FRESH`
     仍会缓存。要不要给 T1 也加一道「行业门」，让非电商域不得输出电商政策意图？
  2. 显式动作短语是**穷举**的（「我要退款」有，但「我要退钱 / 我要办理退货」未必有）——是否够用，还是要
     改成正则（意愿词 + 动作词）？
  3. 酒店租户问「生鲜坏了怎么赔」，T1 仍可能给 `POLICY_FRESH`（检索 domain 过滤后 dense=0，不泄漏）——
     这个**意图标签不干净但不泄漏**的残留是否可接受，还是必须让非电商域的政策意图一律降级？

## Addendum（2026-10-17，酒店活体跑批补做后）

- **本票的收窄引入了一处回归，由酒店活体套件抓出**：`--suite hotel` 首跑 12 条 11 绿 1 红，
  红的是 `HTL-BKF-01.intent_ok`——「早餐是几点到几点」被收窄后的 `HOTEL_WORDS` 漏掉，判成 `UNKNOWN`
  （答案仍对，但不进缓存、标签错）。旧表列的是「酒店早餐 / 早餐券 / 早餐时间」，覆盖不到**不带前缀**的
  「早餐是几点到几点」。
- **修法（仍在本票「词表精度」范围内）**：`HOTEL_WORDS` 去掉三个早餐词，改用负向断言
  `早餐(?!机)`——餐饮语境的「早餐」命中、家电名里的「早餐机」不命中，**D6 反证不回归**。
  `T0RuleLayerTest` 30 → 34 条（`早餐是几点到几点 / 早餐几点开始 / 早餐时间` 电商与酒店两域一致 +
  「早餐机坏了怎么赔」仍不判酒店）。
- **活体复跑**：`python scripts/run_tool_eval.py --suite hotel` → **12/12 绿**（HTL-BKF-01 → `POLICY_HOTEL`）。
- 数字换代：JVM 四模块 `10 + 69 + 408 + 45 = 532` → **`10 + 69 + 412 + 45 = 536`**，
  同步改了 README / `docs/EVIDENCE.md` / tracker / 收口审计 `G6_EXPECT`。
