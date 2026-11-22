# 票 226 契约：文档与仓库卫生

**状态**：2026-11-22 冻结（所有者指令开票）
**判定**：本票只做文档、tracker、忽略规则与检查脚本；不改业务代码、判据或证据结论。

**基线**：票 224、225 收口后的 `main`；执行前先记录 HEAD SHA 与 `git status --short`。

## 目标

1. 对齐 root README、tracker README 与票面状态（219-226）。
2. 处置未跟踪资产：`docs/readme-hero.svg`、`docs/design-samples/`、本轮截图。
3. 隔离本机临时产物，保留历史证据目录。
4. 修正过期口径与失效图片引用，增加本地链接检查。

## Allowed paths

- `README.md`
- `docs/**`（只改文档、图片索引与 ADR 列表；不移动历史证据）
- `.scratch/shoppilot-mvp/README.md`
- `.scratch/shoppilot-mvp/issues/226-docs-and-repo-hygiene.md`
- `.scratch/shoppilot-mvp/issues/219-*.md` … `225-*.md`（仅 Status/签收记录校正，不改历史结论）
- `plans/226-docs-and-repo-hygiene.md`
- `.gitignore`
- `scripts/check_doc_links.py`（新增）
- `docs/STATUS.md`（仅机械同步）

> **2026-11-23 契约补记（Verifier P1-2 路径缺口，不改 AC，待所有者追认）**：AC1 要求
> 票面状态一致，但首次冻结漏列 219–225 票面。现补入，只允许 `Status` 与签收/提交
> 记录校正，不允许改写历史结论、证据或验收判据。这是冻结后补记，与票 225 的补记
> 一样，所有者书面追认前只能记为已披露的流程偏差。

## Acceptance criteria

1. root README、tracker README 与票面在 219-226 的状态上逐项一致；221 不再出现「签收待确认」。
2. README 引用的本地图片全部存在且已跟踪；未跟踪资产有明确处置（入库或 ignore）。
3. `docs/design-samples/` 的用途与来源写清；若不入库，加入 ignore 并在文档中说明。
4. `git status --short` 只剩明确登记的本地产物；无 `.env`、token、日志、`hs_err_pid*`、`replay_pid*`。
5. 新增 `scripts/check_doc_links.py` 只检查本地 Markdown 图片/相对链接，不访问网络；
   对缺失目标返回非 0。
6. `gen_status.py --check`、`verify_doc_truth.py`、`check_doc_links.py`、`git diff --check` 全绿。
7. 历史证据目录、ADR 与既有 ticket 路径不被移动或删除。

## Verification commands

```powershell
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
python scripts/check_doc_links.py
git diff --check
git status --short
```

## Forbidden

- 不改业务代码、测试判据、gold、阈值、指标分母或历史证据结论。
- 不删除历史证据目录，不按时间通配清理 `.scratch`。
- 不提交 `.env`、token、日志、崩溃日志或本机绝对路径。
- 不把设计参考稿直接冒充产品截图；来源与用途必须写清。
