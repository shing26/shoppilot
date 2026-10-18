# 148 自主性评测集 part10 + `eval_autonomy.py`（0 token 自检）

**Status:** ready-for-agent

## What to build

建一套**能判动态规划与反思**的新评测集与独立判分模块（单独成列、不给总分、不并 `eval_suites.py`），
默认入口是 0 token 夹具自检（无网关、无模型），CI 折进既有套件步（**仍九步**）。

- `eval/cases-part10-autonomy.jsonl`，`kind: autonomy`，**30 条 = 15 / 10 / 5**：
  - **动态规划 15**：8 条 4 轮链（含「读+读+读+写」收尾型）+ 7 条 2 步链（现形状对照）；
  - **反思 10**：5 触发（读工具③失败 → 换路成功）/ 3 不触发（闸与写工具失败面）/ 2 次数用尽
    （同工具第二次③失败 → `TOOL_UNAVAILABLE` + 工单）；
  - **超限收口 5**：3 读超限 + 2 写挂起超限（两条出口语义不同）。
- `scripts/eval_autonomy.py`：与 `eval_task.py` 同形态的独立载体——默认无参数跑 = 夹具自检（0 token）；
  活体跑批复用 `run_tool_eval.py` 的 HTTP 助手（新增 `--autonomy` 入口）。
- 判据列 = 三态（`plan[]` 的 `reflection` / `attempt` + `shoppilot_reflection_total{outcome}`）
  + 步数（`plan[]` ≤4、超限形态）+ 出口（`fallbackReason ∈ {TOOL_UNAVAILABLE, TOOL_ROUNDS_EXHAUSTED}`）；
  **取证列** `billed_tokens` / `latency_ms` 不进判据、只进明细。
- 成本列读 `shoppilot_llm_tokens_total` 增量（或 Redis 日计数差值），**不读响应 token 字段**。

## Blocked by

[144](144-reflection-branch.md)、[145](145-reflection-observability-and-token-fields.md)
（反思分区与三态判据依赖它们的字段与 counter）。

## 口径

- 依据：[ADR 0069](../../../docs/adr/0069-round35-agent-autonomy-upgrade-reopen-basis.md) §五；140 的裁定是唯一口径。
- **不进 `GOLD_CASE_FILES`**；`build_eval_set.py` / `verify_eval_judge.py` 的 `PARTS` 不动；part10 是 add-only 新文件。
- 用例措辞**必须唯一**（动作类查询不进缓存，同措辞重跑会全额计费，缓存会压低成本读数）。
- CI：把 `python3 scripts/eval_autonomy.py` 折进既有套件步（`&&` 串在 `eval_suites.py` 之后），
  **步数不变（九步）**，不新增一步。

## Verify

```powershell
python scripts/eval_autonomy.py
python scripts/eval_suites.py
git diff --check; git status --short
```

（首条输出 `AUTONOMY SELFCHECK ok=<n>` 且退出码 0；第二条仍绿。）

## Handoff notes

（解决时补：关键决策、验证落点、三个现场追问；并给出活体入口的复跑配方。）
