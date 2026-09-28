# task 级判据单独成列，不给总分、不并入四列

Context: 本 ADR 由 round22（ADR 0048）的票 65 触发。要解决的问题是：现有四列（意图 / 工具 / 参数 / 槽位）都是**单维请求质量**，没有一列是**端到端终局**。

最刺眼的形态：**四列全绿而任务没办成**。模型选对了工具、参数也填了，但 biz-mock 返回 `NOT_FOUND` / `STATE_NOT_ALLOWED`（`AgentStateMachine.failedStep()` `:717-720` 已认这四种终态），四列依旧全绿，而用户什么也没拿到。

触发条件成立：`round19-spec-trust-observability.md:131`（登记第 4 项）的触发原文是「有人提出一条能机器判定『任务是否办成』且现有门禁承载得了的判据」——本 ADR 提出并裁定这条判据。

数据来源恰是 round19 刚交付的两个字段（这就是触发条件现在成立的原因）：`plan[]`（`AgentResult.PlanStep(tool, status, latencyMillis, arguments)`，票 48）与 `context.ruleIds`（票 49）。

Decision:

**一、判据面只读三个既有字段，零新增后端代码、零新增探针。**

```
kind_task == "action"   → plan 里 target tool 的 status == OK 且无 fallbackReason
kind_task == "policy"   → context.ruleIds 非空 且无 fallbackReason
kind_task == "escalate" → fallbackReason == USER_REQUESTED
plan 缺失（离线明细无该字段） → None = 未观测
```

**二、单独报一列 `task_done`，不给总分、不并入四列的任何百分比。** 四列是单维请求质量，`task_done` 是端到端终局；混进同一分母会让「四列全绿而任务没办成」这个**它要抓的形态**重新隐身。`unverifiable` 的**条数与比率一起报**（`None` 不是`False`，未观测不许静默计入分子或分母）。

**三、它不是四列的复读机，有两条机器可证明的反例。**
- **反例 A（四列绿、任务红）**：期望 `queryLogistics` + `orderNo=90001`，非归属者得 `NOT_FOUND` → 四列全绿，`task_done` 假。
- **反例 B（工具列红、任务绿）**：对偶矛盾 `ACT-ORD-09` vs `ACT-LOG-09`，模型选 `queryOrderDetail` 而旧口径 gold 只认 `queryLogistics` → 工具列红，`task_done` 真。

**四、落地形态：新模块 + 新夹具，不并进 `eval_suites.py`。** `scripts/eval_task.py`（`eval_suites.py` 形态）+ `eval/cases-part8-task.jsonl`（add-only）+ CI 一个 0 token 步。**保护现成 24 条夹具所在的文件**，且 `round19-spec-…:131` **逐字要求**「新增独立评测模块（`eval_suites.py` 形态）比改现有 gold 安全」。

**五、不碰 gold 的机器依据：** `verify_eval_judge.py:218/221` 只扫 gold 三文件；`part8` 不在 `GOLD_CASE_FILES`。

Considered Options:

- **并进 `eval_suites.py`**：否决。会动到 24 条夹具所在的文件，而那份文件正是 CI 门禁的断言载体；round19 的登记逐字要求新模块。
- **给 `task_done` 一个总分、或并进四列**：否决。见 Decision 二。
- **新增一个探针/后端字段来判定任务是否办成**：否决。round19 刚交付的 `plan[]` 与 `context.ruleIds` 已经足够，再加出口等于造第二本账。
- **`plan` 缺失时按失败计**：否决。离线明细里没有该字段是**未观测**，不是失败；按失败计会把「没测到」伪装成「没办成」，那正是本仓反复拒绝的形态。
- **把 `task_done` 做成第五列并重算总体准确率**：否决。会改现有公开读数的分母（AGENTS.md 铁律）。

Consequences:

- **新增一列公开读数**：README 的评测口径段要同步写清它与四列的关系（并列、不相加）；`docs/EVIDENCE.md` 登记 `part8` 夹具与判据来源。
- 夹具是 append-only：`eval/cases-part8-task.jsonl` 只增不改；`part8` 不进 `GOLD_CASE_FILES`，故 rescore 差异集合不变（仍恰好 4 条）。
- 本票**不改任何现有判据、阈值、gold 与分母**；它只增加一列，不重算旧列。
- 若将来 `plan[]` 的语义变化（例如新增终态），`kind_task` 的 action 判据要跟着复核 —— 触发线写在这里，避免下一个人拿过期的终态表判。
