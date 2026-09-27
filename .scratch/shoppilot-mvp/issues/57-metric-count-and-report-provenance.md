# 57 — 指标名计数换代（41 → 52）+ 两份活体报告加 provenance 表头

**What to build:** 修一处**纯事实错误**，并补两处证据卫生。`README.md:199` 与 `docs/interview-qa.md:574` 都写「41 个唯一 `shoppilot_*` 指标名」，而按它们自己写明的数法（对三个模块 `src/main` 做 `grep -oE 'shoppilot_[A-Za-z0-9_]+'` 再去重）实算是 **52**。仓内甚至已经自相矛盾：`round18-spec-scoring-dimension-completeness.md:14` 在 round18 时点就记「**51 个指标名 / 59 个注册点**」——说明 README 的 41 **从 round18 起就落后**，round19 又加了 `shoppilot_embedding_latency_seconds` 变成 52。

**Blocked by:** None。

**Status:** implemented（2026-09-28）。

**依据：ADR 0031 第 10 行的事实修正豁免**（不改任何判据/阈值/gold/分母，不改被测功能行为）。ADR 0046 已记明本票**不需要**政策覆盖。

口径：

- **只改当前口径的两处**：`README.md:199`、`docs/interview-qa.md:574` 的 `41` → **`52`**。**数法描述一字不改** —— 口径（现场 grep 现算 + 写明数法）本身是可复核的正确设计，坏的是数没跟着换代。
- **`README.md` 里 round14 落点段（`commit=8c4b616`）那句「指标名按三模块 `src/main` 去重后为 41」保持 41 不动，只在其后加换代指针。** 那是**历史落点读数**，按 ADR 0021「两套读数并列」与仓库「旧值原样供着 + 换代指针」的家法，改成 52 等于篡改历史记录。指针内容：round18 时点 51 / round19 加 embedding 计时器后 52 / 当前口径见上文。
- **两份活体报告加 provenance 表头，不重生成正文**：`scripts/retrieval_compare.py` 与 `scripts/calibrate_threshold.py` 的报告表头各加一行元数据（`generated_from_commit` / `corpus_sha256` / `kb_epoch` / `llm_mode`）。重生成正文会改读数，属证据口径变更，不在本票内。
- **已核连锁引用**：`docs/EVIDENCE.md` 与 `docs/loadtest-report.md` 对「41」**无命中**，只有上列两处需要改。

- [x] `README.md:199` 的 41 → 52，数法描述不动
- [x] `docs/interview-qa.md:574` 的 41 → 52（**改的是它的来源票 30 的 Handoff，再重跑生成器**，不手改生成物）
- [x] `README.md` 历史落点段保持 41，加换代指针（**不改数**）
- [x] 新增 `scripts/provenance.py`（算 commit + `knowledge/` 语料 sha256）；两个报告脚本各接一行表头（只加表头行）
- [x] 机器复核：按 README 自述数法实算 = 52；旧口径声明只剩票 57 自身与 README 的历史落点（后者是刻意的）
- [x] `python -m py_compile` 三个脚本通过；`git diff --check` 干净

## Handoff notes

**关键决策**

