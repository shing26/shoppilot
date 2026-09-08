# 08 — ES 倒排召回与 RRF 融合

**What to build:** 政策检索从单路稠密向量变成双引擎混合召回，术语类查询（"七天无理由"与"7天无理由"、"定金膨胀"）的召回质量可被对比出来。落实 ADR 0010。

**Blocked by:** 05 — 最细竖切

**Status:** ready-for-agent

**Verify:** 用"7天无理由"数字写法查询 -> hybrid 召回正确规则块，并与 dense-only 的 top-5 差异表落盘。

- [ ] ES 索引由 ticket 04 的入库脚本写入，`_id` 与 Qdrant point id 同为 `ruleId`
- [ ] BM25 召回 top-20 与 dense top-20 经 RRF（k=60）融合取 top-5 注入 Prompt
- [ ] 检索 filter 带 `tenant_id` + `scope`，平台级条目全租户共享同一份、不按店铺复制
- [ ] 停在够用级：不自定义分词器、不做同义词词典、不做精排；README 写明这是判断而非未完成
- [ ] 检索质量对比：同一组查询在 dense-only 与 hybrid 下的 top-5 命中差异，落成一张表供面试引用
- [ ] 用例：一条含"7天无理由"数字写法的查询在 hybrid 下召回正确规则块

## Handoff notes

（收尾时填写：关键决策 + 你需要能当场回答的三个追问）
