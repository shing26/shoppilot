# 150 round35 收口

**Status:** implemented（2026-10-19）

**Claimed by:** zcode implement session 2026-10-19（round35 收口：验证 + 叙事换代 + 台账）

## What to build

round35（agent 自主性升级）收口：把机制、观测、提示词、评测与证据四件收成可交付状态，并按本仓既有收尾流程
更新叙事与台账。

- **验证**：`.\mvnw.cmd -B -ntp verify` 全绿；收口审计实跑并把读数写进票面（与基线逐条对照，**不新增红**）；
  无 `logs/` 时的 SKIP 照登。
- **叙事换代**（本仓既有流程；map 135 明写叙事不属 map）：README 的 agent 段（4 轮 / 反思 / 观测口径）、
  `docs/architecture-faq.md`、`DELIVERY.md`、`CHANGELOG.md`；对外只说**已实测**的东西。
- **台账**：tracker 索引与 Round 表；`registered-debt.md` 把 **R11 / R12 挪到「已关闭」**；
  `docs/EVIDENCE.md` 核对 part10 行、指标名 56/58、`plan_steps_total` 值域说明。
- **未达成照登**：local 3B 的 `plansteps`（判据不动）、全量矩阵是否重跑按本机资源裁定、
  任何依赖活体的门禁未实跑的格。

## Blocked by

[143](143-max-tool-rounds-4.md)、[144](144-reflection-branch.md)、[145](145-reflection-observability-and-token-fields.md)、
[146](146-write-guard-domain-gate.md)、[147](147-prompt-v1-2-0.md)、[148](148-autonomy-eval-set-and-module.md)、
[149](149-autonomy-dev-live-run.md)（全部）。

## 口径

- 依据：[ADR 0069](../../../docs/adr/0069-round35-agent-autonomy-upgrade-reopen-basis.md) 的 Consequences 与
  [spec v2](../agent-autonomy-spec-v2.md) §5；收口流程照 `AGENTS.md`。
