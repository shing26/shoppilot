# 票 230 契约：round60 项目收尾（文档换代 + 全量验收 + 发布判定）

- Round: 60
- 类型: 收尾 / 验收 / 文档（无新功能面）
- 开票依据: 所有者 2026-11-25 指令「开一张 round60 收尾票」；票 01-229 已全部收口、
  frontier 为空，但交付层仍有未收尾项（见下）
- 冻结时间: 2026-11-25
- Implementer: 待领取（独立分支 `codex/230-round60-closeout`）
- Verifier: 独立 subagent；不得与实现方同会话；命令由 Verifier 亲跑

## 问题（开票时事实）

1. **发布未换代**：`v1.0.0` tag 指向 `7f4334c`，之后已有 **400+ 个提交**
   （计数随提交增长，以 `git rev-list --count v1.0.0..HEAD` 为准）；
   没有新 tag/release，`pom.xml` 仍是 `0.1.0-SNAPSHOT`。
2. **交付文档过期**：`DELIVERY.md` 的「当前状态」停在 2026-10-19
   （200 个提交 / 577 tests / 票 01-150）；`docs/PROJECT_PLAN.md` 是历史计划；
   对外入口会读到旧数字。
3. **全量验收未在当前 HEAD 重跑**：29 步 full 档最近落点是 2026-10-19 的
   `0bfb400`（577 tests）；当前 HEAD `19ebd12` 已是 878 tests / 144 suites。
   224/225/228/229 只跑了定向活体。
4. **U2 未闭环**：完整 `up.ps1` 链路下 biz-mock 演示口令投放尚未复跑；
   票 229 已封堵投放链路，但 AC5 明确保留 U2。
5. **指标与欠账仍在账上**：工具调用准确率 `179/180 = 99.4%` 因 `ACT-ORD-14`
   单点仍判「未达成」；吞吐「独立发压节点复核待做」；活跃登记项
   R1/R3/R5/R6/R8/R10/R15，未达成 U1/U2/U3/U8/U10/U11。

## 目标

把「票务收尾」升级为「可交付收尾」：文档真值、全量活体验收、欠账裁决、
发布判定四件事一次做完，并给出可复跑的收尾记录。

**不新增功能、不改判据**；只做事实核对、文档换代、验收复跑与所有者裁决落地。

## Allowed paths

- `plans/230-round60-closeout.md`
- `.scratch/shoppilot-mvp/issues/230-round60-closeout.md`
- `.scratch/shoppilot-mvp/README.md`
- `.scratch/shoppilot-mvp/registered-debt.md`
- `docs/EVIDENCE.md`
- `DELIVERY.md`
- `RELEASE.md`
- `CHANGELOG.md`
- `docs/PROJECT_PLAN.md`（只加历史 / 现状指针，不改历史段落）
- `README.md`（仅当数字、链接或状态口径漂移时）
- `docs/STATUS.md`（只由 `python scripts/gen_status.py` 生成，不手改）
- `pom.xml`（仅当所有者选定发布版本时）
- `scripts/demo.py`（仅当版本号变更导致 `--prebuilt` 硬编码 jar 名失效时）
- `docs/HANDOFF-20261125.md`（新建收尾交接，可选）

## Acceptance criteria

- **AC1 文档换代**：`DELIVERY.md` 状态段更新到 2026-11-25 / 当前 HEAD /
  `v1.0.0` 之后 400+ 个提交 / 878 tests / 144 suites / 票 01-229 收口 /
  frontier {230}；`RELEASE.md` 增加「`v1.0.0` 之后未发布」指针与发布判定入口；
  `docs/PROJECT_PLAN.md` 的历史指针保持不误导；`gen_status --check` 与
  `verify_doc_truth` 全绿。
- **AC2 全量验收**：在最终 HEAD 跑 `run-acceptance.ps1 -Tier full`（29 步），
  记录日志路径、绿/红数、耗时、HEAD；每条红必须归因到已登记项或新票；
  不得改判据。前置不成立（Ollama / Docker / 浏览器 / 内存）时登记 SKIP 与边界，
  **不得写成 PASS**。
