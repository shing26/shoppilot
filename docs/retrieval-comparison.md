# dense-only 与 hybrid 检索质量对比

生成：`python scripts/retrieval_compare.py`（需网关与三中间件在跑）。

对比口径：**同一次检索里**分别取稠密召回的前 5 与 RRF 融合后的前 5，判据是「期望语料文件的任一规则块是否进入前 5」。90 个规则块、16 条查询、按文件名前缀判定命中（同一篇文档的三个块都算对，因为块粒度不是本项要考的东西）。

## 结果

| 查询 | 期望语料 | dense-only 名次 | hybrid 名次 | dense hit@5 | hybrid hit@5 |
| --- | --- | --- | --- | --- | --- |
| 7天无理由怎么算 | `return-01-7day-basic` | 1 | 1 | 是 | 是 |
| 七天无理由的起算时间是什么适合 | `return-01-7day-basic` | 1 | 1 | 是 | 是 |
| 退回去的邮费谁出 | `return-03-shipping-cost` | 1 | 1 | 是 | 是 |
| 退款到账要几个工作日 | `return-04-refund-timeline` | 1 | 1 | 是 | 是 |
| 跨店满减是怎么凑的 | `promo-01-cross-shop` | 1 | 1 | 是 | 是 |
| 定金膨胀能跟跨店满减一起用吗 | `promo-02-deposit-inflation` | 1 | 1 | 是 | 是 |
| 店铺券和满减可以叠加不 | `promo-03-coupon-stack` | 1 | 1 | 是 | 是 |
| 买贵了能申请价保退差价吗 | `promo-05-price-protection` | 1 | 1 | 是 | 是 |
| 生鲜签收后多久之内可以申请理赔 | `fresh-01-claim-window` | 1 | 1 | 是 | 是 |
| 冷链断掉化冻了怎么界定责任 | `fresh-04-cold-chain` | 1 | 1 | 是 | 是 |
| 螃蟹到货是死的能赔吗 | `fresh-06-seafood-dead` | 1 | 1 | 是 | 是 |
| 可以指定发顺丰吗 | `shipping-01-carrier-scope` | 1 | 1 | 是 | 是 |
| 拍下之后多久发货 | `shipping-02-deadline` | 1 | 1 | 是 | 是 |
| 偏远地区包邮吗 | `shipping-03-remote` | 1 | 1 | 是 | 是 |
| 收货地址填错了还能改吗 | `shipping-05-address-change` | 1 | 1 | 是 | 是 |
| 大促期间发货会延迟吗 | `shipping-07-festival-surge` | 1 | 1 | 是 | 是 |

## 汇总

- dense-only hit@5：**16/16**
- hybrid hit@5：**16/16**
- hybrid 明显更好的查询：0 条；hybrid 反而变差的查询：0 条

### 差异明细

两路在本查询集上没有名次差异。

### 前 5 名对照（供追问时展开）

