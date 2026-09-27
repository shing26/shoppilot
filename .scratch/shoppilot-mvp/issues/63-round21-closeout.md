# 63 — round21 收口

**What to build:** round21 的收口账：spec 登记节、`docs/EVIDENCE.md` 新读数、tracker round 表与票索引、`docs/CODE_MAP.md`、收口审计 `ROUND_FP`/`G6_EXPECT` 换代、**指标名重算并按仓库家法写换代指针**。

**Blocked by:** 57-62。

**Status:** ready-for-agent

口径：

- **指标名重算一次**：票 57 修到 52，票 59 加 `shoppilot_refund_pending_total` 后为 **53**。数法（三模块 `src/main` 现场 grep 去重）**不变**；按 round14/round18 的换代处理写换代指针（**旧值原样供着 + 指针**，不篡改历史落点）。
- **`docs/CODE_MAP.md`**：`bizmock/service` 补审核、`agent` 补重放与 `ReplayReply`、Test Map 补新用例；跨模块工具 DTO 行同步 `ToolStatus` 新值。
- **`docs/EVIDENCE.md`**：新读数（JVM 计数、覆盖率、CI 三门禁）；活体未跑成项按未达成登记。
- **tracker**：round21 行转 done、票 59-63 索引补 Handoff、`interview-qa.md` 走**来源票 Handoff + 重跑生成器**（不手改生成物）。
- **收口审计常数换代**：`ROUND_FP` / `G6_EXPECT`。
- 一条判据都不改；未达成项（活体/gold）按实登记，不摘红。

- [ ] 指标名按现场 grep 重算 = 53，README/interview-qa 当前口径换代 + 历史落点加指针
- [ ] `docs/CODE_MAP.md` 同步（bizmock/service 审核、agent 重放与 `ReplayReply`、Test Map、跨模块 DTO 行）
- [ ] `docs/EVIDENCE.md` 新读数与证据边界
- [ ] tracker round21 转 done、票 59-63 Handoff；`collect_interview_questions.py` 重跑
- [ ] 收口审计 `ROUND_FP` / `G6_EXPECT` 换代
- [ ] `git diff --check` 干净、`git status --short` 无本机日志混入

**Verify**
```bash
grep -rhoE 'shoppilot_[A-Za-z0-9_]+' shoppilot-gateway/src/main shoppilot-biz-mock/src/main shoppilot-tool-api/src/main | sort -u | wc -l   # 53
git diff --check && git status --short
```