- **判据红线**：gold 180 / 阈值 / 分母 / `judge()` / 既有验收判据一字不动；未达成项必须保留实测值、归因与限制。
- `G6_EXPECT` 只在**本机真的重跑带 build/unit 的活体矩阵**时随新落点换代；没跑就不动（它会红，照登）。
- 提交信息沿用 `docs(round35): ...` / `feat(round35): ...` 风格。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python .scratch/shoppilot-mvp/round3-closeout-audit.py
git diff --check; git status --short
```

## Handoff notes

**关键决策**：

1. **验证双件都实跑**：`.\mvnw.cmd -B -ntp verify` 四模块 **`10 + 73 + 449 + 45 = 577` 全绿**（3:37）；
   收口审计实跑：提交前 **`PASS 73 / FAIL 13 / SKIP 10 / 共 96`**，**推送后复跑 `PASS 74 / FAIL 12 / SKIP 10`**
   （`A1` `A2` 双双转绿；落点 `logs/audit-150-postpush.log`，本机证据不入库）。
   **与基线逐条对照：11 条既有红逐条相同、零新增**；多出的两条是 `A2`（提交前工作树，提交后转绿）与
   **`A3`「本轮零额度」为假**——本轮为票 149 的 dev 活体跑真花了 **470,037** 额度，按 round20 先例如实登记、不摘红。
2. **一条被本轮改动带红的检查，已修**：票 149 给 `run_tool_eval.py` 的 `http_json` 加了可选 `method=` 参数（+2 行），
   撞上审计 `H3` 的行号引用表（票 20 钉的两处引用 + 审计脚本自己的 `EXPECT_CITE` 表）——**按磁盘行重锚**
   （`156/160/421` → `158/162/423`，判据一字未动），还原后 `H3` 转绿。这是本轮唯一的「改坏 → 修好」闭环。
3. **叙事只写已实测的**：README 的 4 轮上限与反思机制（含三态观测口径）、指标名 56/58、测试数 **552 → 577**
   （badge + CI 段 + 复现段三处；`gateway 424 → 449`）、part10 一行；`docs/architecture-faq.md` 的 4 轮问答与两处换代指针；
   `CHANGELOG.md`（round16–round35、ADR 编号 0001-0069、新增/变更/修复各一条）；`DELIVERY.md`（状态段换代到 2026-10-19 + 漂移表新增一行）。
   **既有 ADR 一字未改**（换代指针都在 ADR 0069 / spec v2 / tracker）。
4. **台账**：`registered-debt.md` 的 **R11 / R12 已在票 145/146 挪进「已关闭」，本票只做复核**（两条措辞与票面一致，未改）；
   已关闭 9 条、活跃登记项 R1-R8/R10/R14、未达成 U1-U5/U7-U8/U10/U11——`DELIVERY.md` 与 tracker Round 表的计数已同步（R13 经所有者裁定后关闭、U6 随 2026-10-19 清场日矩阵补跑关闭；R14/U11 为矩阵新发现，见下「收口后追加」）。tracker：Round 表 round35 → **done**、
   索引 150 → `implemented`、frontier **清空**（`ready-for-agent` 为空）。

**验证落点**：

- `.\mvnw.cmd -B -ntp verify` → **577 绿**（本轮无 Java 代码改动，跑它是为了确认共享 Python 工装与文档换代没有连带）。
- `python .scratch/shoppilot-mvp/round3-closeout-audit.py` → `PASS 73 / FAIL 13 / SKIP 10`（提交前）/ **`PASS 74 / FAIL 12 / SKIP 10`（推送后，A1/A2 转绿）**。
- 0 token 门禁：`eval_autonomy.py` → `ok=15`、`run_tool_eval.py --selfcheck` → `ok=16`、`eval_suites.py` → `ok=30`、
  `eval_task.py` → `ok=14`、`verify_eval_judge.py` → **40/40**、`verify_onepager.py` → `failures=0`。
- **CI**：`eea7861`（票 149）首跑**红**——`LogbackRotationTest.rollsOnSizeAndHonoursAppName` 一条（logback 轮转的 Linux 时序竞态，
  该用例在 round15/票 32 就登记过同族竞态）；**`gh run rerun` 后同一提交转绿**（run `39184195494` attempt 2，2m33s）。
  这是**间歇红、不是回归**（本轮零 Java 改动，本机同套件两次全绿）；照登。
  收口提交 `c6cbe25` 的 CI run **`39185290434` 一次绿**（2m 内），`eea7861` 那个红只在 attempt 1 上出现过。

**三个现场追问**：

1. **为什么 `A3` 留着红而不是去改判据？**——`A3` 断的是「本轮零额度」。round35 的票 149 就是一次**花真钱的 dev 活体跑**
   （47 万 token，全部登记在 `docs/EVIDENCE.md` 与票 149 的读数表里）。把它改掉或豁免就等于把「这轮花过钱」抹掉，
   而 round20 先例正是**留着红 + 如实登记**。判据一字未动。
2. **`G6` 为什么不动？**——它比的是**入仓的验收矩阵落点**（本机 `logs/` 里的真跑）。本轮没有重跑全量 29 步矩阵
   （本机资源 + 矩阵里多数步骤要活体栈），所以照票面口径「没跑就不动，它会红、照登」；换代要连新落点一起做。
3. **叙事换代为什么改了 README 的 552？**——那是**过期读数**：round35 机制层收口后本机 verify 实读 **577**
   （`10 + 73 + 449 + 45`），badge、CI 段与复现段三处都还写着 552/424。这类「改了代码没更新对外读数」是本仓反复登记过的毛病
   （round34 的 552 vs 551 同族），按收口职责换代并保留历史落点（README 的 round14 历史不动）。

**收口后追加（2026-10-19，收尾复核）**：

- **part10 写用例隔离缺口已修**（票 148 面，`fa7c77a` + `49275f7`）：`AUT-REFL-08` 换单号（90001→90002）、`AUT-RD-05`
  的退款目标换 90003；`eval_autonomy.py` 的 `validate_cases` 新增**写用例隔离规则**（机器钉住，上线当场抓到 RD-05
  那处同族问题），`AUTONOMY SELFCHECK ok=15` 不变。
- **台账 R13 补登记 → 所有者裁定并关闭**（`c40954c` → `65ad83d`，CI run `39206998285` 绿）：README 门面化
  （`2ad6ade`）之后 8 条审计检查指向的 README 内容已不存在；裁定 = **重锚检查为主、补 README 为例外**
  （E2/E6b×2/E6c 断言面改成「README / EVIDENCE / tracker / ticket 20 至少一处含」；H5b 认三种等价说法；
  两支一次性跑器加「已作废」第四态），另加 **R13b 对照组**防自证；审计项数 **96 → 97**，R13 移入「已关闭」。
  本票的台账计数、`DELIVERY.md` 与 tracker Round 表三处随之校正（已关闭 8；R13 不入「登记项」；U8 补进
  「未达成」枚举——它是 round34 就入账的，此前三处枚举都漏了它）。
- **收尾复核读数**：`python .scratch/shoppilot-mvp/round3-closeout-audit.py`（97 项口径）→ 提交前
  `PASS 82 / FAIL 4 / SKIP 11`（4 红 = `A1` 未推送 + `B7` 窗口 + `F1c` 历史 + `G6` 未重跑矩阵；`A3` 为 SKIP——
  网关已停、读不到活体额度）；**推送后复跑 `PASS 83 / FAIL 3 / SKIP 11`**（A1 转绿；3 红 = `B7` 窗口 + `F1c` 历史 + `G6` 未重跑矩阵，零新增）。`.\mvnw.cmd -B -ntp verify` 复跑四模块
  **`10 + 73 + 449 + 45 = 577`** 全绿（3:32）；CI：`db24556` run `39188609583` **success**、
  `65ad83d`（R13 裁定）run `39206998285` **success**。
- **清场日全量矩阵（2026-10-19，收口后）**：full 档 29 步首跑 **20 绿 / 9 红（1242s）**；补跑后 `build`
  （fork 崩溃 → 停栈重跑双绿，G6 常数 **536 → 577**）、`refund`、`workspace` 12/12、`buyer` 9/9 转绿，
  `console` 43/44 为已登记方差；仍红 `feedback`/`plansteps`（既有登记）与两个新发现——**R14**
  （反思吞掉读工具失败：`degraded=false`、无 fallback/工单）与 **U11**（极性守卫 L2 前置不稳定）。
  落点与逐条归因见 `docs/EVIDENCE.md` 的 2026-10-19 行与 `registered-debt.md`。
