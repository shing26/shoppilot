# 检索融合录放门守的是排序确定性与四个常数，不守活体命中率

Context: 本 ADR 由 round22（ADR 0048）的票 64 触发。要解决的问题是：**最强的子系统（混合检索）在 CI 里覆盖为 0**。

取证：`scripts/eval_suites.py:130-235` 的 24 条夹具按 `kind ∈ {emotion, channel, plan, style}` 分发，四个 kind 都**不读** `ruleIds`/`citations`/RRF 序；唯一那份 RAG 质量证据 `docs/retrieval-comparison.md` 的 16 条查询 `dense` 与 `hybrid` **名次完全相同** → 把 `rrf-k` 从 60 改成 30，CI 全绿。也就是说：**改坏融合，没有任何一条断言会红。**

可做性来自一条结构性事实：`ruleId` 同时是 ES `_id` 与 Qdrant point id，所以给定「两路序 + `k`」，融合是**纯函数** —— `HybridRetriever.rrf()`（`:198`）配 `accumulate()`（`:208`），`score += 1.0/(rrfK + rank + 1)`，无随机、无时钟、无模型。

Decision:

**一、判据粒度升到 `ruleId` 级，层一（JVM）是唯一 owner。** 新增 `RetrievalFusionReplayTest`：由夹具里的 `dense` / `lexical` 两路序**重算**融合，断言 top-K 前缀 == `expected_fused_topk`。现有判据 `retrieval_compare.py:79` 按**文件名前缀**判，是文档级 —— 本票要补上「**块级** hit@5 从未被测量」这一格，所以判据必须落在 `ruleId` 上。

**二、四个常数从生产 `application.yml` 绑定，不在测试里写死。** 改 yml 即红。`corpus_sha256` 现算相等（改任一 `knowledge/*.md` 即红）。为此把 `rrf()` 改成**包级可见**（零行为改动）。

**三、层二（Python）不复算 RRF。** `scripts/retrieval_gate.py` 只做夹具 schema 校验、`expected ⊆ dense ∪ lexical`、sha 与 CI pin 校验。**在 Python 里复算融合等于写出第二份实现，那就是第二份判据** —— 违反本仓「判据只有一份」。

**四、夹具家族 append-only，录制用现成探针。** `eval/retrieval-fixture-<date>.json`（与 `eval/results/` 下那些按日期命名的家族同形）。录制用 `OpsController.java:268` 的 `/ops/retrieval` 探针 —— 它一次返回 `denseTop`/`lexicalTop`/`fusedTop`，**一次调用同时录到输入与输出**。夹具里 pin `llm_mode`/`kb_epoch`/`corpus_sha256` 与四个常数。

**五、查询集必须新增「能造分歧」的 case。** 原 16 条 `dense` 与 `hybrid` 名次全同，拿它做夹具**对融合是 no-op**（这正是本票要修的病）。分歧候选：跨店近重复条款（`return-07-shop-t001-window` vs `return-08-shop-t002-fresh` vs 平台级 `return-01-7day-basic`）、同主题相邻的 `shipping-01..07`。

**六、三道防假绿。** ① 重算式断言（只改 `expected` 必红）；② 反证夹具（喂打乱后的输入必须报 mismatch）；③ 哈希钉（`ci-subset.yml` 加 `env: RETRIEVAL_FIXTURE_SHA256`，与 `check_coverage.py` 的门槛写法同模式）。

**七、放 CI 第 6 步。** 现五步 → 插在「套件夹具」后、「覆盖率棘轮」前，成**第 5 步（共 6 步）**。覆盖率影响**正面**（新测试是 test 代码不进分母，但会执行 `rrf`/`accumulate`）；**不新增任何 main 类**。

**八、并入 `docs/CODE_MAP.md:91` 已挂的债。** `MarkdownChunkerTest` + `HybridRetriever` 融合用例与本票同交付（`rrf` 可见性本来就要动一次），**不单独开「补测运动」票**。

**九、诚实分界（本 ADR 最要紧的一条）**：它守的是**排序流水线的确定性与四个常数、语料 sha、夹具完整性**；**不覆盖活体 hit@5 准确率**，因此**不闭合 round19 登记第 5 项**（`round19-spec-…:132` 的触发原文是「出现可离线复跑的录制/回放路径」，而那要回放含模型的整条问答链，代价高一个量级）。

Considered Options:

- **在 `scripts/retrieval_gate.py` 里复算 RRF**：否决。两份实现就是两份判据，判据会分叉；而本仓的「判据只有一份」是显式纪律。
- **拿现有 16 条查询当夹具**：否决。它们 `dense` 与 `hybrid` 名次全同，做夹具对融合是 no-op —— 用一份量不出差别的夹具去守融合，等于给自己发一张绿灯。
- **在夹具里写死四个常数**：否决。写死就测不出「有人改了 yml」；本票要抓的恰恰是常数漂移。
- **覆盖活体 hit@5 数字进 CI**：否决（本轮不做）。那要录制含模型的整条链，已经越出「0 token、不依赖一次性活体读数」的筛子。
- **维持文档级（文件名前缀）判据**：否决。块级 hit@5 从未被测量，而 `ruleId` 才是检索的真实单元。

Consequences:

- **票 64 不闭合 round19 登记第 5 项**，这一点必须同时出现在 ADR 0048、本 ADR 与收口登记里；收口时**不许讲成「活体数字进 CI 了」**。
- 夹具是 append-only 家族：新增只能加新日期文件，不重命名、不清理旧产物（历史文件名是审计接口）。
- `rrf()` 的可见性变化不改变任何行为，只为让重算能在测试里被调用；若将来融合算法改实现，**夹具的输入（两路序）不变、`expected` 必须跟着新算法重录** —— 重录是一次证据口径变更，要在同一次提交里说明。
- 本票把 `CODE_MAP` 已挂的 `MarkdownChunkerTest` 一并交付，故那条债在收口时应更新为「已还」。
