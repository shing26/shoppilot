# 121 180 条标定重跑 + 酒店评测分区

**Status:** implemented

## What to build

多一个质心会改变 T1 最近邻地形，阈值结论必须重验；酒店评测单独成列。

- 重跑 `python scripts/calibrate_intent.py`（需本机 Ollama + bge-m3）：确认
  `MIN_CONFIDENCE` / `MIN_MARGIN` 的工作点结论；`calibrate_intent.py` 的候选集标签由写死的
  「全 10 意图」改成跟着实际意图数走。
- 酒店评测：`eval/cases-part9-hotel.jsonl`（12 条，`kind: hotel`，`tenant: T004`），
  `eval_suites.py` 加 `hotel` kind 与 `score_case` 分支（定案意图 / 不下工具 / 有引用 /
  命中缓存四格）+ 六条夹具（含四条反证）；`run()` 改成**按用例租户现领令牌**。

## Blocked by

[120](120-hotel-tenant-and-corpus.md)。

## 口径

- **不并入 180 条 gold**（动分母，违反红线）；酒店单独成列、不给总分、不并入四列。
- 标定结论以实测为准；阈值常量不凭手感改。

## Verify

```powershell
python scripts/calibrate_intent.py
python scripts/eval_suites.py
.\mvnw.cmd -B -ntp verify
git diff --check; git status --short
```

## Handoff notes

- **关键决策**：重跑结论**与现状一致**——选定工作点仍是 `conf=0.70 / margin=0.02`，
  政策问句准入率 72.7%、判错政策桶 1/24、动作误判为政策 0 条。**加酒店质心没有移动 180 条
  电商 gold 的任何一条读数**（酒店词表与电商四条政策词表零重叠，酒店质心离电商问句很远）。
- **验证落点**：`docs/intent-calibration.md`（重跑生成，字节未变）；`python scripts/eval_suites.py`
  → `SUITE SELFCHECK ok=30`（24 → 30，含酒店六条）。
- **三个现场追问**：
  1. 标定脚本的 `t0_decides` 只认四条电商政策词表，**没把酒店词表纳入**——酒店用例不在 180 条
     gold 里所以不影响结论，但要不要让脚本模型与 T0 完全对齐？
  2. 酒店 12 条里 `HTL-REP-01` 依赖前一条 `HTL-CHK-01` 先写回缓存——单跑该条会假红，是否要把它
     改成自带预热的用例？
  3. 酒店评测要不要一个活体 0 token 命中的单独读数（现在只在套件里体现），还是并进既有 audit？
