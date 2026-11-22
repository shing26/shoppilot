# 票 226：文档与仓库卫生

Round: 56（所有者 2026-11-22 指令；按 224 → 225 → 226 顺序）
ADR: 无（文档与仓库卫生，不改业务能力）
Status: ready-for-agent　Claimed by: 无　Blocked by: 票 224、票 225
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

- 待实现后补；本票不产生业务代码变更。

**验证落点**

- 待实现后补；证据绑定 HEAD SHA 与 `git status --short`。

**三个现场追问**

1. `docs/design-samples/` 是长期设计参考还是本轮临时素材？决定入库还是 ignore。
2. root README 的产品化首屏是否保留，还是回到精简版并只留链接？
3. 本机截图是否要迁到 `docs/` 作为正式证据，还是继续留在 `.scratch` 不入库？