| 查询 | dense-only top-5 | hybrid top-5 |
| --- | --- | --- |
| 7天无理由怎么算 | return-01-7day-basic, return-03-shipping-cost, return-07-shop-t001-window, return-02-7day-exclusions, return-02-7day-exclusions | return-01-7day-basic, return-07-shop-t001-window, return-03-shipping-cost, return-02-7day-exclusions, return-02-7day-exclusions |
| 七天无理由的起算时间是什么适合 | return-01-7day-basic, return-07-shop-t001-window, return-03-shipping-cost, return-02-7day-exclusions, return-02-7day-exclusions | return-01-7day-basic, return-07-shop-t001-window, return-03-shipping-cost, return-02-7day-exclusions, return-02-7day-exclusions |
| 退回去的邮费谁出 | return-03-shipping-cost, return-03-shipping-cost, return-05-defect-exchange, return-07-shop-t001-window, return-06-packaging-requirement | return-03-shipping-cost, return-03-shipping-cost, return-07-shop-t001-window, return-05-defect-exchange, return-05-defect-exchange |
| 退款到账要几个工作日 | return-04-refund-timeline, return-04-refund-timeline, return-04-refund-timeline, return-01-7day-basic, return-05-defect-exchange | return-04-refund-timeline, return-04-refund-timeline, return-04-refund-timeline, return-07-shop-t001-window, return-03-shipping-cost |
| 跨店满减是怎么凑的 | promo-01-cross-shop, promo-01-cross-shop, promo-03-coupon-stack, promo-01-cross-shop, promo-07-split-cart | promo-01-cross-shop, promo-01-cross-shop, promo-01-cross-shop, promo-03-coupon-stack, promo-02-deposit-inflation |
| 定金膨胀能跟跨店满减一起用吗 | promo-02-deposit-inflation, promo-01-cross-shop, promo-03-coupon-stack, promo-01-cross-shop, promo-02-deposit-inflation | promo-02-deposit-inflation, promo-01-cross-shop, promo-03-coupon-stack, promo-02-deposit-inflation, promo-01-cross-shop |
| 店铺券和满减可以叠加不 | promo-03-coupon-stack, promo-08-shop-t001-member, promo-01-cross-shop, promo-02-deposit-inflation, promo-01-cross-shop | promo-03-coupon-stack, promo-08-shop-t001-member, promo-01-cross-shop, promo-02-deposit-inflation, promo-08-shop-t001-member |
| 买贵了能申请价保退差价吗 | promo-05-price-protection, promo-08-shop-t001-member, promo-05-price-protection, promo-05-price-protection, promo-06-refund-discount | promo-05-price-protection, promo-08-shop-t001-member, promo-05-price-protection, promo-05-price-protection, promo-06-refund-discount |
| 生鲜签收后多久之内可以申请理赔 | fresh-01-claim-window, fresh-03-packaging-loss, fresh-06-seafood-dead, fresh-02-claim-scope, fresh-01-claim-window | fresh-01-claim-window, fresh-03-packaging-loss, fresh-07-exclusions, fresh-06-seafood-dead, fresh-07-exclusions |
| 冷链断掉化冻了怎么界定责任 | fresh-04-cold-chain, fresh-03-packaging-loss, fresh-04-cold-chain, fresh-04-cold-chain, fresh-07-exclusions | fresh-04-cold-chain, fresh-04-cold-chain, fresh-03-packaging-loss, fresh-04-cold-chain, fresh-07-exclusions |
| 螃蟹到货是死的能赔吗 | fresh-06-seafood-dead, fresh-06-seafood-dead, fresh-02-claim-scope, fresh-06-seafood-dead, fresh-02-claim-scope | fresh-06-seafood-dead, fresh-06-seafood-dead, fresh-02-claim-scope, fresh-06-seafood-dead, fresh-01-claim-window |
| 可以指定发顺丰吗 | shipping-01-carrier-scope, shipping-01-carrier-scope, shipping-04-split-package, shipping-03-remote, shipping-02-deadline | shipping-01-carrier-scope, shipping-04-split-package, shipping-02-deadline, shipping-04-split-package, shipping-01-carrier-scope |
| 拍下之后多久发货 | shipping-02-deadline, shipping-01-carrier-scope, shipping-03-remote, shipping-02-deadline, shipping-07-festival-surge | shipping-02-deadline, shipping-01-carrier-scope, shipping-05-address-change, shipping-02-deadline, shipping-04-split-package |
| 偏远地区包邮吗 | shipping-03-remote, shipping-01-carrier-scope, shipping-03-remote, shipping-03-remote, shipping-05-address-change | shipping-03-remote, shipping-01-carrier-scope, shipping-03-remote, shipping-03-remote, shipping-01-carrier-scope |
| 收货地址填错了还能改吗 | shipping-05-address-change, shipping-05-address-change, shipping-05-address-change, shipping-04-split-package, shipping-03-remote | shipping-05-address-change, shipping-05-address-change, shipping-05-address-change, shipping-04-split-package, shipping-03-remote |
| 大促期间发货会延迟吗 | shipping-07-festival-surge, shipping-07-festival-surge, shipping-07-festival-surge, shipping-02-deadline, shipping-01-carrier-scope | shipping-07-festival-surge, shipping-07-festival-surge, shipping-07-festival-surge, shipping-02-deadline, shipping-01-carrier-scope |

## 结论（按实测写，不按设计意图写）

- 两路在本查询集上的 hit@5 **完全相同（16 vs 16）**，gold 排第 1 的条数也几乎一样（dense 16 / hybrid 16）。
- 差异只体现在次序：12/16 条查询的 top-5 **顺序**不同，其中 8 条的 top-5 **集合**不同。
- 最反直觉的一条：`7天无理由怎么算` 这种数字写法，bge-m3 的稠密召回**本来就把它排在第 1**。上线前担心的“数字 vs 中文写法”差距在这个语料上没有出现，所以混合检索的收益不能说成“修复了数字写法”——那是没测出来的东西。

### 那为什么还留着 Elasticsearch

- 当前语料只有 90 块，任何一路都能把它排完；双引擎测的是**链路形态**而不是当下收益。万级条款、跨店铺条款命名高度雷同（`return-07-shop-t001-window` 这类）时，纯稠密召回容易被语义相近但条款不同的块挤出去，词法那路是可解释性与精确术语召回的兜底。
- 保留成本是可控的：ES 限 512MB 堆、单节点、不开安全、索引由同一个入库脚本写，两路结果按 `ruleId` 对齐，没有双写一致性问题。

### 刻意不做的事

自定义分词器、同义词词典、精排（rerank）模型。90 个块上精排不改变 top-5 集合——这是判断不是没做完；判断若错了，代价是加一层独立可插的 rerank 模块。
