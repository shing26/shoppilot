# 68 — round22 收口

**What to build:** round22 的收口账：spec 登记节、`docs/EVIDENCE.md` 新读数、tracker round 表与票索引、`docs/CODE_MAP.md`（`knowledge` 与 CI 步：五步 → 七步）、**指标名重算并按仓库家法写换代指针**、收口审计 `ROUND_FP`/`G6_EXPECT` 换代。

**Blocked by:** 64、65、66、67。

**Status:** ready-for-agent

口径：

- **指标名重算**：以现场 grep 现算为准（round21 收口为 **53**；票 66 只加标签不加名、票 64/65/67 预期不新增指标名 → 若如此则仍是 53，**照实登记，不为凑整改数**）。数法（三模块 `src/main` 现场 grep 去重）**一字不改**；旧值按仓库家法**原样供着 + 加换代指针**。
- **`docs/CODE_MAP.md`**：`knowledge` 行补录放门；CI 步从五步改为七步（本轮加检索录放门与告警测试两步）；`HybridRetriever` / `MarkdownChunker` 那笔债（`CODE_MAP.md:91`）应更新为**已还**（票 64 同交付）；Test Map 补新用例与新脚本。
- **`docs/EVIDENCE.md`**：新读数（JVM 计数、覆盖率、CI 步数）；`task_done` 这一列的公开口径；票 64 的**诚实分界**（不闭合 round19 登记第 5 项）；票 67 的**红线**（不声称生产会响）；未跑成项按未达成登记。
- **tracker**：round22 行转 done、票 64-68 索引补 Handoff、`interview-qa.md` 走**来源票 Handoff + 重跑生成器**（不手改生成物）。
- **收口审计常数换代**：`ROUND_FP` → round22 起点、`G6_EXPECT` → 现场 surefire 总数。**必须跑一次带 build 的矩阵**（审计 G6 读 `logs/acceptance/{build,unit}.log`；`-SkipBuild` 会让它读到旧日志而红 —— round21 踩过这个坑）。审计里 E5/E5b 的问答库计数也要换代。
- **一条判据都不改**；未达成项（活体 / gold）按实登记，不摘红。

- [ ] 指标名按现场 grep 重算（预期 53，照实登记）+ README 当前口径换代与历史落点指针
- [ ] `docs/CODE_MAP.md` 同步（knowledge 录放门、CI 七步、`CODE_MAP.md:91` 那笔债转「已还」、Test Map）
- [ ] `docs/EVIDENCE.md`：新读数 + `task_done` 口径 + 票 64/67 的两条红线措辞
- [ ] tracker round22 转 done、票 64-68 Handoff；`collect_interview_questions.py` 重跑
- [ ] 收口审计 `ROUND_FP`/`G6_EXPECT`/E5/E5b 换代；**带 build 的矩阵跑一次**并记录
- [ ] `git diff --check` 干净、`git status --short` 无本机日志混入

**Verify**
```bash
grep -rhoE 'shoppilot_[A-Za-z0-9_]+' shoppilot-gateway/src/main shoppilot-biz-mock/src/main shoppilot-tool-api/src/main | sort -u | wc -l
python .scratch/shoppilot-mvp/round3-closeout-audit.py
git diff --check && git status --short
```