- **AC3 U2 闭环**：备份 `.env` 并记录 SHA-256，完整跑一次 `up.ps1` 链路
  （含 `bootstrap_env.py --force` 的受控改写），验证 biz-mock 播种演示账号、
  登录 200；结束后从备份恢复 `.env` 并断言 SHA 不变。按结果更新 U2。
- **AC4 欠账裁决**：对 R1/R3/R5/R6/R8/R10/R15 与 U1/U2/U3/U8/U10/U11
  逐项给出「保留（触发条件）/ 关闭（证据）/ 需所有者裁定」；只有证据充分才关闭，
  清单与 `docs/EVIDENCE.md` 同步。
- **AC5 发布判定**：给出三案并交所有者书面选定——
  **A** 维持 `0.1.0-SNAPSHOT`、不发布；**B** 改 `1.1.0-SNAPSHOT`、承认未发布；
  **C** 发布 `v1.1.0`（`pom.xml` 版本 + tag + release note）。
  本票只执行被选中的那一项；选 C 时必须在 `mvnw verify` 与 CI 绿后才打 tag。
- **AC6 仓库卫生**：清理本地已合并的 `codex/*` 分支（保留 `main`）；
  `git status --short` 为空；无 `logs/` 或临时产物入库；HEAD 与 `origin/main`
  同步；CI 对最终 HEAD 为 success。
- **AC7 独立 Verifier**：独立复跑契约命令并抽查活体证据；输出 PASS/FAIL 报告；
  不得采信实现方自评。
- **AC8 收尾记录**：票面 `Status` 与 `## Handoff notes`、tracker round60 行、
  `docs/EVIDENCE.md`、`registered-debt` 同步；`git diff --check` 等全绿。

## Verification commands

静态：

```powershell
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
pwsh -NoProfile -File scripts/verify-launcher-env.ps1
.\mvnw.cmd -B -ntp verify
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
python scripts/check_doc_links.py
git diff --check
git status --short
```

活体（AC2）：

```powershell
pwsh -NoProfile -File scripts/run-acceptance.ps1 -Tier full
```

U2（AC3）：

```powershell
# 1) 备份 .env 并记录 SHA-256
# 2) pwsh -NoProfile -File scripts/up.ps1
# 3) 断言演示账号播种成功、/auth/login 200
# 4) 恢复 .env，断言 SHA-256 与备份一致
```

## Forbidden

- 不改 Java / Vue / 业务配置 / 数据库 / API / gold / 阈值 / 覆盖率门槛。
- 不跳过、删除或弱化测试；不为发布改判据或隐藏红项。
- 不把 SKIP、未跑或环境红写成 PASS。
- 不在所有者选定发布案前改 `pom.xml` 版本或打 tag。
- 不提交 `.env`、`logs/`、本机截图或临时产物。

## 现场追问

1. **发布案 A/B/C 需要所有者选定**：默认建议 B（先承认未发布，等全量验收绿后
   再决定是否发 `v1.1.0`），不建议在 AC2 未绿前打 tag。
2. **全量 29 步需要 Ollama / Docker / 浏览器与约 7 GB 内存**：若本机前置不足，
   是否接受「SKIP + 边界登记」而不是等环境？建议接受边界，但不得宣称 full PASS。
3. **U2 的 `.env` 受控改写**：建议允许「备份 → 跑 `up.ps1` → 恢复」；
   票 229 的「不得改动 `.env`」约束只属于 229，不延续到本票。
4. **R15 / U10 / U11 属产品决策**：收尾票只给建议与证据，不替所有者裁决
   是否扩词表、重跑压测或重做极性前置。

## Owner ruling（2026-11-25）

- 所有者选定 **AC5 发布案 B**：`pom.xml` 从 `0.1.0-SNAPSHOT` 改为
  `1.1.0-SNAPSHOT`；**不打 tag、不建 GitHub Release**。
- 选 C（发布 `v1.1.0`）的前置保留：29 步 full 验收通过、欠账裁决完成、
  所有者书面签收发布声明后才评估。
- **契约修订 1**：`scripts/demo.py` 进入 Allowed paths。原因是它把
  `shoppilot-*-0.1.0-SNAPSHOT.jar` 写死在三处；版本号一改，`--prebuilt`
  会找不到 jar。实现改为按 `shoppilot-<module>-*.jar` 通配解析，
  不改变 `--prebuilt` 的语义。
