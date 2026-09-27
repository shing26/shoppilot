# 63 — round21 收口

**What to build:** round21 的收口账：spec 登记节、`docs/EVIDENCE.md` 新读数、tracker round 表与票索引、`docs/CODE_MAP.md`、收口审计 `ROUND_FP`/`G6_EXPECT` 换代、**指标名重算并按仓库家法写换代指针**。

**Blocked by:** 57-62。

**Status:** implemented（2026-09-28）。

口径：

- **指标名重算一次**：票 57 修到 52，票 59 加 `shoppilot_refund_pending_total` 后为 **53**。数法（三模块 `src/main` 现场 grep 去重）**不变**；按 round14/round18 的换代处理写换代指针（**旧值原样供着 + 指针**，不篡改历史落点）。
- **`docs/CODE_MAP.md`**：`bizmock/service` 补审核、`agent` 补重放与 `ReplayReply`、Test Map 补新用例；跨模块工具 DTO 行同步 `ToolStatus` 新值。
- **`docs/EVIDENCE.md`**：新读数（JVM 计数、覆盖率、CI 三门禁）；活体未跑成项按未达成登记。
- **tracker**：round21 行转 done、票 59-63 索引补 Handoff、`interview-qa.md` 走**来源票 Handoff + 重跑生成器**（不手改生成物）。
- **收口审计常数换代**：`ROUND_FP` / `G6_EXPECT`。
- 一条判据都不改；未达成项（活体/gold）按实登记，不摘红。

- [x] 指标名按现场 grep 重算 = 53，README/interview-qa 当前口径换代 + 历史落点加指针
- [x] `docs/CODE_MAP.md` 同步（bizmock/service 审核、agent 重放与 `ReplayReply`、Test Map、跨模块 DTO 行）
- [x] `docs/EVIDENCE.md` 新读数与证据边界
- [x] tracker round21 转 done、票 59-63 Handoff；`collect_interview_questions.py` 重跑
- [x] 收口审计 `ROUND_FP` / `G6_EXPECT` 换代
- [x] `git diff --check` 干净、`git status --short` 无本机日志混入

**Verify**
```bash
grep -rhoE 'shoppilot_[A-Za-z0-9_]+' shoppilot-gateway/src/main shoppilot-biz-mock/src/main shoppilot-tool-api/src/main | sort -u | wc -l   # 53
git diff --check && git status --short
```

## Handoff notes

**关键决策**

- **指标名换代：52 → 53**（票 59 加 `shoppilot_refund_pending_total`）。数法（三模块 `src/main` 现场 grep 去重）**一字未改**；`README.md` 的当前口径改数 + 加换代指针，`README.md:852` 的 round14 历史落点读数 **41 保持原样**（只在其换代指针里续上「round21 后为 53」）。这与票 57 的处置同形。
- **`docs/CODE_MAP.md` 三处**：`agent` 行补「请求级幂等回放 + 退款受理出口」与 `ReplayReply`；`bizmock/service`/`bizmock/web`/`tool/view` 行补审核与 `PENDING_APPROVAL`/`RefundReviewState`；请求主链路补「请求级幂等回放」「退款审核」两行；Test Map 补 4 行；**`AgentStateMachine` 的代码债行数从「约 665」更正为「约 938」**（round21 实测；round19 起就涨了，本票只是把数字改对），并记明 round21 的两处新增抽成了 helper、话术外置 `ReplayReply`，是**不加重**这笔债。
- **`CONTEXT.md` 只增词、不改既有条目**（ADR 0047 决策八）：新增**退款审核 / 待审核 / 受理 vs 放行**三条，每条都点名与既有「复核队列」「待办动作」的区别。
- **收口审计重锚**：`ROUND_FP` `e458677` → `c28eb04`（round20 收口那一笔）、`G6_EXPECT` `[3, 21, 276]` → `[5, 29, 290]`。
- **`docs/EVIDENCE.md`**：覆盖率棘轮行加换代指针（旧值 55.37/77.49/41.73 原样供着）；「22 步全量活体验收」行标题改为「round21 起 23 步」并补本轮两次矩阵落点；新增 round21 汇总行。

