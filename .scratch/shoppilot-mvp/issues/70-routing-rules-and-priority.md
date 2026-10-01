# 70 分流规则表、优先级与 SLA 计时

**Status:** implemented（2026-10-01）

## What to build

工单创建时按**可配置的规则表**分派队列（ADR 0055），并算 SLA 截止时间。

- `routing_rules` 表：`tenantId`（`*` 表示通配）、`intent`、`emotion`、`queue`、`priority`、`slaMinutes`、`enabled`；首次分派按**最长匹配优先**（tenant 精确 > intent 精确 > 通配），**未命中走默认队列**。
- 优先级枚举：`URGENT_EMOTION` > `MONEY` > `NORMAL`（先比来源与情绪，再比规则表给的基线）。
- SLA：创建时按队列的 `slaMinutes` 算 `slaDeadline`；超时**只打 `escalatedAt` 标记**，不承诺解决时限（0055 明确）。
- 规则表是数据不是代码：改动走 Flyway 迁移或管理端点，**每次改动发一条 `audit` 事件**（0056 的审计面）。

## Blocked by

[69](69-unified-ticket-entity.md)——分流需要统一实体当分母。

## 口径

规则表**不得**引入模型判断（LLM 分流在 0055 已否决并登记）。优先级与 SLA 的默认值要写进 ADR 的表格而不是散落在代码常量里；队列命名进 CONTEXT.md。

## 验收

- 命中/未命中/通配/租户覆盖四类分派各有 JVM 用例；未命中走默认队列而不是抛异常。
- 优先级排序可机验：同一队列里 `URGENT_EMOTION` 恒排在 `NORMAL` 之前。
- SLA 超时只打标记，不改工单状态（不得出现"超时自动关闭"这类行为）。
- 规则表改动的每一条都有 `audit` 事件可查。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/retrieval_gate.py        # 夹具与语料未被本票触及，应仍绿
python .scratch/shoppilot-mvp/round3-closeout-audit.py
```

## Handoff notes

### 关键决策

1. **匹配键用「租户 × 来源 × 降级原因」，不是 ADR 0055 字面写的「意图」**（偏离，已登记）。落单那一刻 `intent` 已经是 `ESCALATE`（`AgentStateMachine.fallback` 一律返回它），把原始意图接进落单请求要穿 21 个调用点，拿到的多半还不是买家真正要办的事；而「退款审批单去 REFUND、槽位问不齐的去 ESCALATION」这件事，来源与降级原因就是直接答案，且两列都已在工单上。
2. **优先级枚举名 ≠ 存储字面量**，这是被迫的：`verify-emotion.ps1:125/140` 在活体判据里逐字断言 `priority=high`（ADR 0034 定下的语义），改字面量就是改判据面。所以领域侧叫 `URGENT_EMOTION/MONEY/NORMAL`，存储侧仍是 `high/money/normal`——**换代的是这一列能表达什么（从二值变三档），不是它对既有消费者的说法**。旧值 `null` 的含义（「不是情绪升级」）等价于 `NORMAL`，V4 做了语义等价回填。
3. **规则表全局、不按租户切数据**：只有几行，读全表在内存里挑最具体那条。为它建租户索引是拿一种不存在的查询（一次读几行）去换另一种（每租户扫全表）。代价是租户级规则会影响全局匹配——这由 `hitsTenant` 的**硬条件**兜住（见下）。
4. **规则只能抬高优先级**：`TicketPriority.highest(baseline, rule)`。情绪升级与资金动作不能被一条泛化规则压到后面去——尤其是退款审批单，`MONEY` 基线来自**来源**而不是规则表，让它依赖「有人记得给退款配一条规则」是危险的默认。
5. **SLA 超时惰性求值**（在工单列表读路径上触发），**只打戳**：不改状态、不关单、不通知（ADR 0055 明确不承诺解决时限）。省掉一个定时线程池和一处停机负担。打戳记「观测到超时的时刻」，不倒填截止时间——倒填会让「超时多久」在两次读之间自相矛盾。
6. **规则表 API 本轮只读**，写入口刻意不做：规则改动一旦能从 HTTP 进来就必须有审计，而那正是票 71 的事件骨干要给的。在它接上之前规则只能经 Flyway 变更——那一步天生带提交记录。**触发条件 = 票 71 落地。**

### 用例逮到的两个真缺陷（都在本票范围内修掉）

1. **规则表跨店劫持**（严重）：`hitsTenant` 最初写成「租户不匹配反而 +4 分」，等于 A 店的规则会去劫持 B 店的工单。修法是把租户从「打分项」改成「**硬条件**」——「不匹配」和「更不具体」是两种不同的失格方式。**变异对照**：`hitsTenant` 恒返回 true → `tenantRuleOverridesWildcardWithoutLeaking` 转红（`expected "ESCALATION" but was "ESCALATION_T1"`），还原即绿。
2. **同型笔误三处**：`ANY.equals(tenantId)` 拿 `"*"` 去比**请求值**而不是规则列，导致通配行永不命中，所有工单都落到代码兜底的 `DEFAULT`。三处（tenant/source/reason）一起修。
3. **反馈复核工单没走分派**（票 69 遗留）：`FeedbackService` 直接构造 Ticket 并保存，绕过了 `createWorkItem`，所以那张单的 `queue` 是 `null`——**在坐席台里等于看不见**。「开了单但没人能领」比没开单更糟。现在它照样走 `RoutingService.assign`。

### 口径（一个数字都没动）

- 判据面：gold 180、阈值、`judge()`、`verify_eval_judge.py`、CI 八步——**零改动**。
- `tickets.priority` 的**存储字面量**未变（`'high'` 仍表示情绪升级，`verify-emotion.ps1` 逐字依赖）；`null` → `'normal'` 是语义等价回填，不是改分母。
- 审计 `G6_EXPECT` 仍不动（同票 69 的理由：它读上次活体矩阵落点，换代在收口时连同新落点做）。

### 验证落点

- `.\mvnw.cmd -B -ntp verify` → **`5 + 43 + 308 = 356`** 绿（biz-mock 36 → 43，新增 `RoutingDispatchTest` 7 条）。
- 覆盖率：biz-mock **79.88% → 80.74%**（门槛 76.0）、gateway 63.23%、tool-api 47.95%。
- 新用例 7 条：四来源各落自己的队列 + SLA 分钟数、退款审批拿 `money` 基线、规则只能抬高不能压低、租户规则覆盖且不外溢、分派可复现、超时只打戳且终态不回溯、规则表可查且写入口 405。

### 现场三问

1. **为什么不用 ADR 写的「意图」当匹配键？** 落单那一刻 intent 已经是 ESCALATE，要拿原始意图得穿 21 个调用点；而且工单要分的是「这类活该谁接」，来源与降级原因直接就是这个答案。
2. **为什么优先级存 `high` 而不存枚举名？** 因为 `verify-emotion.ps1` 逐字断言它。领域名与存储名分开，是「不能改判据」这条纪律逼出来的诚实做法，而不是偷懒。
3. **为什么规则表不给写端点？** 能改但查不到谁改的，比不能改更糟。写入口等票 71 的审计事件一起上。
