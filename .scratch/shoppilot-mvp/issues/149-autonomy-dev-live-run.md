# 149 dev 活体跑 part10 一次 + 读数登记（预算 600k 落地）

**Status:** ready-for-agent

## What to build

用 dev 档（qwen-plus）把 part10 全量跑一次，把真实读数写进仓库；同时把日预算闸门的**值**抬到实测覆盖得了的水平
（闸门本体保留）。

- `.env`：`SHOPPILOT_LLM_DAILY_TOKEN_BUDGET` 260000 → **600000**；`.env.example` 注释同步
  （`.env` 不入库，只改 `.env.example` 并说明）。
- dev 档活体跑 part10 **全量 30 条**，逐条 0.25s 节流（≈4 QPS < `customer-qps=5`），每条前复位演示数据。
- 读数三件：**计费 token**（`shoppilot_llm_tokens_total` 增量 / Redis `shoppilot:llm:tokens:<day>` 差值）、
  **时长**、**三态与出口**（触发 / 不触发 / 用尽；`TOOL_UNAVAILABLE` 与 `TOOL_ROUNDS_EXHAUSTED`）。
- 明细与报告落 `eval/results/`（add-only），`docs/EVIDENCE.md` 增 part10 行（夹具、判据来源、实测单价、预算值）。
- 所有读数标注 **promptVersion = v1.2.0**（与 v1.1.0 不可混比）。

## Blocked by

[147](147-prompt-v1-2-0.md)、[148](148-autonomy-eval-set-and-module.md)（归因必须与 v1.2.0 同轮；集与判分模块先就位）。

## 口径

- 依据：[ADR 0069](../../../docs/adr/0069-round35-agent-autonomy-upgrade-reopen-basis.md) §五；140 的 AFK 实测是预算依据
  （单跑 ≈ **29 万 token**、30 条、均值 ≈9.6k；600k = 一次全跑 + 一次复跑）。
- **花真钱**：执行前确认 `.env` 的 dev key 与当日额度；若只批「单跑 + 重试」口径则预算取 **400000**（ADR 0069 记录）。
- 成本读数**不读响应 token 字段**（R12 的三处缺口即便已在 145 修，评测列仍以指标增量为准，口径单一）。
- gold 180 / 阈值 / 分母零触碰；本票不重跑 gold（U7 是独立决策）。
- 实测口径可复跑：命令、端点、节流、复位与计费算法逐条写进 Handoff。

## Verify

```powershell
# 预检：网关 dev 档与 biz-mock 已起、额度可用（不要把 biz-mock 没起的一轮当真读数）
python scripts/eval_autonomy.py            # 0 token 自检先绿
# 活体跑批（入口按票 148 落地后的实际参数）
python scripts/run_tool_eval.py --autonomy
git diff --check; git status --short
```

## Handoff notes

（解决时补：实测读数表 + 复跑配方 + 与 140 的预估差 + 三个现场追问。）
