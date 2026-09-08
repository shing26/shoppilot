# 04 — 政策知识库离线入库脚本

**What to build:** 一条命令把 30 篇政策 Markdown 切分并向量化进 Qdrant 与 ES，同一 `ruleId` 落两边，重跑不产生重复。落实 ADR 0010。

**Blocked by:** 01 — 三模块骨架与中间件容器栈

**Status:** ready-for-agent

**Verify:** 入库脚本连跑两次后分别统计 ES 与 Qdrant 条目数 -> 两边数量相等，且重跑后不增长。

- [ ] 30 篇政策文档覆盖四类：退换货细则、生鲜保鲜理赔、跨店满减与定金膨胀、发货与快递政策；LLM 生成后人工校对，术语与 `CONTEXT.md` 一致
- [ ] 按标题层级切分为规则块，目标 300-600 字，不重叠；`ruleId = hash(sourceDoc + headingPath)`
- [ ] 元数据：`ruleType` `applicableCategory` `scope` `tenantId`（平台级条目固定为平台标识）`sourceDoc` `effectiveFrom`
- [ ] `ruleId` 同时作为 ES `_id` 与 Qdrant point id，重跑为幂等 upsert
- [ ] embedding 走本地 `bge-m3`，1024 维，经 Ollama；Ollama 未启动时脚本给出可执行的提示而非堆栈
- [ ] 脚本输出统计：文档数、规则块数、各 scope 分布、入库耗时
- [ ] 知识库纪元 `kb_epoch` 由脚本在成功入库后推进，供缓存失效使用

## Handoff notes

（收尾时填写：关键决策 + 你需要能当场回答的三个追问）
