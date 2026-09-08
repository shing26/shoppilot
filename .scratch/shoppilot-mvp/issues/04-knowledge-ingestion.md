# 04 — 政策知识库离线入库脚本

**What to build:** 一条命令把 30 篇政策 Markdown 切分并向量化进 Qdrant 与 ES，同一 `ruleId` 落两边，重跑不产生重复。落实 ADR 0010。

**Blocked by:** 01 — 三模块骨架与中间件容器栈

**Status:** done

**Verify:** 入库脚本连跑两次后分别统计 ES 与 Qdrant 条目数 -> 两边数量相等，且重跑后不增长。

- [x] 30 篇政策文档覆盖四类：退换货细则、生鲜保鲜理赔、跨店满减与定金膨胀、发货与快递政策；LLM 生成后人工校对，术语与 `CONTEXT.md` 一致
- [x] 按标题层级切分为规则块，目标 300-600 字，不重叠；`ruleId = hash(sourceDoc + headingPath)`
- [x] 元数据：`ruleType` `applicableCategory` `scope` `tenantId`（平台级条目固定为平台标识）`sourceDoc` `effectiveFrom`
- [x] `ruleId` 同时作为 ES `_id` 与 Qdrant point id，重跑为幂等 upsert
- [x] embedding 走本地 `bge-m3`，1024 维，经 Ollama；Ollama 未启动时脚本给出可执行的提示而非堆栈
- [x] 脚本输出统计：文档数、规则块数、各 scope 分布、入库耗时
- [x] 知识库纪元 `kb_epoch` 由脚本在成功入库后推进，供缓存失效使用

## Handoff notes

**关键决策**

1. **30 篇政策文档覆盖四类**：退换货细则 8 篇、生鲜保鲜理赔 7 篇、跨店满减与定金膨胀 8 篇、发货与快递政策 7 篇；LLM 生成后人工校对，术语与 `CONTEXT.md` 对齐。语料是合成的，README 明确写了这一点，不假装是真实平台条款。
2. **按标题层级切分，目标 300-600 字，不重叠**，实测 30 篇切出 90 个规则块。不做滑窗重叠是刻意取舍：重叠块会在 RRF 融合时互相抢名次，让 top-5 里三块是同一句话的三种截断，检索质量反而下降。
3. **`ruleId = hash(sourceDoc + headingPath)`，同时作为 ES `_id` 与 Qdrant point id。** 两边共用同一个 ID 是"重跑即幂等 upsert"的前提，也是 ticket 08 能把两路召回对齐融合的前提。
4. **元数据落 `ruleType` / `applicableCategory` / `scope` / `tenantId` / `sourceDoc` / `effectiveFrom`**；平台级条目 `tenantId` 固定为平台标识（`RuleChunk.PLATFORM_TENANT`），全租户共享同一份，不按店铺复制 N 份（ADR 0004）。
5. **embedding 恒为本地 `bge-m3` 1024 维，经 Ollama**（ADR 0001）。入库与在线检索必须同模型同维度，换模型等于换向量空间，混用会让缓存与检索同时失效。
6. **Ollama 未启动时脚本给出可执行提示而非堆栈**：`请先运行 ollama serve 与 ollama pull bge-m3`。这类"接手就能跑通"的报错文案是项目可用性的一部分。
7. **`kb_epoch` 由脚本在成功入库后推进**，作为缓存纪元：政策一改，旧答案整纪元作废（ticket 06/09 的失效机制靠它，不靠 TTL）。

**你需要能当场回答的三个追问**

- *Q：为什么不用现成的文档解析框架？* A：语料是自己写的 Markdown，标题层级就是天然结构；引入解析器只增加依赖和不确定性。切分逻辑集中在 `MarkdownChunker` 一个类里，好测也好改。
- *Q：90 块是不是太少？* A：对演示与压测足够，因为要测的是链路而不是召回率上限。真正的信息量在元数据过滤（scope/tenant/intent）上，块数增加不改变结论。
- *Q：重跑会不会产生重复向量？* A：不会，Qdrant 用 `ruleId` 作 point id，重复写是覆盖；验收就是"连跑两次两边条目数相等且不增长"。

**验证记录（2026-09-05，后续多次重跑）**

`scripts/ingest.ps1` 连跑两次：ES 与 Qdrant 均 90 条且不增长；`kb_epoch` 每次成功入库 +1（当前值为 9，含历次语料修订）。
