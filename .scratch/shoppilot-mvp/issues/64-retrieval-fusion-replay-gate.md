# 64 — 检索融合 0 token 录放回归门（并入 Chunker/Retriever 聚焦单测）

**What to build:** 给「双路召回 → RRF 融合 → 取 top-5」装一道确定性回归门。今天**改坏融合没有任何一条断言会红**：CI 的 24 条夹具四个 kind 都不读 `ruleIds`/RRF 序，唯一那份 RAG 证据的 16 条查询 `dense` 与 `hybrid` 名次全同（把 `rrf-k` 从 60 改成 30，CI 全绿）。本票用「录下两路序 + 重算融合 + 断言 top-K 前缀」把这条链钉进 CI。

**Blocked by:** None（可立即开始）。

**Status:** implemented（2026-09-28）。

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

- [x] `rrf()` 改包级可见（零行为改动）；`RetrievalFusionReplayTest` 由两路序重算融合并断言 top-K 前缀
- [x] 四个常数从生产 `application.yml` 绑定；`corpus_sha256` 现算
- [x] 夹具 `eval/retrieval-fixture-<date>.json`：append-only、pin 四项元数据、**含新增的能造分歧 case**
- [x] `scripts/retrieval_gate.py`：只做 schema/子集/sha/pin 校验，**不复算 RRF**
- [x] `MarkdownChunkerTest` 与 `HybridRetriever` 融合用例同交付（还 `CODE_MAP.md:91` 那笔债）
- [x] 反证夹具：喂打乱后的输入必报 mismatch
- [x] CI 插入第 5 步 + `RETRIEVAL_FIXTURE_SHA256` 哈希钉
- [x] 全量 `verify` 绿；`check_coverage.py` exit 0

**Verify**
```bash
./mvnw.cmd -B -ntp verify
python scripts/retrieval_gate.py
python scripts/check_coverage.py
```

## Handoff notes

**关键决策**

- **重算调的是生产那一份 `rrf()`，不是测试里的复制品。** 为此把 `rrf` 与 `Scored` 放开到包级可见（零行为改动）。**在测试里另写一份融合等于造出第二份判据** —— 与 ADR 0049 否决「Python 复算 RRF」是同一条理由，只是换了个语言。
- **夹具的 `dense`/`lexical` 是输入、`fused` 是输出；用例由输入重算输出再比对。** 所以「只改夹具里的 `fused`」必红 —— 这是第一道防假绿，不需要额外写一条断言去证明。
- **四条断言各守一处**：① 由输入重算 == `fused` 前缀；② **夹具里必须有 top-5 分歧**（没有就说明这份夹具对融合是 no-op —— 原 16 条查询正是这个毛病，所以这条断言直接写进了用例）；③ 反转两路序必须得到不同结果（证明断言对输入敏感，不是恒真）；④ 四个常数与语料 sha 现算比对（改 `application.yml` 或改任一 `knowledge/*.md` 即红）。
- **常数用 Spring 自己的 `Binder` 读生产 yml**（`YamlPropertySourceLoader` + `Binder.bind`），不是正则抄一份 —— yml 写坏了这里也会红。
- **Python 层刻意只校验、不复算**：schema、`fused ⊆ dense ∪ lexical`、语料 sha、常数与 yml 一致，外加 CI 的哈希钉。CI 步排在套件夹具之后、覆盖率棘轮之前（ADR 0051 的顺序）。
- **录制的三道拒绝**（见下「一处实测踩到的坑」）：任一路为空/`degraded`、`intentFilterRelaxed` 为真、同查询两次不一致 —— 三种情况都**拒绝录制**，因为录一份降级或不确定的夹具等于给融合门发一张假绿灯。本机 bge-m3 会被别的项目容器挤掉显存，偶发 `degraded`，故录制脚本对**环境抖动**重试三次（仍拿不到干净的一份就拒绝），前置本身没有放宽。

