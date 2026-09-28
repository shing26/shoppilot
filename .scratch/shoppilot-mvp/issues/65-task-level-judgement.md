# 65 — task-level 判据最小形态（单独成列，不给总分）

**What to build:** 补上「这件事最终办成了吗」这一列。现有四列（意图 / 工具 / 参数 / 槽位）都是**单维请求质量**，没有一列是**端到端终局** —— 最刺眼的形态是**四列全绿而任务没办成**：模型选对工具、参数也填了，但 biz-mock 返回 `NOT_FOUND` / `STATE_NOT_ALLOWED`（`AgentStateMachine.failedStep()` `:717-720` 已认这四种终态），四列依旧全绿，而用户什么也没拿到。本票新模块 + 新夹具 + CI 一步，独立报 `task_done`。

**Blocked by:** None（可立即开始）。

**Status:** implemented（2026-09-28）。

**依据：触发已到（ADR 0048）。** `round19-spec-trust-observability.md:131` 登记第 4 项的触发原文是「有人提出一条能机器判定『任务是否办成』且现有门禁承载得了的判据」——本票提出并落地这条判据，**故不需要政策覆盖**。

口径（ADR 0050 已立契，本票只执行）：

- **判据面只读三个既有字段，零新增后端代码、零新增探针**（数据来源恰是 round19 刚交付的 `plan[]` 与 `context.ruleIds`，这就是触发条件现在成立的原因）：

```
kind_task == "action"   → plan 里 target tool 的 status == OK 且无 fallbackReason
kind_task == "policy"   → context.ruleIds 非空 且无 fallbackReason
kind_task == "escalate" → fallbackReason == USER_REQUESTED
plan 缺失（离线明细无该字段） → None = 未观测
```

- **单独报一列 `task_done`，不给总分、不并入四列任何百分比**：混进同一分母会让「四列全绿而任务没办成」这个**它要抓的形态**重新隐身。`unverifiable` 的**条数与比率一起报**（`None` 不是 `False`，未观测不许静默计入分子或分母）。
- **它不是四列的复读机**，两条机器可证明的反例必须有用例：**反例 A（四列绿、任务红）** 期望 `queryLogistics` + `orderNo=90001`、非归属者得 `NOT_FOUND`；**反例 B（工具列红、任务绿）** 对偶矛盾 `ACT-ORD-09` vs `ACT-LOG-09`（模型选 `queryOrderDetail` 而旧口径 gold 只认 `queryLogistics`）。
- **落地形态：新模块 + 新夹具，不并进 `eval_suites.py`**。`scripts/eval_task.py`（`eval_suites.py` 形态）+ `eval/cases-part8-task.jsonl`（add-only）+ CI 一个 0 token 步。这既保护现成 24 条夹具所在文件，也照 `round19-spec-…:131` 逐字要求。
- **不碰 gold 的机器依据**：`verify_eval_judge.py:218/221` 只扫 gold 三文件；`part8` 不在 `GOLD_CASE_FILES`，故 rescore 差异集合不变（仍恰好 4 条）。
- **不改任何现有判据/阈值/gold/分母**：只增一列，不重算旧列。

- [x] `scripts/eval_task.py`（判据 + 自带夹具自检，0 token、无网关可跑）
- [x] `eval/cases-part8-task.jsonl`（add-only；含反例 A、反例 B 各一条）
- [x] `task_done` 单独成列输出；**不产生总分、不并入四列**
- [x] `plan` 缺失记 `None` 并计入 `unverifiable`，`unverifiable` 条数与比率一起报
- [x] CI 加一个 0 token 步
- [x] gold 三文件与 rescore 差异集合未动（仍恰 4 条）
- [x] `verify_eval_judge.py` 40/40；`git diff --check` 干净

**Verify**
```bash
python scripts/eval_task.py
python scripts/verify_eval_judge.py
./mvnw.cmd -B -ntp verify
```

## Handoff notes

**关键决策**

