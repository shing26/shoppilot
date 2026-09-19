# 34 — 评测子集进 CI：selfcheck + 离线 rescore 门禁比对

**What to build:** 把 0 token 的评测量具验证接进 `ci-subset.yml`：① `scripts/verify_eval_judge.py`（判据形状、量具变异反证、校验器防呆，40 项断言）；② `scripts/run_tool_eval.py --rescore` 对 2026-09-10 六份入库明细按当前判据重算，并钉住期望差异集合（对偶矛盾放开的 ACT-ORD-09/11/16/17 四条）。两者都不发 HTTP、不花模型额度、纯 stdlib，在干净 runner 上可复跑。从此 judge() 或 gold 的任何静默漂移都会让 CI 变红——这是 ADR 0033 给票 36-39 的前置硬闸门（改判据的票必须先过这道比对）。

**Blocked by:** None（round17 前置票；本票不改判据、阈值、gold，只接线）。

**Status:** implemented（2026-09-19；本地两命令 exit 0，CI run 见 Handoff）

- [x] `ci-subset.yml` 新增两步：`Eval judge selfcheck (0-token)` 与 `Offline rescore gate (0-token)`，位于 `mvnw verify` 之后
- [x] rescore 输入按入库矩阵的既定次序：5 份按意图补跑（`080[6-9]xx-dev-budgetfix`）在前、1 份全量（`075747-dev`）殿后，`先出现为准` 合并
- [x] `--rescore-expected-diff ACT-ORD-09,ACT-ORD-11,ACT-ORD-16,ACT-ORD-17` 钉住判据基线；实际差异集合偏离即退出 1
- [x] 本地验证：`python scripts/verify_eval_judge.py` exit 0（40 PASS）；rescore 命令 exit 0（180 cases，tool_diff=4 与期望一致）
- [x] README CI 段、`docs/EVIDENCE.md` 门禁行、tracker README 门禁描述同步更新
- [x] 全量 `mvnw verify` 228 绿不受影响（CI 步骤是纯增量）

**Verify:**

```bash
python3 scripts/verify_eval_judge.py
python3 scripts/run_tool_eval.py \
  --rescore eval/results/tool-eval-20260910-080638-dev-budgetfix.csv \
            eval/results/tool-eval-20260910-080732-dev-budgetfix.csv \
            eval/results/tool-eval-20260910-080830-dev-budgetfix.csv \
            eval/results/tool-eval-20260910-080926-dev-budgetfix.csv \
            eval/results/tool-eval-20260910-080942-dev-budgetfix.csv \
            eval/results/tool-eval-20260910-075747-dev.csv \
  --rescore-expected-diff ACT-ORD-09,ACT-ORD-11,ACT-ORD-16,ACT-ORD-17
```

预期：两命令 exit 0；rescore 末行 `RESCORE DONE cases=180 files=6 tool_diff=4`；CI run 两步全绿。

**验收项**

1. CI run 包含两步且全绿；两步都是 0 token（脚本自证：不发 HTTP、纯 stdlib）。
2. 判据漂移有机器反证：改 judge() 或 gold 使差异集合偏离四条 → rescore 退出 1 → CI 红。
3. rescore 在 CI 内只产出临时明细（runner 临时目录，不入库）；本机验证产生的新 rescore 明细已删除，历史证据目录不动。

## Handoff notes

**关键决策**

- **两步进同一个 job、排在 `mvnw verify` 之后**：评测门禁与 JVM 门禁共享"干净 runner 零外部依赖"的定位；分开 job 只会多一份启动成本，且 fail-fast 语义相同。workflow 仍叫 `ci-subset`——它依旧不覆盖 17 步活体验收，只是"子集"的边界从构建+JVM 扩到构建+JVM+评测量具自检（README 已同步）。
- **rescore 的基线钉法**：门禁比对的是"按当前判据重算旧明细后，选对工具的结论发生变化的样本集合"必须恰为对偶矛盾放开的四条。judge() 改规则、gold 改标注、明细文件被动过——任何一种都会让这个集合偏离并退出 1。这正是 ADR 0033 要求它先于票 36-39 进 CI 的原因：后面每张票都要动 judge() schema，改动必须有这道比对背书。
- **python3 而非 setup-python**：ubuntu-latest 预装 python3，脚本纯 stdlib；不为两步 0 token 脚本引入 setup-python 的启动开销。版本随 runner（当前 3.x），脚本不依赖任何 3.8+ 之外的特性。

**验证落点**

- 本机（Windows，py 3.11.9）：`verify_eval_judge.py` 40 PASS exit 0；rescore `RESCORE DONE cases=180 files=6 tool_diff=4` exit 0，与 `tool-eval-20260911-042142-rescore.csv` 记录的基线一致。
- CI run：见 Handoff 末行（推送后回填）。

**现场追问**

1. *为什么比对的是"差异集合"而不是 95.6% 这个分数？* 分数会随明细里的"未观测断言"列浮动，而差异集合是判据变化的直接指纹：新判据要么与旧结论一致，要么恰好在认定的四条对偶矛盾上不同。钉集合比钉分数更紧，也更可解释。
2. *票 36-39 扩 judge() schema 后这道门禁会不会变红？* 会，而且是故意的。扩 schema 改变了新字段用例的判法，但 part1-3 的 180 条旧明细在新 schema 下结论必须不变（新字段只加判不翻旧案）——若旧明细的差异集合偏离四条，说明新判据破坏了既有语义，这正是门禁要拦的。
3. *rescore 每次都会写新的明细文件，CI 里为什么不落库？* 本机的 rescore 产物是"当次验证"的一次性读数，与 `tool-eval-20260911-042142-rescore.csv`（判据定版时的基线证据）内容相同；重复入库只会制造第二份数字表，违反 EVIDENCE 入账规则第 1 条。CI runner 本就临时，无此问题。
