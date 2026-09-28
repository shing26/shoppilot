# 64 — 检索融合 0 token 录放回归门（并入 Chunker/Retriever 聚焦单测）

**What to build:** 给「双路召回 → RRF 融合 → 取 top-5」装一道确定性回归门。今天**改坏融合没有任何一条断言会红**：CI 的 24 条夹具四个 kind 都不读 `ruleIds`/RRF 序，唯一那份 RAG 证据的 16 条查询 `dense` 与 `hybrid` 名次全同（把 `rrf-k` 从 60 改成 30，CI 全绿）。本票用「录下两路序 + 重算融合 + 断言 top-K 前缀」把这条链钉进 CI。

**Blocked by:** None（可立即开始）。

**Status:** ready-for-agent

**依据：政策越过（ADR 0048）。** `round19-spec-trust-observability.md:132` 的触发原文是「出现可离线复跑的录制/回放路径」，而**建这条路径就是本票本身** —— 引自己当依据是循环论证，**收口时不许讲成「登记第 5 项的触发已成立」**。

口径（ADR 0049 已立契，本票只执行）：

- **诚实分界（本票最要紧的一条）**：守的是**排序流水线的确定性与四个常数、语料 sha、夹具完整性**；**不覆盖活体 hit@5 准确率**，因此**不闭合 round19 登记第 5 项**。
- **层一（JVM，唯一 owner）**：新增 `RetrievalFusionReplayTest` —— 由夹具的 `dense`/`lexical` **重算**融合，断言 top-K 前缀 == `expected_fused_topk`；四个常数从**生产 `application.yml`** 绑定（改 yml 即红）；`corpus_sha256` 现算相等（改任一 `knowledge/*.md` 即红）。为此把 `HybridRetriever.rrf()`（`:198`）改**包级可见**（零行为改动）。
- **层二（Python，绝不复算 RRF）**：`scripts/retrieval_gate.py` 只做夹具 schema、`expected ⊆ dense ∪ lexical`、sha 与 CI pin 校验。**在 Python 里复算融合等于写出第二份实现、也就是第二份判据**，违反「判据只有一份」。
- **夹具 append-only**：`eval/retrieval-fixture-<date>.json`；录制用现成 `/ops/retrieval` 探针（`OpsController.java:268`，一次调用同时返回 `denseTop`/`lexicalTop`/`fusedTop`）；pin `llm_mode`/`kb_epoch`/`corpus_sha256`/四个常数。
- **查询集必须新增「能造分歧」的 case**：原 16 条对融合是 no-op（这正是要修的病）。候选：跨店近重复条款（`return-07-shop-t001-window` vs `return-08-shop-t002-fresh` vs 平台级 `return-01-7day-basic`）、同主题相邻的 `shipping-01..07`。
- **判据粒度升到 `ruleId` 级**：现有 `retrieval_compare.py:79` 按**文件名前缀**判（文档级）；`ruleId` 才是检索的真实单元，也补上「块级 hit@5 从未被测量」这一格。
- **三道防假绿**：① 重算式断言（只改 `expected` 必红）；② 反证夹具（喂打乱后的输入必报 mismatch）；③ 哈希钉（`ci-subset.yml` 加 `env: RETRIEVAL_FIXTURE_SHA256`，与 `check_coverage.py` 的门槛写法同模式）。
- **CI 放第 5 步**：插在「套件夹具」后、「覆盖率棘轮」前（现五步 → 共 6 步）。覆盖率影响**正面**（新测试是 test 代码不进分母，但会执行 `rrf`/`accumulate`）；**不新增任何 main 类**。
- **并入 `docs/CODE_MAP.md:91` 已挂的债**：`MarkdownChunkerTest` + `HybridRetriever` 融合用例与本票同交付（`rrf` 可见性本来就要动一次），**不单独开「补测运动」票**。
- **与票 67 同改 `ci-subset.yml`**：两票各加一步，逻辑上互不阻塞，但别并行改同一文件。

- [ ] `rrf()` 改包级可见（零行为改动）；`RetrievalFusionReplayTest` 由两路序重算融合并断言 top-K 前缀
- [ ] 四个常数从生产 `application.yml` 绑定；`corpus_sha256` 现算
- [ ] 夹具 `eval/retrieval-fixture-<date>.json`：append-only、pin 四项元数据、**含新增的能造分歧 case**
- [ ] `scripts/retrieval_gate.py`：只做 schema/子集/sha/pin 校验，**不复算 RRF**
- [ ] `MarkdownChunkerTest` 与 `HybridRetriever` 融合用例同交付（还 `CODE_MAP.md:91` 那笔债）
- [ ] 反证夹具：喂打乱后的输入必报 mismatch
- [ ] CI 插入第 5 步 + `RETRIEVAL_FIXTURE_SHA256` 哈希钉
- [ ] 全量 `verify` 绿；`check_coverage.py` exit 0

**Verify**
```bash
./mvnw.cmd -B -ntp verify
python scripts/retrieval_gate.py
python scripts/check_coverage.py
```