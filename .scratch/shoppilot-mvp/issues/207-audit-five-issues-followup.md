# 票 207：审计五问的残余修复（文档真值门禁 + Stage3 影子观测）

Round: 48 补口（spec：[round48-spec-quality-closure.md](../round48-spec-quality-closure.md)）
ADR: 0083　Status: implemented　Blocked by: 无（所有者 2026-11-05 指令：五问未修就修）

## 背景

round48 的 200–206 是按 2026-11-03 审计的「五个必须正视的问题」设计的。收口后逐条复核，
其中两条仍有**可修的残余**，本票只修这两条，不改产品行为、不改判据：

1. **文档真值仍在漂**：`gen_status.py --check` 只守 `docs/STATUS.md`，管不到 README 的
   JVM Tests badge、复现段、CI 段的「**700 条**」与 ADR 篇数。实测 README CI 段写
   `700 条（12 + 76 + 50 + 600）`，产物是 `783（12 + 92 + 50 + 629）`——审计第 2 条点名的
   「三个真相」还差最后一处。
2. **Stage3 路由只停在决策**：`docs/STAGE3_MODEL_ROUTING.md` 建议「政策走 local、动作走云端」，
   206 只写了决策与设计，没有可运行的观测面，所以「默认真实模型路径弱于演示路径」无法用
   真实流量取证。审计第 3 条要的「先影子模式」没有落地。

## What to build

1. `scripts/verify_doc_truth.py`：从 surefire 现算四模块测试/套件数，逐处比对
   README badge、复现段、CI 段、TUNING_PLAN 与 STATUS；ADR 篇数与 `docs/adr/*.md` 比；
   README 的「唯一指标名」计数与 `src/main` 源码现算比。**进 CI，折进既有
   `Coverage ratchet + status/compose checks` 一步，不加步数。**
2. Stage3 影子观测：`shoppilot.routing.shadow`（默认 false）；开启后只在模型路径记
   `shoppilot_routing_shadow_total{intent_class,suggested,actual,agree}`。
   **不改变调用的模型、提示词、工具面与 SSE 事件**；关闭时零开销。
3. 把 206 决策文档里「影子不实现」改成已实现、默认关闭，并写明原设计的审计字段为何没做。

## 边界

- 不改默认 LLM 模式/模型、不启用按意图路由、不加真正的路由开关；
- 不改审计契约、不加 SSE 事件、不改 gold/阈值/`judge()`/降级枚举/语料；
- 文档真值只改**可复算的读数**，不改历史读数和口径说明；
- 不碰检索夹具与 120 条用例集（#4 的测量仍缺活体栈）。

## Verify

```bash
python scripts/verify_doc_truth.py
python scripts/gen_status.py --check
./mvnw.cmd -B -ntp verify -Dspring-boot.repackage.skip=true
git diff --check
```

## 验收项

- 真值门禁在修前对 7 处漂移全部报红（实测），修后通过；
- 影子默认关闭时 Registry 里没有任何 `shoppilot_routing_shadow_total`；
- 开启后 local 档：政策 agree=true、动作 suggested=dev agree=false、ESCALATE suggested=none agree=true；
- 默认模型/路由/审计/SSE 零变化；JVM 用例只增不减，读数换代到 783 / 132。

## Handoff notes

**关键决策**

- **真值检查读产物、不读文档里的另一份数字**：测试数一律从 `target/surefire-reports` 现算，
  指标名从 `src/main` 现扫；README/STATUS/TUNING_PLAN 只作为被比对对象。这样它和
  `gen_status.py` 同源，不会出现「两份生成逻辑互相背书」。
- **锚点缺失也算失败**：README 的复现段、CI 段、指标段被改写时门禁宁可红一次，也不静默跳过。
- **影子只记计数器，不写审计字段**：审计契约（ADR 0072/0024 的事实面）不为观测层改动；
  计数器已能回答「建议路由与实际模式是否一致」。这条与原设计的差异写进决策文档，不藏。
- **影子挂在模型路径，不挂在 triage 之后**：缓存命中与转人工不选模型，记进去会把路由分布
  稀释成「缓存命中率」。ESCALATE 例外地在该路径之前就被拦下，所以 `suggested=none` 这一格
  由单测覆盖，不会在线上出现；这是刻意的（它本来就不该进模型分布）。
- 关闭时**不建计数器、不做映射**：`RoutingShadowTest` 第一条用例直接断 Registry 为空。

**验证落点**

- 修前 `python scripts/verify_doc_truth.py` 报 **7 处**：STATUS 780 vs 783、suites 131 vs 132、
  README badge 780、复现段 626/780、CI 段 700（12+76+50+600）、TUNING_PLAN 两处 780/131、
  README 指标名 73 vs 74。修后全部通过。
- 修后：`文档真值 OK：783 tests / 132 suites`；`gen_status.py --check` 通过。
- 全量 `./mvnw.cmd -B -ntp verify -Dspring-boot.repackage.skip=true` **BUILD SUCCESS**（2:57），
  四模块 **12 / 92 / 629 / 50 = 783 tests / 132 suites**、0 失败。
- 新增 `RoutingShadowTest` 3 条：关闭零登记、local 档三分类一致/不一致、dev 档累加。
- 指标名 73/75 → **74/76**（新增一个名字，不是加标签）；README 口径行同步。
- `docs/retrieval-quality-metrics.md` 重跑后零改动。

**五问现状（本票收口时）**

| # | 问题 | 状态 |
| --- | --- | --- |
| 1 | AI 行为没有回归门 | **已修（有残余）**：201 录放门 + 202 答案级 60 条 + 204 Testcontainers nightly；残余是 cassette 只有 6 张（审计要求 180 gold）、答案级 56/60 |
| 2 | 指标真值漂移 | **已修**：200 换代 + 本票补 README/CI/TUNING/ADR 一致性门禁，修前 7 处漂移全报红 |
| 3 | 默认真实模型路径弱于演示 | **部分修**：默认不变（证据不足），影子观测已落地可取证；转正需过四门槛 |
| 4 | RAG 证据不足 | **未修**：120 条用例集与判据已备，但**夹具未录**（无 Docker/ES/Qdrant），也没有标注难负例/不可回答问句；单/双引擎仍无 holdout 读数 |
| 5 | 复杂度与流程成本 | **已修**：203 拆分（1768→73 行，handler <500）+ 205 前端测试与状态下沉 |

**现场追问**

1. 若 CI 上 `verify_doc_truth.py` 红而本地绿：先确认它排在 `verify` 之后（surefire 产物在各模块
   `target/`），再看是不是 README 换了说法导致锚点找不到——修锚点要连同门禁一起改。
2. 若想打开影子：`SHOPPILOT_ROUTING_SHADOW=true` 后看 `shoppilot_routing_shadow_total` 的
   `agree` 分布；**不要**顺手改默认模式，转正门槛在 `docs/RETRIEVAL_ROUTING_DECISION.md`。
3. 若下一轮开始录 120 条夹具：先起网关 + ES + Qdrant，用
   `python scripts/record_retrieval_fixture.py --cases eval/retrieval-cases.json`；录完先看
   `RetrievalQualityMetricsTest` 打印的自标注读数，别直接改 `rrf-k`。
