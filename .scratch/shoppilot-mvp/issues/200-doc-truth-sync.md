# 票 200：文档真值同步（round48 第一票）

Round: 48（spec：[round48-spec-quality-closure.md](../round48-spec-quality-closure.md)）
ADR: 0083　Status: implemented　Blocked by: 无

## What to build

1. **用一次真实 `mvnw verify` 重新生成 STATUS**：`python scripts/gen_status.py`；
   记录四模块测试数、suites、覆盖率，不接受只改字面不改产物。
2. **README 当前读数**：
   - 首屏 badge 的 JVM Tests 数改为生成值；
   - 「架构决策演进」一行的 ADR 篇数改为 `docs/adr/*.md` 实际数量（编号数与成文数分开写，
     0022 未占用的说明保留）；
   - 复现命令注释里的四模块拆分（`12 + 76 + 600 + 50 = 738`）改为当前拆分。
   - **历史表格不改写**：README 深度附录里 09-10 / 09-11 的 93.3% / 95.6% 读数保持原样，
     如需要只在该行加「历史读数，当前见 `docs/STATUS.md`」。
3. **ARCHITECTURE.md**：§6 的 ADR 篇数换代。
4. **TUNING_PLAN.md**：
   - JVM 基线从 701 换代到当前值；
   - 「Gold 180 · qwen-plus」改为 **179/180 = 99.4%**，`ACTION_REFUND` 94.4%；
     退款定向重跑 18/18 **单列**，不写成「全分区 100%」；
   - 「分意图红点已清零」改为全量口径下的实际状态（剩 `ACTION_REFUND` 1 条）；
   - 文末目标表的 JVM 行同步换代。
5. **`docs/current-metrics.json`**：
   - 「工具调用准确率」写 179/180 = 99.4%（dev qwen-plus，2026-10-27），
     `ACTION_REFUND` 94.4%；定向重跑 18/18 单列；
   - `generatedFrom` 更新到本次全量读数的日期与产物。
6. **收口审计台账**：`round3-closeout-audit.py` 的 `G6_EXPECT` 由 `[10, 73, 449, 45]`
   换代到本次 verify 的四模块读数，并加注释说明这是「台账换代，不是放宽判据」。
7. **tracker**：`.scratch/shoppilot-mvp/README.md` 补 round48 行与票 200/201 索引。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/gen_status.py
python scripts/gen_status.py --check
python .scratch/shoppilot-mvp/round3-closeout-audit.py
git diff --check
git status --short
```

审计在提交前允许 `A2`（工作树未提交）红；`F1c` 是 round20 的历史红，不在本票范围。
其余新增红必须逐条归因，不许改判据放行。

## 验收项

- `docs/STATUS.md` 的测试数、覆盖率、指标行与一次真实 verify 的产物一致，`--check` 通过。
- README badge / README 复现注释 / STATUS 三处测试总数一致；ARCHITECTURE 的 ADR 篇数与目录一致。
- `current-metrics.json` 不再把 93.3% / 90.5% 当作当前值；全量 179/180 与定向 18/18 分列。
- TUNING_PLAN 不再出现「全分区 100%」「分点已清零」这类把定向重跑当全量的说法。
- G6 台账与本次 verify 一致，审计不再因台账陈旧判红。
- `git status --short` 不混入 `logs/`、`hs_err_pid*.log`、`replay_pid*.log` 或过期快照。

## Handoff notes

**关键决策**

- **测试数用一次真实 `mvnw verify` 定案**：`./mvnw.cmd -B -ntp verify` → BUILD SUCCESS，四模块
  **12 / 92 / 619 / 50 = 773 tests / 129 suites**、0 失败。README badge、README 复现注释、
  `docs/STATUS.md` 三处统一到 773；TUNING_PLAN 基线与目标表同步换代。不引用 round47 的 771——
  那是 `eb53628` 补两条 SSE 401 测试之前的读数。
- **ADR 计数按磁盘而非旧叙事**：`docs/adr/*.md` 实为 82 篇，编号占 0001–0083 中的 83 槽
  （ADR-0022 仍有意预留）。README 首屏与 ARCHITECTURE §6 统一写「83 个编号 / 82 篇成文」，
  badge 写 82。
- **工具准确率拆两个口径**：全量 dev-167 是 **179/180 = 99.4%**，其中 `ACTION_REFUND`
  17/18 = 94.4%；退款定向 `refund-168b` 的 18/18 明写「另一口径、不并入全量」。旧读数
  93.3% / 90.5% / 重算 95.6% 只保留在 README 历史附录与 EVIDENCE 的对应行，不再充当当前值。
- **G6 台账没有按票面第 6 条换代——这是有意的偏离**。G6 比的是本机
  `logs/acceptance/{build,unit}.log`，不是 Surefire；这两个文件仍停在 2026-10-19 的
  `10 + 73 + 449 + 45 = 577`。把 `G6_EXPECT` 改成 773 会让 G6 立刻假红，属于「改台账制造红」，
  比保留旧台账更坏。本轮只在审计脚本里加注释登记真实数 **773**，常数等下一次带 build/unit 的
  全量矩阵重跑后再换代。尝试过本机刷新两份日志：`pwsh` 不在 PATH，Windows PowerShell 5.1
  解析该脚本的 UTF-8 中文即 ParserError，无法执行；不伪造日志。

**验证落点**

```text
./mvnw.cmd -B -ntp verify                 BUILD SUCCESS（12/92/619/50 = 773，129 suites）
python scripts/gen_status.py              wrote docs/STATUS.md
python scripts/gen_status.py --check      STATUS ok（exit 0）
python .scratch/shoppilot-mvp/round3-closeout-audit.py
                                          PASS 78 / FAIL 8 / SKIP 11；G6 PASS（577，与两份日志对齐）
git diff --check                          无输出
git status --short                        仅 round48 文档/tracker/本机探测脚本，无 logs/、无崩溃日志
```

审计 8 项 FAIL 均为既有红，逐条归因：A1/A2（origin 落后、工作树未提交，审计自述正常）、
B7（`6bc45f3..HEAD` 长窗口的历史禁面，非本票）、F1c（本机 `logs/` 不入库的历史引用）、
G2b / G8 / H2 / H3（round3 时代落点，与本票无关）。本票没有新增红，也没有放宽任何判据。

**三个现场追问**

1. **为什么 `docs/STATUS.md` 的指标行也变了？** 因为 `gen_status.py` 的指标表来自
   `docs/current-metrics.json`；只改 json 不重生成，`--check` 会红。两者必须一起提交。
2. **773 是不是把本轮文档改动算进去了？** 不是。本轮零代码改动；773 相对 round47 的 771
   多出的两条来自 `eb53628` 的 SSE 401 补测，与文档同步同批入库但不同源。
3. **G6 什么时候才能真正换代？** 需要一台有 `pwsh` 7 的机器（或把 run-acceptance 的每日档
   在 CI/Linux 跑一次），产出新的 `logs/acceptance/{build,unit}.log`，再把常数改成当时的
   四模块读数；在那之前 G6 继续读 2026-10-19 快照，不代表当前代码。
