# round48：质量收口（读数可信 → 行为可回归 → 结构可维护）

开轮依据：[ADR 0083](../../docs/adr/0083-round48-quality-closure.md)（所有者政策覆盖，不自动续期）。
本轮**不新增产品能力**；gold / 阈值 / `judge()` / 降级枚举 / 指标分母零触碰。

## 0. 本轮要补的三格

```text
读数可信
  ├─ STATUS / README badge / README ADR 数 / TUNING_PLAN 基线 同一份真相   ← 票 200
  └─ 全量 179/180 与退款定向 18/18 分列，不合并口径                       ← 票 200

行为可回归
  ├─ LLM 请求/响应录放（promptVersion + model + messages hash）           ← 票 201
  ├─ CI 0 token 复算同一 judge()，缺 cassette 即红                        ← 票 201
  └─ 答案级 required/forbidden facts 判据                                 ← 票 202

结构可维护
  ├─ TurnOrchestrator + Retrieve/PlanExecute/Compose/Persist handlers      ← 票 203
  ├─ Testcontainers：Redis Streams + Postgres（nightly）                   ← 票 204
  └─ Vitest + Playwright 三条冒烟；App.vue 状态下沉                        ← 票 205

决策
  └─ 100–200 条检索集；单/双引擎；Stage3 路由表（影子模式）                ← 票 206
```

## 1. 票拆分与顺序

| 票 | 内容 | 依赖 | 预计 |
| --- | --- | --- | --- |
| [200](issues/200-doc-truth-sync.md) | 文档真值同步：STATUS / README / ARCHITECTURE / TUNING_PLAN / `current-metrics.json` / 审计 G6 台账 | 无 | 0.5 天 |
| [201](issues/201-llm-replay-gate.md) | LLM 录放回归门：`llm.mode=replay` + 录制器 + 离线 judge + CI 步 | 200 | 3–4 天 |
| 202（待 201 收口后立票） | 答案级评测：40 政策 + 20 动作，required / forbidden facts，确定性判分 | 201（复用回放） | 2–3 天 |
| 203（待 201 收口后立票） | 编排器拆分：TurnOrchestrator + 四个 handler，每类 <500 行 | 201（行为安全网） | 3–5 天 |
| 204（待 201 收口后立票） | 真实依赖集成层：Testcontainers Redis Streams + Postgres，nightly | 无 | 2–3 天 |
| 205（待 201 收口后立票） | 前端安全网：Vitest + Playwright 三条冒烟 + 状态下沉 | 无 | 2–3 天 |
| 206（待 201 收口后立票） | 检索与模型路由决策：100–200 条检索集、单/双引擎、Stage3 影子路由 | 200/201 | 1–2 天 |

**执行纪律**：一次只领一张；票 200 收口后才把 201 置为 `in-progress`。203/204/205 在 201 收口后
可并行（改的文件集互不重叠），但同一轮仍按票逐个收口。

## 2. 票 200 的判据

1. `python scripts/gen_status.py --check` 通过；STATUS 的测试数与一次真实 `mvnw verify` 一致。
2. README badge、README 第 609 行注释、`docs/STATUS.md` 的测试总数三处一致；ARCHITECTURE 的 ADR 篇数与
   `docs/adr/*.md` 实际数量一致。
3. `docs/current-metrics.json` 的「工具调用准确率」写 **179/180 = 99.4%**（dev qwen-plus，2026-10-27），
   `ACTION_REFUND` 94.4%；**退款定向重跑 18/18 单列**，不并入全量。
4. `docs/TUNING_PLAN.md` 不再出现「全分区 100%」「分意图红点已清零」这类把定向重跑当全量的说法；
   JVM 基线换代到当前值。
5. 收口审计 `G6_EXPECT` 按一次真实 verify 的四模块读数换代，并在注释里写明「台账换代，不是放宽判据」。
6. 历史表格（README 的 09-10 / 09-11 读数、DELIVERY 的更新记录）不改写；需要时只加「历史读数，当前见 STATUS」指针。
7. `git diff --check` 干净；收口审计的 `A2` 在提交前允许红，其余新增红必须归因。

## 3. 票 201 的判据

1. 新增 `SHOPPILOT_LLM_MODE=replay`（或 `shoppilot.llm.mode=replay`），生产默认值不变。
2. cassette 格式固定：`fixtures/llm-cassettes/<model>/<promptVersion>/<sha256>.json`，
   内含请求摘要、响应体、模型、promptVersion、录制时间；未知请求 **fail-closed**（缺 cassette 即红），
   不允许静默回退到真实模型或 Mock。
3. 录制器可以包住现有 `LlmClient` 录制真实响应；录制过程不改生产代码路径，只在装配层换实现。
4. CI 新增一步「LLM replay gate」：读 cassette、跑既有 `judge()` / 评测套件、0 token；
   与入仓基线逐条比对，差异必须显式更新基线，不能自动放行。
5. 至少交付一条端到端 JVM 用例：同一请求在 replay 模式下得到与录制时相同的工具选择与答案摘要；
   缺 cassette 时用例红。
6. **不声称覆盖模型漂移**：文档写清「回放守编排/提示词回归，模型能力仍靠周期性活体评测」。

## 4. 本轮不改

- gold（`eval/cases-part1-3.jsonl`）、阈值、`judge()`、降级枚举 10/降级 9、指标分母；
- 生产默认 LLM 模式与模型选择（票 206 决策）；
- 现有 ADR 原文（只新增 0083）与历史证据目录；
- 产品 UI 与路由（轮 45–47 已收口，本轮不加）。

## 5. 证据落点

- 票 200：`docs/STATUS.md`、`docs/current-metrics.json`、`README.md`、`ARCHITECTURE.md`、
  `docs/TUNING_PLAN.md`、`.scratch/shoppilot-mvp/round3-closeout-audit.py` 的 G6 台账。
- 票 201：`eval/llm-cassettes/`（新增）、`scripts/record_llm_cassettes.py`（新增）、
  `scripts/verify_llm_replay.py`（新增）、`.github/workflows/ci-subset.yml` 新增一步。
- 所有未达成项按既有纪律写进票面 Handoff 与 `docs/EVIDENCE.md`，不预填。
