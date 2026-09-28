# 68 — round22 收口

**What to build:** round22 的收口账：spec 登记节、`docs/EVIDENCE.md` 新读数、tracker round 表与票索引、`docs/CODE_MAP.md`（`knowledge` 与 CI 步：五步 → 七步）、**指标名重算并按仓库家法写换代指针**、收口审计 `ROUND_FP`/`G6_EXPECT` 换代。

**Blocked by:** 64、65、66、67。

**Status:** implemented（2026-09-28）。

口径：

- **指标名重算**：以现场 grep 现算为准（round21 收口为 **53**；票 66 只加标签不加名、票 64/65/67 预期不新增指标名 → 若如此则仍是 53，**照实登记，不为凑整改数**）。数法（三模块 `src/main` 现场 grep 去重）**一字不改**；旧值按仓库家法**原样供着 + 加换代指针**。
- **`docs/CODE_MAP.md`**：`knowledge` 行补录放门；CI 步从五步改为七步（本轮加检索录放门与告警测试两步）；`HybridRetriever` / `MarkdownChunker` 那笔债（`CODE_MAP.md:91`）应更新为**已还**（票 64 同交付）；Test Map 补新用例与新脚本。
- **`docs/EVIDENCE.md`**：新读数（JVM 计数、覆盖率、CI 步数）；`task_done` 这一列的公开口径；票 64 的**诚实分界**（不闭合 round19 登记第 5 项）；票 67 的**红线**（不声称生产会响）；未跑成项按未达成登记。
- **tracker**：round22 行转 done、票 64-68 索引补 Handoff、`interview-qa.md` 走**来源票 Handoff + 重跑生成器**（不手改生成物）。
- **收口审计常数换代**：`ROUND_FP` → round22 起点、`G6_EXPECT` → 现场 surefire 总数。**必须跑一次带 build 的矩阵**（审计 G6 读 `logs/acceptance/{build,unit}.log`；`-SkipBuild` 会让它读到旧日志而红 —— round21 踩过这个坑）。审计里 E5/E5b 的问答库计数也要换代。
- **一条判据都不改**；未达成项（活体 / gold）按实登记，不摘红。

- [x] 指标名按现场 grep 重算（**仍是 53**，票 66 只加标签不加名）+ README 当前口径确认
- [x] `docs/CODE_MAP.md` 同步（录放门/判据/告警三行 Test Map、`CODE_MAP.md:91` 那笔债转「已还」）
- [x] `docs/EVIDENCE.md`：新增 round22 行 + 「22 步」行改为 25 步（round21 起 23、round22 起 25）
- [x] tracker round22 转 done、票 64-68 Handoff；`collect_interview_questions.py` 重跑
- [x] 收口审计 `ROUND_FP`/`G6_EXPECT`/E5/E5b 换代；**带 build 的矩阵跑一次**并记录
- [x] `git diff --check` 干净、`git status --short` 无本机日志混入

**Verify**
```bash
grep -rhoE 'shoppilot_[A-Za-z0-9_]+' shoppilot-gateway/src/main shoppilot-biz-mock/src/main shoppilot-tool-api/src/main | sort -u | wc -l
python .scratch/shoppilot-mvp/round3-closeout-audit.py
git diff --check && git status --short
```

## Handoff notes

**关键决策**

- **指标名仍是 53，不换代。** 票 66 只给 `shoppilot_llm_tokens_total` 加 `source` 标签、不加名字；票 64/65/67 都不新增指标名。现场 grep 复算确认 —— 本轮**没有**换代指针要写，这本身就是结论（round21 是 52 → 53）。
- **「22 步」这个叫法改掉了。** 矩阵 round21 起是 23 步（+`refund`），round22 起是 **25 步**（再 +`task`/`funnel` 两条 0 token 步）。`docs/EVIDENCE.md` 的行标题与 tracker 的措辞同步；`run-acceptance.ps1` 的两步是 add-only。
- **`CODE_MAP.md:91` 那笔债标「已还」**：`MarkdownChunkerTest` 与 `HybridRetriever` 融合用例随票 64 同交付，不再挂账。
- **收口审计**：`ROUND_FP` 重锚到 `5697702`（round21 收口那一笔）、`G6_EXPECT` → `[5, 29, 304]`、E5/E5b 随问答库换代。**审计必须在带 build 的矩阵之后跑**（G6 读 `logs/acceptance/{build,unit}.log`）—— 本票就是按这个顺序跑的。
- **票 67 的 CI 步没有进本机矩阵**（它需要一个需下载的外部二进制；本机到发布 CDN 不可达）。矩阵里的 `task`/`funnel` 两步量的是脚本侧，promtool 那一步的本地替代见票 67 Handoff（用本机一个已在运行的 Prometheus 容器里的同版本二进制）。