**验证落点（本机实跑）**

- 指标名：现场 grep 去重 = **53**。
- JVM：`.\mvnw.cmd -B -ntp verify` → **`5 + 29 + 290 = 324`** 绿。
- 覆盖率棘轮：gateway **59.75%** / biz-mock **79.30%** / tool-api **47.95%**（门槛 54.0/76.0/40.0，未动）。
- 门禁：`verify_eval_judge.py` **40/40**、`eval_suites.py` **ok=24**、离线 rescore **cases=180 files=6 tool_diff=4**。
- 活体：`verify-refund-approval.ps1` **7/7**、`verify-idempotency.ps1` **exit 0**、`verify-console.mjs` **40/40**、`verify-action-loop.ps1` **11/11**、全量矩阵 **606 s、21 绿 / 2 红**（红 = `feedback`、`plansteps`，均登记）。
- `git diff --check` 干净。

**未达成（按实登记，不摘红）**

- **gold 180 条活体重跑未做**（需 dev 额度）：票 58/59/60 对 gold 的影响都只有静态依据，**不得声称 gold 未漂移**。
- **本地 3B 的读回措辞缺口**（见票 62）：对「我那退款到哪了」判 `ACTION_REFUND` 后不调 `queryOrderDetail`。
- **`stack` 步本机内存压力下构建期 OOM**：第二次矩阵改 `-SkipStack` 复用已起的栈，故 606 s 那次没有 stack 读数。
- **收口审计**：读数 **PASS 85 / FAIL 2 / SKIP 8（共 95 项）**（`.scratch/shoppilot-mvp/round3-closeout-audit.txt`）。两条 FAIL 都照登、都不摘：① `A1 origin/main == HEAD` —— **本轮只提交到本机，未推送**；② `F1c` —— round20 那次我删掉的历史本机日志不可逆，**判据未动、未加豁免**。本轮新绿的三条：`E5`/`E5b`（问答库 202/57 → **223/64**）与 `G6`（surefire `5 + 29 + 290 = 324`；该条读 `logs/acceptance/{build,unit}.log`，所以必须跑**一次带 build 的矩阵**而非 `-SkipBuild`）。
- **一处我自己撤回的错**：`eval/results/tool-eval-20260928-040944-rescore.csv` 曾随票 59 入库，是错的——审计 D9/D10 的规矩是「复算结果与 stamped 基线字节级相同、盘上只留那一份」（见 `docs/EVIDENCE.md` 的 round21 行）。已 `git rm` 撤回。

**你需要能当场回答的三个追问**

1. *Q：为什么 JVM 从 `3 + 21 + 276` 涨到 `5 + 29 + 290`，涨得最多的是 biz-mock？* A：biz-mock 的 `reviewRefund` 与审核控制器是票 59 的新代码，而它的覆盖率余量当时只有 1.49pp，所以配套了 `RefundReviewTest` 8 条（三态迁移、推导回滚、跨租户、重复审核 409、未知决定 400）；tool-api 的 2 条覆盖新枚举与声明式审批策略；gateway 的 14 条里 10 条属票 58（回放）。
2. *Q：为什么要在收口时把 `AgentStateMachine` 的债务行数从 665 改成 938？* A：那是核对时发现的**过期数字**（round19 的票 48/49 就把它推高了，一直没更正）。把它改对不是美化——它正是「这笔债还在长」的证据；本票同时记明 round21 的新增是用 helper 与外置渲染器做的，没有把这笔债推得更重。
3. *Q：round21 结束了，`docs/PROJECT_PLAN.md` 要不要改？* A：不需要。本轮不改交付方向与冻结线；round21 是 ADR 0046 覆盖的一次政策覆盖轮，收口即回到 ADR 0031 机制，**不自动续期**（ADR 0046 的 Consequences 已写明）。