- **判据面只读三个既有字段，零新增后端代码/探针**：`plan` / `context.ruleIds` / `fallbackReason`（都是 round19 票 48/49 交付的）。这既是 ADR 0050 的口径，也是「触发已到」的依据本身 —— 触发原文要的就是「一条能机器判定任务是否办成**且现有门禁承载得了**的判据」。
- **`None` 与 `False` 严格分开**：`"plan" not in result` / `"context" not in result` / `"fallbackReason" not in result` → `None`（未观测）；而 **`plan == []` 是已观测**（live 响应恒带 `plan` 数组，票 48 钉过「空数组不是 null」），含义是「目标工具没被调用」→ `False`。这条是判据的承重缝：把缺失当失败会让「没测到」伪装成「没办成」。
- **反例 A/B 落在夹具里而不是用例里**：它们是**合成 result 驱动的机器证明**（0 token、确定性），证明 `task_done` 不是四列的复读机 —— 用例文件（part8）放的是三条真跑用例，因为「四列都绿」这个前提在真跑里不可控。**这一点与票面「jsonl 含反例 A、反例 B 各一条」的字面略有出入**，如实记在这里。
- **独立成模块 + 复用既有 runner**：判据在 `scripts/eval_task.py`（不动 `eval_suites.py` 那份 24 条夹具载体），活体入口是 `run_tool_eval.py --task`（add-only 一个 flag，复用该文件的 HTTP 助手，**不写第二份发 HTTP 的代码**）。`run_tool_eval.py` 自票 34/35 起已从审计内容级禁面摘出，故这次编辑不违 B7；`verify_eval_judge.py`（仍在禁面）未动，仍 40/40。
- **聚合**：`summarize()` 只报 `task_done` / `没办成` / `未观测`（含比率）与 `已观测`，**没有任何总分字段**；打印时也明写「独立一列、不给总分、不并入四列」。

**验证落点**

- 夹具自检：`python scripts/eval_task.py` → **TASK SELFCHECK ok=14**（含反例 A、反例 B、三条 `None` 分支、未知 kind 抛错）。
- 门禁不变：`python scripts/verify_eval_judge.py` **40/40**；`python scripts/eval_suites.py` **ok=24**（24 条夹具未动）。
- `gold` 与 rescore：`part8` 不在 `GOLD_CASE_FILES`，`build_eval_set.py` 的 `PARTS` 只含 part1-3，故 180 条结构与 rescore 差异集合不受影响。
- **活体**：`python scripts/run_tool_eval.py --task` 跑通全链（`SCORER SELFCHECK ok=16` → `TASK SELFCHECK ok=14` → 出明细 CSV），读数见下。**语义正例已取到**：`task_done 3 / 没办成 0 / 未观测 0`。

**未达成（按实登记，不摘红）**

- ~~活体正例读数未取到~~ —— **已补（2026-09-28 晚）**：显存空出来之后生成模型加载成功，`python scripts/run_tool_eval.py --task` 得 **`task_done 3 / 没办成 0 / 未观测 0`**（明细 `eval/results/tool-eval-20260928-173008-dev-task.csv`）：action 那条真调到 `queryOrderDetail` 并答出订单状态、policy 那条答在条款上、escalate 那条落 `USER_REQUESTED` + 工单号。**本次两次读数并列供着**：更早那次（`…-125025-dev-task.csv`，`1/2/0`）是**环境红**——当时本机 Ollama 无模型驻留、`cudaMalloc failed: out of memory`（四套项目容器抢 4 GB 显存），两条 model-dependent 用例落 `LLM_*` 降级、判据如实判「没办成」。那次读数证明的是**判据能正确判负**，这次证明的是**语义正例成立**；两者都不是摘红的产物。
- **CI 那一页只有夹具自检**（与 `eval_suites.py` 同例）：part8 的活体跑批进不了 CI（要网关 + 模型），能进 CI 的是判据本身。

**你需要能当场回答的三个追问**

1. *Q：为什么单独一列而不给总分？* A：四列是单维请求质量、`task_done` 是端到端终局。混进同一分母，会让「四列全绿而任务没办成」这个**它专门要抓的形态**重新隐身 —— 那个形态恰恰是分母变大后最难看见的。ADR 0050 把这条立成契约。
2. *Q：`plan` 缺失为什么记 `None` 而不是 `False`？* A：离线明细不带 `plan`（它落的是 CSV，没有这一步的字段）——「没测到」与「没办成」是两件事。记 `False` 等于把未观测静默计入分母，那是本仓反复拒绝的假绿形状（与 gold 的「未观测断言」列同一纪律）。
3. *Q：为什么反例 A/B 在夹具里而不是用例文件里？* A：反例的成立前提是「四列都绿」——真跑里这个前提不可控（取决于模型与业务返回），而夹具用合成 result 就能**确定性地**证明「同一条 result 在四列上是绿的、在 `task_done` 上是红的」。判据的机器证明要的是确定性，不是一次运气好的真跑。