**验证落点**

- 指标名现场复算：**53**（与 round21 收口一致）。
- 全量 `.\mvnw.cmd -B -ntp verify` → **`5 + 29 + 304 = 338`** 绿。
- 覆盖率棘轮：gateway **62.79%** / biz-mock 79.30% / tool-api 47.95%（门槛 54.0/76.0/40.0，未动）。
- 门禁：`verify_eval_judge.py` **40/40**、`eval_suites.py` **ok=24**、`eval_task.py` **ok=14**、`retrieval_gate.py` **自检 6/6 + ok cases=10**、离线 rescore 差异仍恰 4 条。
- 全量矩阵（**25 步**）：`logs/acceptance-run-20260928-134721.log`，**786 s、23 绿 / 2 红**（红 = `feedback`、`plansteps`，两条都是登记项）。新加的 `task`/`funnel` 两步绿。
- 收口审计：**PASS 85 / FAIL 2 / SKIP 8（共 95 项）**（`.scratch/shoppilot-mvp/round3-closeout-audit.txt`）。两条 FAIL 都照登、都不摘：① `A1 origin/main == HEAD` —— 本轮只提交到本机，**未推送**；② `F1c` —— round20 那次我删掉的历史本机日志不可逆，判据未动、未加豁免。本轮新绿的：`G6`（surefire `5 + 29 + 304 = 338`）、`E5`/`E5b`（问答库 **238/69**）。

**未达成（按实登记，不摘红）**

- **票 64 不闭合 round19 登记第 5 项**（活体 hit@5 准确率进 CI）—— 本轮只补上「排序确定性 + 四个常数 + 语料/夹具指纹」这一层。
- **票 65 的活体正例读数未取到**：本机无模型驻留（`cudaMalloc failed: out of memory`，四套项目容器抢显存），两条 model-dependent 用例落降级，判据如实判「没办成」。
- **票 67 的 CI 步未在干净 runner 上实跑**；且它按发布方 `sha256sums.txt` 校验下载物，而不是仓内硬编码 hash（本机到发布 CDN 不可达，拿不到那个值就**不猜**）。
- **spec §4 第 2-6 项仍是我的 best judgment 默认值**（ADR 结构 4 份 / promtool 用 curl 固定版本 / 告警 4 条 / 票 66 保留 / 顺序 65→67→64→66→68），**所有者尚未逐条确认** —— 本轮已按这些默认值实现并收口，改任一项需要连带改对应 ADR 与实现。

**你需要能当场回答的三个追问**

1. *Q：round22 补上的到底是什么？* A：补的是「谁在机器上守着这条链」。票 64 让融合排序有了确定性回归门（改坏 `rrf-k` 或换常数即红），票 65 让「任务有没有办成」第一次可机器判定，票 67 让告警规则第一次可被证明「该响时响」。**它们都不改判据、不改 gold、不改分母。**
2. *Q：为什么指标名没换代？* A：因为本轮没有任何一处新增指标名。票 66 加的是标签（名字不变），票 64/65/67 只加脚本、测试与规则文件。收口时现场 grep 复算 = 53，与 round21 一致 —— 这条结论要写出来，否则下一个人会以为忘了换代。
3. *Q：这轮最该被质疑的是哪一票？* A：**票 66**。ADR 0048 自己写明它最弱、可无损删：部署期模式固定，同一条序列里本来就不混计量方法，它换来的只是口径声明的机器可读性。若工期紧或评审认为不值，删掉它只损失一条用例与一个 tag。