# 检索质量：BM25 / 稠密 / RRF 混合（同一把尺子 top-5）

> 生成：`RetrievalQualityMetricsTest`（JVM，0 token，干净 runner 可复现）。
> 数据源：`eval/retrieval-fixture-20260928.json`（round22 录的三路完整序）+ `eval/retrieval-labels.json`（标注）。**不重跑检索、不连模型、不连引擎。**

相关 = 期望语料文件的**任一规则块**（沿用 `docs/retrieval-comparison.md` 的判据口径）。
三臂都给 top-5：生产里 `fusedTopK=5`，而 single-path 的 20 只是**融合输入**，不是它对外的答案长度。

## 汇总

| 臂 | hit@5 | MRR@5 |
|---|---|---|
| BM25（lexical） | 10/10 | 0.875 |
| 稠密（dense） | 10/10 | 1.000 |
| RRF 混合（fused） | 10/10 | 1.000 |

## 逐条：首个命中的名次（**三臂同深 top-5**）

| 查询 | 期望语料 | BM25 | 稠密 | 混合 |
|---|---|---|---|---|
| 七天无理由怎么算 | `return-01-7day-basic` | 2 | 1 | 1 |
| 退回去的邮费谁出 | `return-03-shipping-cost` | 4 | 1 | 1 |
| 退款到账要几个工作日 | `return-04-refund-timeline` | 1 | 1 | 1 |
| 偏远地区包邮吗 | `shipping-03-remote` | 1 | 1 | 1 |
| 可以指定发顺丰吗 | `shipping-01-carrier-scope` | 1 | 1 | 1 |
| 收货地址填错了还能改吗 | `shipping-05-address-change` | 1 | 1 | 1 |
| 生鲜签收后多久之内可以申请理赔 | `fresh-01-claim-window` | 1 | 1 | 1 |
| 螃蟹到货是死的能赔吗 | `fresh-06-seafood-dead` | 1 | 1 | 1 |
| 店铺券和满减可以叠加不 | `promo-03-coupon-stack` | 1 | 1 | 1 |
| 跨店满减是怎么凑的 | `promo-01-cross-shop` | 1 | 1 | 1 |

## 融合到底改了什么（**不需要标注**的一层）

质量指标饱和时，能问的另一个问题是「融合把名次改成了什么样」。这一层**不依赖标注**，
所以它的结论不受上面那份标注的偏差影响。

| 查询 | dense top-1 | BM25 top-1 | 混合 top-1 | 混合的 top-1 来自 |
|---|---|---|---|---|
| 七天无理由怎么算 | return-01-7day-basic | return-07-shop-t001-window | return-01-7day-basic | 与 dense 相同（两路本来就一致） |
| 退回去的邮费谁出 | return-03-shipping-cost | return-05-defect-exchange | return-03-shipping-cost | 与 dense 相同（两路本来就一致） |
| 退款到账要几个工作日 | return-04-refund-timeline | return-04-refund-timeline | return-04-refund-timeline | **BM25** |
| 偏远地区包邮吗 | shipping-03-remote | shipping-03-remote | shipping-03-remote | **BM25** |
| 可以指定发顺丰吗 | shipping-01-carrier-scope | shipping-01-carrier-scope | shipping-01-carrier-scope | **BM25** |
| 收货地址填错了还能改吗 | shipping-05-address-change | shipping-05-address-change | shipping-05-address-change | **BM25** |
| 生鲜签收后多久之内可以申请理赔 | fresh-01-claim-window | fresh-01-claim-window | fresh-01-claim-window | **BM25** |
| 螃蟹到货是死的能赔吗 | fresh-06-seafood-dead | fresh-06-seafood-dead | fresh-06-seafood-dead | **BM25** |
| 店铺券和满减可以叠加不 | promo-03-coupon-stack | promo-03-coupon-stack | promo-03-coupon-stack | **BM25** |
| 跨店满减是怎么凑的 | promo-01-cross-shop | promo-01-cross-shop | promo-01-cross-shop | **BM25** |

- 混合的 top-1 与 dense 相同：**10 / 10** 条
- 混合的 top-1 改由 **BM25** 提供：**8** 条（融合把稀疏路顶上来的地方）
- 混合的 top-1 来自**两路都不是**的第三来源：**0** 条
- 混合的 top-1 来自 dense 但与 BM25 不同：**0** 条

**这张表的读法**：融合在这一批数据上的作用主要是**不让 BM25 的好结果丢掉**，而不是把稠密的结果改好。

## 结论（按实测算，不按设计意图写）

1. **hit@5 在这批查询上完全饱和**（三臂都 10/10），所以「谁更准」这句话**测不出来**——这与 `docs/retrieval-comparison.md` 那 16 条是同一个现象，不是新问题。
2. **MRR@5：稠密 1.000、混合 1.000、BM25 0.875**。也就是说**在这批查询上融合追平了最好的一臂、比 BM25 高，但它没有超过稠密**。
3. **所以诚实的说法不是「混合更好」，而是「混合没有更差」**：它保住了 BM25 在这批查询里的好名次，而代价（多一次融合计算）在这批数据上**没有换成更高的命中**。
4. 要证明融合在**困难查询**上有价值，需要一批**故意难**的样本（同义改写、多意图、需要跨文档综合的）；这批 10 条的挑选标准是「两路结果不同」，**对融合有利**，所以上面的结论**不能外推**。

## 这张表测不出什么（口径的边界，不藏）

1. **它测「查得到 / 查不到」，不测「答得对 / 答错」**。标注按文件而非按块，
   所以需要**跨文档综合**才能回答的问题（round19 QA 记过：「退款到哪了」需先查再答）会被判成不命中。
2. **标注由作者读标题拟定、未对排序盲化**。这会在「同义但不同词」的查询上略微偏向稠密召回；
   偏差方向写在这里而不是脚注里——一张有偏的表，读者有权知道它往哪边偏。
3. **10 条查询的样本量不足以做显著性判断**。它能回答「这批查询上谁没掉队」，
   不能回答「A 优于 B」。要下那个结论需要人工标注的百条量级，那是另一笔投入。
4. **它复用了 round22 录的那 10 条**，而那份录制的选择标准不是「难查询」而是「两路结果不同」——
   所以这批查询**对混合检索是有偏利的样本**。BM少赢几条不代表它在别处也弱。
