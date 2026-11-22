# 票 226：文档与仓库卫生

Round: 56（所有者 2026-11-22 指令；按 224 → 225 → 226 顺序）
ADR: 无（文档与仓库卫生，不改业务能力）
Status: implemented（首轮 Verifier FAIL 已整改，待复验）　Claimed by: implementer-226　Blocked by: 票 224、票 225（均已独立 Verifier PASS）
契约：[plans/226-docs-and-repo-hygiene.md](../../../plans/226-docs-and-repo-hygiene.md)（2026-11-22 冻结）

## What to build

在 224/225 收口后，把文档、tracker 与仓库状态对齐：

1. 对齐 219-223 的签收/待验收状态、224-226 的 ready/blocked、测试数与 ADR 数；
   修正 root README 与 tracker 的过期口径（含 221 签收状态）。
2. 处理未跟踪资产：`docs/readme-hero.svg`、`docs/design-samples/`、本轮截图；
   要么入库并写来源/用途，要么明确 ignore；README 不得引用不存在的图片。
3. 隔离本机临时产物：`.scratch` 探针、截图、日志；保留历史证据目录，不按时间通配删除。
4. 增加/更新文档检查：图片引用存在、关键状态一致、无本机绝对路径与密钥。
5. 跑 `gen_status.py --check`、`verify_doc_truth.py`、链接检查与 `git status --short`。

## 边界

- 不重写产品叙事，不删除历史证据，不改业务代码、判据、gold、阈值或分母。
- 不提交 `.env`、token、日志、崩溃日志或本机绝对路径。
- 不把 `docs/design-samples/` 直接并入产品截图区；先定用途与来源。

## Verify

```powershell
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
python scripts/check_doc_links.py
git diff --check
git status --short
```

若仓库尚无 `check_doc_links.py`，本票新增该脚本；它只检查本地 Markdown 图片/相对链接，
不访问网络。

## 验收项

- root README、tracker README 与票面对 219-226 的状态一致；221 不再出现「签收待确认」。
- README 引用的本地图片全部存在且已跟踪；未跟踪资产有明确处置（入库或 ignore）。
- `git status --short` 只剩明确登记的本地产物；无密钥、日志、`.env` 或崩溃文件。
- `gen_status.py --check`、`verify_doc_truth.py`、文档链接检查与 `git diff --check` 全绿。
- 历史证据目录、ADR 与既有 ticket 的路径不被移动或删除。

## Handoff notes

**关键决策**

- `docs/design-samples/` **入库**：它是 round51 的三版高保真设计提案，不是产品截图；
  目录内 README 已写清生成日期、用途、与 219 实现的取舍关系，并追加 2026-11-23
  归档说明「实现状态以 tracker 为准」。这样 219/225 票面与 EVIDENCE 的引用不再悬空。
- `docs/readme-hero.svg` **ignore**：未被任何 README 引用，属未采用的本机设计草稿；
  入库会制造「有图不用」的噪声，引用它又会改产品叙事，超出本票范围。
- 本机探针、截图、一次性脚本按类别 ignore（`.scratch/*.py|*.ps1|*.sh|*.mjs|*.txt|*.png`、
  `unslop-*`、`spike-sidecar`、`ux-probe/shots` 等），**不按时间通配清理**；
  `.scratch/shoppilot-mvp/**` 是受控 tracker，不在忽略范围。Verifier 报告作为本地
  审计物 ignore，结论已汇总进 `docs/EVIDENCE.md` 与票面。
- 新增 `scripts/check_doc_links.py`：只扫 `README.md` + `docs/**/*.md`，剥离代码块/
  行内代码后检查 Markdown 图片、相对链接与 `<img src>`；外链、锚点、站点绝对路径跳过，
  缺失目标 exit 1，不访问网络。
- 修掉 6 条既有失效本地链接：ADR 0062→0029、0063→0058/0047、0079→round43 spec、
  `CHANNEL_CONNECTIONS`→`../config/channels.example.json`、
  `EVIDENCE`→`../eval/results/prometheus-scrape-20261112-t218.txt`。
- 状态对齐：root README 增加 Round 51–56 状态行，221 改为「2026-11-21 已书面签收」；
  tracker 221 去掉过期的「未提交」，226 转 implemented；219/220 done、222/223
  待验收、224/225 PASS 待签收保持原样。未改任何 AC、证据结论或历史路径。
- **首轮 Verifier FAIL 整改（2026-11-23）**：221 票面本身仍有「工作树未提交/未 commit」
  字样，与 tracker 和实际提交 `4fc0309` 冲突；现校正 `Status` 与两处现场描述为
  「已随 round51–53 统一提交于 `4fc0309`」，历史段落保留但标注为当时现场。
  `docs/design-samples/README.md` 两处「219 未提交」同步改为 done/已提交。
  契约 Allowed paths 补入 `.scratch/shoppilot-mvp/issues/219-*.md … 225-*.md`
  （只允许 Status/签收记录校正），并注明冻结后补记、待所有者追认。

**验证落点**

- HEAD `e66ce88`，分支 `codex/226-docs-and-repo-hygiene`（从票 225 HEAD `68afa16`
  切出；224/225 尚未合并 main，本票为堆叠分支）。
- `python scripts/check_doc_links.py` → `DOC LINKS OK files=119 local_links=234`。
- `python scripts/gen_status.py --check` → `STATUS ok`；
  `python scripts/verify_doc_truth.py` → 870/143 一致；
  `git diff --check` 无输出；`git status --short` **为空**（本机产物已按类别 ignore）。
- 未跑 JVM / 前端测试：本票只改文档、ignore 与新增检查脚本，无业务代码变更；
  既有 870/143 读数由票 225 第三轮 Verifier 在本分支祖先 `a85496d` 上亲跑。

**三个现场追问**

1. `docs/design-samples/` 已按「设计参考、非产品截图」入库；若后续要删，需先清掉
   219/226 与 EVIDENCE 的引用，不能只删目录。
2. root README 的产品化首屏保留，只加了 5 行状态块；没有回到精简版。
3. 本机截图继续留在 `.scratch` 并 ignore，不迁入 `docs/` 作为正式证据；需要长期
   证据时仍按 AGENTS.md 写汇总/可复跑产物到 `docs/`、`eval/results/` 或
   `loadtest/results/`。
