# 65 — task-level 判据最小形态（单独成列，不给总分）

**What to build:** 补上「这件事最终办成了吗」这一列。现有四列（意图 / 工具 / 参数 / 槽位）都是**单维请求质量**，没有一列是**端到端终局** —— 最刺眼的形态是**四列全绿而任务没办成**：模型选对工具、参数也填了，但 biz-mock 返回 `NOT_FOUND` / `STATE_NOT_ALLOWED`（`AgentStateMachine.failedStep()` `:717-720` 已认这四种终态），四列依旧全绿，而用户什么也没拿到。本票新模块 + 新夹具 + CI 一步，独立报 `task_done`。

**Blocked by:** None（可立即开始）。

**Status:** ready-for-agent

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

- [ ] `scripts/eval_task.py`（判据 + 自带夹具自检，0 token、无网关可跑）
- [ ] `eval/cases-part8-task.jsonl`（add-only；含反例 A、反例 B 各一条）
- [ ] `task_done` 单独成列输出；**不产生总分、不并入四列**
- [ ] `plan` 缺失记 `None` 并计入 `unverifiable`，`unverifiable` 条数与比率一起报
- [ ] CI 加一个 0 token 步
- [ ] gold 三文件与 rescore 差异集合未动（仍恰 4 条）
- [ ] `verify_eval_judge.py` 40/40；`git diff --check` 干净

**Verify**
```bash
python scripts/eval_task.py
python scripts/verify_eval_judge.py
./mvnw.cmd -B -ntp verify
```