**一处实测踩到的坑（值得下一个人记住）**：我最初用 `curl -G --data-urlencode` 打 `/ops/retrieval`，**中文查询被 shell 的编码搞坏了**，于是每一条都返回 `lexicalTop=[]` 而 `denseTop` 有 20 条，看起来像「词法那一路坏了、RRF 退化成 dense-only」。逐层查下去才发现：**Qdrant 那一路拿到的是向量（不经文本），任何串都能返回最近邻；ES 那一路拿的是文本，查询文本坏了就一条都匹配不上**。换成 urllib（正确的 UTF-8 客户端）后 `lexical=20`、`degraded=false`，两路名次真有分歧。**结论：驱动这个探针必须用能保证 UTF-8 的客户端；用 curl 打中文查询会把「自己发坏了」误读成「系统坏了」。**

**验证落点**

- 全量 `.\mvnw.cmd -B -ntp verify` → **`5 + 29 + 301 = 335` 绿**（gateway 290 → 301：`RetrievalFusionReplayTest` 5 条 + `MarkdownChunkerTest` 6 条）。
- 覆盖率棘轮 exit 0：gateway LINE 59.75% → **61.95%**（新用例真的执行到了 `rrf`/`accumulate` 与切块器）；biz-mock 79.30%、tool-api 47.95%。
- 录放门：`RetrievalFusionReplayTest` **5/5**；用例会打印「N/M 条 case 在 top-5 上 dense≠fused」（本夹具 **10/10**）。
- `python scripts/retrieval_gate.py` → `GATE SELFCHECK ok=6` + `RETRIEVAL GATE ok cases=10`；**哈希钉实测**：给错的 pin → 红；给对的 pin → 绿。
- 夹具：`eval/retrieval-fixture-20260928.json`（10 条 case，`kb_epoch=4`、`llm_mode=local`、constants `{rrfK:60, denseTopK:20, lexicalTopK:20, fusedTopK:5}`、`corpus_sha256` 现算一致），sha256 `4e2a4a5c…`（已 pin 进 `ci-subset.yml`）。
- `CODE_MAP.md:91` 那笔债（`MarkdownChunkerTest`）**随本票还清** —— 票 68 收口时应把它从债列表移走。

**未达成（按实登记，不摘红）**

- **不闭合 round19 登记第 5 项**（活体 hit@5 准确率进 CI）：本门只覆盖排序确定性与四个常数、语料 sha、夹具完整性。这一条同时写在 ADR 0048 / 0049 与本行，收口时**不许**讲成「活体数字进 CI 了」。
- **CI 那一步未在干净 runner 上实跑**（本机已跑通同一命令）。
- **夹具是单次录制**：语料或常数一改就要重录（重录会改 `corpus_sha256` 与哈希钉，属**证据口径变更**，要在同一次提交里说明并把新 sha 填进 `ci-subset.yml`）。本机 bge-m3 的显存争抢会让录制偶发失败 —— 重试或换一台空闲机器。

**你需要能当场回答的三个追问**

1. *Q：为什么重算放在 JVM 而不放在 Python？* A：判据只有一份。放两份实现（Java 与 Python）必然分叉，而分叉的那天没人知道该信哪一份。Python 只做它独有的四件事（schema、子集、语料 sha、常数核对）；重算交给能直接调用生产 `rrf()` 的那一侧。
2. *Q：为什么要在用例里断言「夹具必须含分歧」？* A：因为本票要修的正是「夹具对融合判别力为零」这个病。原 16 条查询按文件名前缀比时 dense 与 hybrid 名次全同 —— 拿它守融合，改坏 `rrf-k` 也不会红。把这条写成断言，等于让「夹具退化成 no-op」这件事本身变成一次红灯。
3. *Q：这个门守住了什么、没守住什么？* A：守住了**排序流水线的确定性**（同样的两路序必然给出同样的融合序）、**四个常数没被悄悄改**、**语料没被悄悄换**、**夹具没被悄悄换**（哈希钉）。没守住**活体 hit@5 准确率** —— 那要连模型一起回放，代价高一个量级，仍挂在 round19 登记第 5 项上。