- **`docs/interview-qa.md` 是生成物，不能直接改。** 它的来源是各 ticket 的 `## Handoff notes` 里的三个追问，生成器是 `scripts/collect_interview_questions.py`。那句话说错的地方在 `issues/30-…md:29`（票 30 的 Handoff），所以修法是**改源头 + 重跑生成器**；直接改 `docs/interview-qa.md` 会在下次重生成时被覆盖，也会让生成物与来源不一致（这是本仓「判据只有一份」的同一形状）。校正后的措辞保留了历史：当前 52，票 30 当时 41，round18 时点 51。
- **`README.md:852` 的 41 是历史读数，按家法不改数、只加换代指针。** 这在分域调研里曾被提为"改成 52"，主控核到那一行绑着 `commit=8c4b616` 的落点描述后推翻了这个建议 —— 改它等于篡改历史记录，而仓库对"旧读数"的一贯处置是**原样供着 + 加指针**（ADR 0021、票 47/51 都这么做过）。
- **provenance 表头只钉它能证实的两个维度。** `commit` 与 `corpus_sha256` 由 `scripts/provenance.py` 现算（语料指纹 = `knowledge/*.md` 按文件名排序后拼接取 sha256，语料一改就变），`kb_epoch` 与 `llm_mode` 只有连得到网关的脚本才拿得到：`retrieval_compare.py` 从探针响应的 `kbEpoch` 与 `/ops/switches` 的 `llmMode` 读，`calibrate_threshold.py` **直连 Ollama、不经网关**，所以这两格写 `-` 而不是猜一个值。**"拿不到就写 `-`"是刻意的**：provenance 表头的价值全在可信，凑一个值比空着更糟。
- **顺手加了一道纪元守卫**：`retrieval_compare.py` 采集期间若 `kbEpoch` 发生变化（说明有人 bump 了纪元），直接 `FAIL` 退出 —— 那种报告混了两个语料版本，比不了。这与该脚本既有的 `degraded` 守卫同形（"这一行比的是故障不是算法"）。
- **`kb_epoch=0` 必须当成有效值**，所以 `provenance.line()` 用 `is not None` 判而不是真值判 —— 已用单测式调用验过（`line(kb_epoch=0)` 输出 `kb_epoch=0`，不是 `-`）。
- **顺带补了 `.gitattributes` 的三条 `whitespace=cr-at-eol` 豁免。** 两个报告脚本是 CRLF（`calibrate_threshold.py` 的 index 全 CRLF、`retrieval_compare.py` 的 index 是历史混合行尾），`git diff --check` 会把新增的 CRLF 行报成 trailing whitespace。这不是新问题：仓库早就为 `run_tool_eval.py` / `build_eval_set.py` / `verify_eval_judge.py` 加过**同一理由**的豁免（`.gitattributes:17-21` 的注释逐字写着这件事）。所以处置是**照同一做法加豁免、不改存储** —— 而不是把新行改成 LF（那会让 `retrieval_compare.py` 这个本就混合行尾的文件更混）。

**验证落点**

- 机器复核（本机实跑）：`grep -rhoE 'shoppilot_[A-Za-z0-9_]+' shoppilot-gateway/src/main shoppilot-biz-mock/src/main shoppilot-tool-api/src/main | sort -u | wc -l` → **52**（与 README 新写的数一致）。
- 遗留声明扫描：`grep -rn '41 个唯一\|去重为 41\|字面值去重为 41'` → 只剩票 57 自身与 README 的历史落点（后者刻意保留）。
- `python -m py_compile scripts/provenance.py scripts/retrieval_compare.py scripts/calibrate_threshold.py` → OK。
- `provenance.line()` 三态调用：带 `kb_epoch/llm_mode`、不带、`kb_epoch=0` —— 均正确。
- `git diff --check` 干净。
- **未做**：两个报告正文**未重生成**（重生成会改读数，属证据口径变更，不在本票范围）。所以 `docs/retrieval-comparison.md` 与 `docs/threshold-calibration.md` 现在**还没有**表头 —— 表头要等下次真跑那两个脚本时才出现。**这是本票的已知未达成，不得声称"报告已有 provenance"。**

**你需要能当场回答的三个追问**

1. *Q：为什么 `docs/interview-qa.md` 不直接改，绕道去改一张已收口的票？* A：因为它是**生成物**（`scripts/collect_interview_questions.py` 从各 ticket 的 Handoff 生成）。直接改生成物有两个坏处：下次重生成会被覆盖（改动静默消失），以及生成物与来源不一致（同一句话说两遍就有一遍是错的）。改源头再重跑是唯一能让"来源 → 生成物"这条链保持单一真相的做法。
2. *Q：`README.md:852` 那个 41 为什么不一起改成 52？* A：那一行绑着 `commit=8c4b616` 那次落点的描述，是**历史读数**。这个仓库对旧读数的一贯处置是"原样供着 + 加换代指针"（ADR 0021 的「两套读数并列」）——把历史读数改成现值，读者就再也看不出它是哪一代的数；而保留指针既能读到当时的值，也能顺着指针走到当前值。判据与"当前口径声明"是两类东西，混改会把审计线抹掉。
3. *Q：provenance 表头里 `档位=-` 和 `kb_epoch=-` 是什么意思，是不是没做完？* A：不是。`calibrate_threshold.py` **直连 Ollama、不经网关**，它拿不到网关的档位与知识纪元——这两格写 `-` 是"该维度对本脚本不适用"，不是"忘了填"。**表头的价值全在可信**，所以拿不到的维度宁可留空也不猜一个值。另一个脚本（`retrieval_compare.py`）走网关，所以它两格都填得上。

