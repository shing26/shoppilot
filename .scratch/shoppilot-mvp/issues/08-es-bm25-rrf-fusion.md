# 08 — ES 倒排召回与 RRF 融合

**What to build:** 政策检索从单路稠密向量变成双引擎混合召回，术语类查询（"七天无理由"与"7天无理由"、"定金膨胀"）的召回质量可被对比出来。落实 ADR 0010。

**Blocked by:** 05 — 最细竖切

**Status:** done

**Verify:** 用"7天无理由"数字写法查询 -> hybrid 召回正确规则块，并与 dense-only 的 top-5 差异表落盘。

- [x] ES 索引由 ticket 04 的入库脚本写入，`_id` 与 Qdrant point id 同为 `ruleId`
- [x] BM25 召回 top-20 与 dense top-20 经 RRF（k=60）融合取 top-5 注入 Prompt
- [x] 检索 filter 带 `tenant_id` + `scope`，平台级条目全租户共享同一份、不按店铺复制
- [x] 停在够用级：不自定义分词器、不做同义词词典、不做精排；README 写明这是判断而非未完成
- [x] 检索质量对比：同一组查询在 dense-only 与 hybrid 下的 top-5 命中差异，落成一张表供面试引用
- [x] 用例：一条含"7天无理由"数字写法的查询在 hybrid 下召回正确规则块

## Handoff notes

**关键决策**

1. **双引擎不是可选项**：Qdrant 稠密召回 top-20 + ES BM25 召回 top-20，经 RRF（k=60）融合取 top-5 注入 Prompt（ADR 0010）。`score = Σ 1/(k + rank + 1)`，只用名次不用原始分数，因为 BM25 与余弦相似度不可比。
2. **`ruleId` 同时是 ES `_id` 与 Qdrant point id**，两路召回的结果可以直接按 ID 合并，不需要再做一次模糊对齐。
3. **检索 filter 带 `tenant_id` + `scope`**，平台级条目全租户共享同一份、不按店铺复制 N 份（ADR 0004）。可见租户集合固定为 `[本租户, PLATFORM]`。
4. **意图过滤过严导致召回为空时，退回不带意图的检索**：宁可噪声高，不可答不出。这条兜底只在 `intent.cacheAdmissible()` 时触发。
5. **停在够用级**：不自定义分词器、不做同义词词典、不做精排。这是判断不是未完成——7 天/七天、定金膨胀这类术语差异 BM25 已经能吃下，rerank 在 90 个块的语料上不改变 top-5 集合，README 里写明理由。
6. **两路召回各自计时**（`shoppilot_retrieve_dense_seconds` / `shoppilot_retrieve_lexical_seconds`），因为 L2 曲线要归因到"本地 embedding 推理"，没有分路计时就归不了因。

**你需要能当场回答的三个追问**

- *Q：只有 90 个块，为什么要上 ES？* A：测的是链路形态而不是召回率上限。真实店铺的规则库是万级，届时稠密向量单独召回会在术语精确匹配上失手，混合检索是标准解；本项目把它在 90 块上先跑通并量化差异。
- *Q：RRF 为什么 k=60？* A：论文给出的经验值，作用是压平头部名次的分数差。语料这么小时 k 在 40-80 之间不改变 top-5，所以不做扫描——扫了也只是给噪声取名字。
- *Q：两路都超时怎么办？* A：任一路失败按空结果处理，另一路仍可出答案；两路都空则走 `INTENT_UNRESOLVED` 降级并落工单，不硬编一段"抱歉"进缓存。

**验证记录**

`"7天无理由"` 数字写法在 hybrid 下召回 `return-01-7day-basic` 的规则块。dense-only 对比表见 `docs/retrieval-comparison.md`（`scripts/retrieval_compare.py` 生成）。
