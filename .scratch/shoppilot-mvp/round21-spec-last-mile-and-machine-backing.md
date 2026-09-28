# round21 / round22 规格：闭环的最后一公里 + RAG 的机器背书

> **状态（2026-09-28）**：**round21 已开轮**（ADR 0046 = 轮范围与逐票依据，ADR 0047 = 退款审核语义与契约）。
> 票 **57、58 已收口**；票 **59-63 开放**，范围与口径见本 spec §2 / §3 / §4，**ticket 文件在领取时按仓库形制拆出**
> （本 spec 就是它们的 Why 与 How）。
> **round22（票 64-68）已另开轮**（2026-09-28）：权威规格见 [`round22-spec-machine-backing.md`](round22-spec-machine-backing.md)，
> 决策见 ADR `0048`-`0051`。**本文件里 round22 的那两节（§2 的 round22 表、§3 的票 64-68）已降级为开轮前的草案**，
> 与 round22 spec 冲突时**以那份为准**（逐票口径基本一致，差异在 promtool 的引入方式、告警条数与 CI 步序）。
>
> 产生方式：2026-09-28 外部审计 `D:\WorkBuddyData\Agent项目七维架构审计-v2-分类修正-20260927.md` 的
> ShopPilot 指控 → 两轮分域调研（AI-Architect / RAG-Data-Specialist）→ 主控逐条对仓核实 → **14 项决策问答**。
> §6 是那 14 项问答的裁定记录，逐条附理由。

**基线**：HEAD `c28eb04`（票 57/58 之前的落点），round20 已收口。<br>
**票 57/58 之后的读数（2026-09-28）**：JVM `3 + 21 + 287 = 311` 绿；覆盖率 gateway **59.47%**（门槛 54.0）、
biz-mock 77.49%（门槛 76.0，**余量仅 1.49pp**）、tool-api 41.73%（门槛 40.0）；
22 步活体矩阵 20 绿 / 2 红（`feedback`、`plansteps`，按登记保留、判据不动）。

---

## 1. 审计逐条核实（本轮的分析结论）

审计对 ShopPilot 提了四条指控 + 一处数字纠正。**逐条对仓核实后，只有一条是全新发现。**

| # | 审计指控 | 核实结论 | 证据 |
|---|---|---|---|
| 1 | 高风险动作无人工确认，`applyRefund` 槽位齐备即落库 | **成立，但早已登记** | `BizMockService.java:165-218` 在 `:202-209` 一个事务插 `Refund("PROCESSING")` + 置 `Order→REFUNDING`；三模块 `src` 下 `approval\|confirm` **0 命中**（复核过）。但 `round19-spec-trust-observability.md:130` 登记第 3 项已把它登记为**政策决定** |
| 2 | 零告警规则 | **成立，且本轮可做实** | 仓内无任何 `*.rules.yml`/`*alert*`/`*prometheus*`。ADR 0030 第 5 条的触发线是「出现可机器寻址的 Prometheus/Alertmanager 形态」，而 ADR 0024 排除它的**核心理由**是"写了只能证语法、**证不了该响时会不会响**" —— `promtool test rules` 证的正是这一句（见 §4.4） |
| 3 | 记忆仅会话级、多实例不可用 | **⚠️ 说得不精确** | 会话已 Redis 外置（`SessionStore.java:115-119`），限流（Redisson）与 token 预算同为 Redis 后端 → "多实例不可用"对这三者**不成立**。真正进程内的只有 `feedback/FeedbackService.java:50` 的 `trails` |
| 4 | 180 条活体评测不进 CI | **成立，早已登记** | `round19-spec-…:133` 登记第 5 项；CI 五步只覆盖「判据不漂移」 |
| 5 | README 写 41 个指标，实测 52 | **成立，且比审计说的更糟** | 按 README 自述数法实算 = **52**；`round18-spec-scoring-dimension-completeness.md:14` 在 round18 就记「**51 个指标名 / 59 个注册点**」→ README 的 41 **从 round18 起就落后**，round19 又加了 `shoppilot_embedding_latency_seconds` |

**审计没看出来的那一格（比它列的四条更重要）**：它给 D4（RAG）/D6（评测）都判 ✅ —— 架构层成立 —— 但它没问
「**谁在机器上守着这条链**」。实测：CI 里**没有任何一条断言在守检索排序**（`eval_suites.py:130-235` 的 24 条夹具按
`kind ∈ {emotion, channel, plan, style}` 分发，四个 kind 都不读 `ruleIds`/`citations`/RRF 序）；
唯一那份 RAG 质量证据 `docs/retrieval-comparison.md` 里 16 条查询 `dense` 与 `hybrid` **名次完全相同**
（`:30-31`、`:61`）→ **对 RRF 回归判别力为零**（把 `rrf-k` 从 60 改成 30，CI 全绿）。

**"完整"断在两处最后一格**：① **闭环最后一公里** —— 钱动了没人看过、客户端重试拿空答案；
② **机器背书最后一格** —— 最强的子系统（检索）进 CI 覆盖为 0。

---

## 2. 两轮的票号、依据与顺序

每张票的依据分**四类**（这个分类本身就是「能不能立刻动手」的判据，也是 ADR 0046 的主体）：

| 类 | 含义 | 票 |
|---|---|---|
| ① **事实修正** | ADR 0031 明文「事实错误…不受限制」，**不需要 ADR** | 57 |
| ② **契约正确性修复** | 兑现既有 `idempotencyToken` 语义，不新增功能面、不新增 intent/工具/事件 | 58 |
| ③ **政策越过** | 冻结期新功能面，靠所有者政策 | 59 / 60 / 61 / 62 / 64 / 66 |
| ④ **触发条件本次成立** | 登记项写明的触发条件**真实满足**（不是自引） | 65（task 判据）/ 67（告警） |

> **票 64 属第 ③ 类的特别说明**：round19 登记第 5 项的触发写的是「**出现可离线复跑的录制/回放路径**」，
> 而**建这条路径就是票 64 本身** —— 引自己当依据是循环论证。它必须诚实记为**政策越过**，
> 收口时不许讲成「登记第 5 项的触发已成立」。

### round21 —— 闭环最后一公里（需 ADR 0046 + 0047；票 57 / 58 先行）

| # | 票 | 依据 | Blocked by | ≤1 天 |
|---|---|---|---|---|
| 57 | 指标名计数换代 + 活体报告 provenance | ① 事实修正 | 无 | ✅ 0.5d |
| 58 | 幂等重放时机前移 | ② 契约修复 | 无 | ✅ 1d |
| 59 | 退款审批闸门（后端） | ③ | ADR 0046 + 0047；建议在 58 后（共用话术渲染器） | ❌ 2d |
| 60 | 买家读回（`OrderView` 带退款审核态） | ③ | 59 | ✅ 0.5d |
| 61 | 调试台审核面板 | ③ | 59 | ✅ 0.75d |
| 62 | 审批闸门活体验收 | ③ | 59-61 | ✅ 0.5d |
| 63 | round21 收口 | — | 57-62 | ✅ 0.5d |

**小计 ≈ 5.75 人日。**

### round22 —— 机器背书（开轮时按同样形制评估新的依据文档）

| # | 票 | 依据 | Blocked by | ≤1 天 |
|---|---|---|---|---|
| 64 | 检索融合 0 token 录放回归门（并入 Chunker/Retriever 聚焦单测） | ③（**不是**触发已到，见上） | — | ✅ 1d |
| 65 | task-level 判据最小形态 | ④ | — | ✅ 1d |
| 66 | token 计量加 `source` 标签 | ③ | 57（计数耦合） | ✅ 0.5d |
| 67 | 告警最小集 + `promtool test rules` | ④ | — | ✅ 1d |
| 68 | round22 收口 | — | 64-67 | ✅ 0.5d |

**小计 ≈ 4 人日。**

> **票号说明**：Q14 选项里草写的 round21 = 57-62、round22 = 63-67；实际每轮各需一张独立的收口票
> （仓库每轮都有：票 52、票 60 同形），故整体上移一位。

---

## 3. 逐票规格

### 票 57 — 指标名计数换代 + 活体报告 provenance（依据 ①，可立即执行）

**落点（只有 4 处，连锁引用已核）**
1. `README.md:199`：`41 个唯一 shoppilot_* 指标名` → **52**。**数法描述一字不改**（口径是对的，坏的是数没换代）。
2. `docs/interview-qa.md:574`：`这次实测字面值去重为 41` → 52。
3. `README.md` 的 round14 落点段（`commit=8c4b616`）「指标名按三模块 `src/main` 去重后为 41」：
   **保持 41 不动**，其后加换代指针（round18 时点 51 → round19 加 embedding 计时器 → 52）。
   **这一处推翻专家建议**：那是**历史落点读数**，按 ADR 0021「两套读数并列」与「旧值原样供着 + 换代指针」的家法，
   改数等于篡改历史记录。
4. `scripts/retrieval_compare.py`、`scripts/calibrate_threshold.py` 报告表头各加一行元数据
   （`generated_from_commit` / `corpus_sha256` / `kb_epoch` / `llm_mode`）。**只加表头，不重生成正文**（重生成会改读数）。

**已核连锁引用**：`docs/EVIDENCE.md`、`docs/loadtest-report.md` 对「41」**无命中**。

**Verify**
```bash
grep -rhoE 'shoppilot_[A-Za-z0-9_]+' shoppilot-gateway/src/main shoppilot-biz-mock/src/main shoppilot-tool-api/src/main | sort -u | wc -l   # 52
grep -rn '去重为 41\|41 个唯一' README.md docs/interview-qa.md   # 只剩历史落点那 1 处
git diff --check && git status --short
```

---

### 票 58 — 幂等重放时机前移（依据 ②，可立即执行）

**要解决什么**：`idempotencyToken` 的语义是「客户端重试不该重复执行、应回放」，但现状把这条承诺**挂在模型行为上**
—— 重复检测在 `ToolDispatcher.dispatch`→`executeWrite`，只有模型**真的再发一次工具调用**才会执行；
本地 3B 在已含上一轮成功答复的会话里不发调用，请求走到 `done` 而 `answer` 为空、**既无回放也无降级话术**
（round20 登记第 5 项，探针 3/3 复现）。

**落点**
- `agent/IdempotencyService.java`：新增请求级索引 `shoppilot:idem:req:{md5(tenant|customer)}:{md5(clientToken)}`
  → `{tool, queryHash, resultJson}`；`complete(...)` 仅在 token **由客户端显式提供**时写它；
  `(tenant, customer, tool, token)` 主键与两态语义**不变**；新增
  `Optional<Replay> lookupByClientToken(tenantId, customerId, clientToken, normalizedQuery)`
  —— 仅当 token 非空 **且** `queryHash` 相等才命中，否则 `empty()`。
- `agent/AgentStateMachine.run`：在 `triageEngine.triage` **之前**插入回放判定；命中即发 `meta` + `duplicate_submit`、
  用确定性渲染器出答案（**不打模型**）、追加一轮会话、返回。
- 新增 `agent/ReplayReply.java`：`render(tool, json)`（本票）与 `pendingApproval(tool, json)`（票 59 用）。
  抽 helper 而非内联，避免加重 `CODE_MAP.md:87` 已登记的「`AgentStateMachine` 过长」这笔债。

**为什么放在状态机**：`ChatController` 的同步/流式两条路径已有重复决策（`CODE_MAP.md:89` 登记的债），
放状态机一处即两条通路同时生效，**不加重该债**。

**契约与 gold**：10 状态枚举不动；SSE 不新增事件（复用 `duplicate_submit`）；`ToolStatus` 不动；
**gold 180 条零影响** —— 已核 `scripts/run_tool_eval.py:519` 的 payload 是 `{"query": case["query"]}`，**评测不发 token**。

**Verify**：`.\mvnw.cmd -B -ntp verify` / `python scripts/check_coverage.py` / `python scripts/verify_eval_judge.py`（40/40）
/ `pwsh -NoProfile -File scripts/verify-idempotency.ps1`

**验收项**：① 同 token + 同 query → 确定性回放、无空答案、`duplicate_submit` 已发、**零 LLM 调用**；
② 同 token + 不同 query → 放行；③ 跨买家/跨租户不互通；④ **变异对照**：去掉 `queryHash` 比较 → 「换了要求」用例必红。

**已知风险**：`verify-idempotency.ps1` 的两次调用是「不同 conversation id、同 query、同 token」→ 预回放会命中，
按脚本逻辑推断该步**更稳**（不再依赖模型重发工具），但**必须活体实跑确认**，不许按推断声称已绿。

---

### 票 59 — 退款审批闸门（依据 ③；ADR 0046 + 0047）

**设计（方案三）**：业务侧受理态 + 异步人工审核 + 确定性受理话术。**核心判断一句话**：
**HITL 不必是对话轮次，可以是状态迁移的门** —— 所以 ADR 0008 的 2 轮预算与 ADR 0036 的 Plan ≤2 步**一字不动**。

**落点**
- `shoppilot-tool-api`
  - `tool/view/ToolStatus.java`：新增 `PENDING_APPROVAL`。**已核安全**：全仓无 `values()` 遍历、无穷尽 switch
    （只用 `==` 比较），`BizMockClient.interpret` 的 `valueOf` 直接接受新串。
  - `ToolName`：新增**声明式审批策略**（与 `intent()` 同层），本票只有 `APPLY_REFUND` 返回 true。
    判据是「该动作是否**不可逆或涉及资金**」，不是「是否写库」（先例：`IdempotencyService.isWrite(ToolName):55`）。
  - `ToolResponse.succeeded()`（`:28-30`）**保持** `OK || IDEMPOTENT_REPLAY` 不变（避免涟漪）。
- `shoppilot-biz-mock`
  - `service/BizMockService.java:165-218` `applyRefund`：插入 `Refund(status="PENDING_REVIEW")`（原 `PROCESSING`，`:204`）；
    **保留** `Order→REFUNDING`（`:206`）作「已受理、冻结后续改动」标记；返回 `ToolStatus.PENDING_APPROVAL`。
    **不加 `prior_status` 列** —— 订单表已有 `paidAt`/`shippedAt`/`deliveredAt`（`Order.java:82-89`），
    而 `refundable()` 只允许 `PAID|SHIPPED|DELIVERED`，故先前状态可**无损推导**。
  - 新增 `reviewRefund(refundId, decision, note)`：`APPROVE` → `PROCESSING`；`REJECT` → `REJECTED` + 按推导回滚订单；
    已审过的再审 → `STATE_NOT_ALLOWED`。**无 V3 迁移**（留痕价值主要落在"记审核人"上，而 `CONTEXT.md:93`
    已定「运维凭证不证明身份」——不记一个系统自己都不认的字段；审核发生过这件事由状态迁移 + 指标 + request-id 日志证明）。
  - 新增 `web/RefundReviewController`：`GET /api/refunds/pending`、`POST /api/refunds/{id}/review`，
    走既有 `InternalAuthFilter`。
- `shoppilot-gateway`
  - **唯一必须改的一行**：`agent/ToolDispatcher.java:107-112` 的 `complete` 判据从 `== OK` 扩成
    `== OK || == PENDING_APPROVAL`。**不改这行 `verify-idempotency.ps1` 会红**（受理结果不落幂等 →
    第二次同 token 落到 biz-mock 的 `IDEMPOTENT_REPLAY` 而不经网关 `duplicate` 分支 → `duplicate_submit` 消失）。
  - `AgentStateMachine`：`dispatch`（`:317`）后加分支，`PENDING_APPROVAL` → 发 `tool_result` →
    `ReplayReply.pendingApproval(...)` 受理话术 → 直接收尾（不打第二跳模型）。
  - 新增指标 `shoppilot_refund_pending_total`。

**为什么不新增 SSE 事件 / 不复用现有 `ToolStatus` 值**：SSE 10 事件契约（`PLAN.md:59-75`）不变，
受理态走既有 `tool_result.status`（与 ADR 0044「只加字段不加事件」的纪律一致）；**不新增 `FallbackReason`**
（这不是失败，落工单会污染「降级 9」的口径与 ADR 0009 语义）。复用 `OK` 会让闸门在观测面不可见；
复用 `STATE_NOT_ALLOWED` 语义错且会命中 `AgentStateMachine.failedStep`（`:701-704`）把受理当失败。

**gold 影响（逐类）**：退款 18 条（`eval/cases-part2-action.jsonl:55-72`）判据只查 `tool`/`args`，
`expectStatus` 缺省 → `status_ok` 恒真（`run_tool_eval.py:215-218`）；归属/存在性校验在受理插入**之前**，
`NOT_FOUND` 类仍 `NOT_FOUND`；缺槽位类不派发 → **逐类不退化**。离线 rescore 期望差异集合仍须恰好 4 条。

**需同步更新的既有测试**（被测行为变了，不是改 gold）：`TenantIsolationAndIdempotencyTest.java:118`
的 `"OK"` 期望 → `"PENDING_APPROVAL"`。

**Verify**：`.\mvnw.cmd -B -ntp verify` / `python scripts/check_coverage.py`（⚠️ biz-mock 余量 1.49pp，
`reviewRefund` 必须配测）/ `python scripts/verify_eval_judge.py` / `python scripts/run_tool_eval.py --rescore …` /
`pwsh -NoProfile -File scripts/verify-idempotency.ps1`

**验收项**：① 三态迁移正确、REJECT 按推导回滚（驳回后买家能再申请）；② 重复审核 `STATE_NOT_ALLOWED`；
③ 50 并发同 token 仍 1 行；④ 受理话术确定、不落工单、`fallbackReason` 为空；⑤ **`PlanExecutionTest` 原样通过**
（ADR 0036 未被扰动的机器证据）。

---

### 票 60 — 买家读回（依据 ③）

**要解决什么**：买家被告知「已受理，等待审核」之后，对话里没有出口。他下一轮问「我那退款到哪了」，
现在只能拿到 `OrderStatus.REFUNDING` —— 而它把「待审」与「已放行」**混成同一个值**，等于没答。

**落点**：`tool-api` 的 `OrderView` 加退款审核态字段（待审 / 已放行 / 已驳回）；biz-mock 映射；
受理话术与 `queryOrderDetail` 途径的答案都要**明确写出到账边界**（「已放行，到账由支付渠道处理」）——
即把 `REFUNDED` 那格从「沉默的缺口」变成「写下来的边界」（Q12 裁定：**不**推进状态，只在话术里点明）。

**为什么这条不碰判据**：gold 的断言形状是 `{"tool","args","slotAsk"}`（`cases-part2-action.jsonl` 逐行如此），
**没有任何一条断言答案文本** → 构造上碰不到判据。不新增 intent、不新增工具、不动 `ToolSchemaGenerator`。
**间接风险**：工具结果变了 → dev 模型答案文本可能变 → 需活体确认那几步的文本断言。

**Verify**：`.\mvnw.cmd -B -ntp verify` / `python scripts/verify_eval_judge.py` / 活体 `verify-action-loop.ps1`

---

### 票 61 — 调试台审核面板（依据 ③）

**要解决什么**：一道只存在于 curl 里的闸门，在演示现场等于不存在 —— 那正是审计给 OpsPilot 的评价
（「踪影只能在状态机代码里演示」）。而它给 ShopPilot 的正面判词恰恰是「端上可演示」。

**落点**：`gateway/src/main/resources/static/index.html` 加审核队列面板（列表 + 放行 / 驳回），
风格照现有工单队列；`scripts/verify-console.mjs`（现 36/36）加断言（面板出现在 `PENDING_APPROVAL`
之后、放行后队列清空、驳回后订单状态回到可申请态）。

**Verify**：`node --check` + 活体 `pwsh -NoProfile -File scripts/verify-console.mjs`

---

### 票 62 — 审批闸门活体验收（依据 ③）

新增 `scripts/verify-refund-approval.ps1`（形状照 `verify-idempotency.ps1`）：申请退款 →
断言 `tool_result.status = PENDING_APPROVAL` → `GET /pending` 有该单 → `POST .../review {APPROVE}` →
断言 `PROCESSING` 且买家读回能答出「已放行」；REJECT 独立一条走回滚 + 买家读回能答出「已驳回」。
`run-acceptance.ps1` 矩阵 **add-only** 加一步（不改既有 22 步的任何判据）。

**风险**：矩阵耗时已从 512 s 涨到 805 s，加一步约 +30-60 s；本机资源三条硬限制见 `docs/EVIDENCE.md`。

---

### 票 64 — 检索融合 0 token 录放回归门（依据 ③，**不是**触发已到）

**要解决什么**：给「双路召回 → RRF 融合 → 取 top-5」装确定性回归门。**诚实分界**：它守的是
**排序流水线的确定性与四个常数**，**不覆盖**活体 hit@5 准确率，**因此不闭合 round19 登记第 5 项**——
这一条必须写进 ADR 的 Consequences，收口时不许讲成「活体数字进 CI 了」。

**为什么可做**：`ruleId` 同时是 ES `_id` 与 Qdrant point id，给定两路序 + `k`，融合是**纯函数**
（`HybridRetriever.java:198` `rrf()` + `:208` `accumulate()`，`score += 1.0/(rrfK + rank + 1)`）。

**夹具**：`eval/retrieval-fixture-<date>.json`（append-only 家族）。录制用现成 `/ops/retrieval` 探针
（`OpsController.java:268`，一次返回 `denseTop`/`lexicalTop`/`fusedTop`，**一次调用同时录到输入与输出**）。
pin `llm_mode`/`kb_epoch`/`corpus_sha256`/四个常数。
**查询集必须新增"能造分歧"的 case** —— 原 16 条 `dense` 与 `hybrid` 名次全同，用它做夹具对融合是 no-op。
分歧候选：跨店近重复条款（`return-07-shop-t001-window` vs `return-08-shop-t002-fresh` vs 平台级 `return-01-7day-basic`）、
同主题相邻的 `shipping-01..07`。判据粒度**升到 ruleId 级**（补上「块级 hit@5 从未被测量」这一格：
现判据 `retrieval_compare.py:79` 按**文件名前缀**判，是文档级）。

**断言（层一 · JVM，唯一 owner）**：新增 `RetrievalFusionReplayTest` —— 由 `dense`/`lexical` 重算融合，
断言 top-K 前缀 == `expected_fused_topk`；四个常数从**生产 `application.yml`** 绑定（改 yml 即红）；
`corpus_sha256` 现算相等（改任一 `knowledge/*.md` 即红）。为此把 `rrf()`（`:198`）改**包级可见**（零行为改动）。

**层二 · `scripts/retrieval_gate.py`（纯 stdlib）**：只做夹具 schema、`expected ⊆ dense ∪ lexical`、
sha 与 CI pin 校验。**不在 Python 里复算 RRF** —— 两份实现就是第二份判据，违反「判据只有一份」。

**三道防假绿**：① 重算式断言（只改 `expected` 必红）；② 反证夹具（喂打乱后的输入必须报 mismatch）；
③ 哈希钉（`ci-subset.yml` 加 `env: RETRIEVAL_FIXTURE_SHA256`，与 `check_coverage.py` 的门槛写法同模式）。

**放哪**：CI 现五步 → 插在「套件夹具」后、「覆盖率棘轮」前，成**第 5 步（共 6 步）**。
覆盖率影响**正面**（新测试是 test 代码不进分母，但会执行 `rrf`/`accumulate`）；**不新增任何 main 类**。

**并入 `CODE_MAP.md:91` 已挂的债**：`MarkdownChunkerTest` + `HybridRetriever` 融合用例，与本票同交付
（`rrf` 可见性本来就要动一次），**不单独开「补测运动」票**。

---

### 票 65 — task-level 判据最小形态（依据 ④：触发真实成立）

**要解决什么**：现有四列（意图/工具/参数/槽位）都是**单维请求质量**，没有一列是**端到端终局**。
最刺眼的形态：**四列全绿而任务没办成** —— 模型选对工具、参数也填了，但 biz-mock 返回
`NOT_FOUND`/`STATE_NOT_ALLOWED`（`AgentStateMachine.java:703-705` 的 `failedStep()` 已认这四种终态），
四列依旧全绿，而用户什么也没拿到。

**数据来源恰是 round19 刚交付的两个字段**（这就是触发条件现在成立的原因）：`plan[]`
（`AgentResult.PlanStep(tool, status, latencyMillis, arguments)`）与 `context.ruleIds`。
**判据面只读这三个既有字段，零新增后端代码、零新增探针。**

```
kind_task == "action"   → plan 里 target tool 的 status == OK 且无 fallbackReason
kind_task == "policy"   → context.ruleIds 非空 且无 fallbackReason
kind_task == "escalate" → fallbackReason == USER_REQUESTED
plan 缺失（离线明细无该字段） → None = 未观测
```
**聚合**：单独报 `task_done` 一列，**不给总分、不并入四列的任何百分比**；`unverifiable` 条数与比率一起报。

**为什么不是四列的复读机（两条反例机器证明）**：
- **反例 A（四列绿、任务红）**：期望 `queryLogistics` + `orderNo=90001`，非归属者得 `NOT_FOUND`
  → 四列全绿，`task_done` 假。
- **反例 B（工具列红、任务绿）**：对偶矛盾 `ACT-ORD-09` vs `ACT-LOG-09`，模型选 `queryOrderDetail`
  而旧口径 gold 只认 `queryLogistics` → 工具列红，`task_done` 真。

**落地**：新模块 `scripts/eval_task.py`（`eval_suites.py` 形态）+ `eval/cases-part8-task.jsonl`（add-only）
+ CI 一个 0 token 步。**不并进 `eval_suites.py`** —— 保护现成 24 条夹具所在的文件，且
`round19-spec-…:131` **逐字要求**「新增独立评测模块（`eval_suites.py` 形态）比改现有 gold 安全」。

**不碰 gold 的机器依据**：`verify_eval_judge.py:218/221` 只扫 gold 三文件；`part8` 不在 `GOLD_CASE_FILES`。

---

### 票 66 — token 计量加 `source` 标签（依据 ③）

**要解决什么**：`shoppilot_llm_tokens_total` 的三个来源**计量方法不同却共用一个名字**：
`MockLlmClient` 用 `estimateTokens()` **估算**（`:35/:120`）、`OpenAiCompatibleLlmClient` 用 provider 回报的
`usage.prompt_tokens` **真值**（`:161`）、`OllamaLlmClient` 用模型自报的 `prompt_eval_count` **真值**（`:161`），
三者都喂给 `LlmGateway.java:115` 同一个 counter、**无标签区分**。模式在部署期固定，所以同序列内不混方法 ——
缺的是**口径声明的机器可读性**，不是测量正确性。这一项是本轮最弱的一票，可无损删。

**落点**：给现有计数器加 `source=provider|estimate` 标签（**名字不变 → 指标名计数不变**），照票 47
的 `result` 标签先例；perf/local 档记 `estimate`、dev 云端档记 provider。**红线**：不改 `TokenBudget` 的放行语义。

---

### 票 67 — 告警最小集 + `promtool test rules`（依据 ④：触发本次做实）

**为什么这条能进（本轮最值得讲的一处判断）**：ADR 0024 把「最小告警集」列为非目标，理由是
「本机无 Prometheus 与 Alertmanager 实例，**写了只能证语法、证不了该响时会不会响，正是这条判据要拦的自述**」；
而它说的「这条判据」是同一份 ADR 立的总筛子 ——「一项改造只有能在本仓以 **0 token、不依赖一次性活体读数**的形式
被机器复跑证明，才进本轮范围」。`promtool test rules` 是**纯离线**的告警规则单元测试：喂合成序列、
断言**该响时响、不该响时不响** —— 它证的正是 ADR 0024 说「证不了」的那一句，且满足那条筛子。
**因此 ADR 0024 排除告警的核心理由不再成立，ADR 0030 第 5 条的触发线随之真正达成**
（不是"政策越过"，是"触发已到"）。

**落点**：CI 引入 `promtool test rules` + 3-5 条最小规则（引用真实指标名）+ 触发行为断言。
副作用很好：规则引用真实指标名 → 谁改了指标名，告警测试当场红。
**成本**：约 1 天（含往刻意保持零依赖的 CI 子集里加一个需下载的二进制 —— 这是本票的真实代价，要写进 ADR）。
**红线**：不得声称这些规则在生产会响；它证明的是**规则在该响的合成序列上确实响**。

---

### 票 63 / 68 — 两轮的收口

spec 登记节、`docs/EVIDENCE.md` 新读数、tracker round 表与票索引、`docs/CODE_MAP.md`
（round21：`bizmock/service` 补审核、`agent` 补重放与 `ReplayReply`、Test Map 补新用例；round22：`knowledge` 与 CI 步）、
**指标名重算并按仓库家法写换代指针**、收口审计 `ROUND_FP`/`G6_EXPECT` 换代。

---

## 4. 三处核心设计的裁定

### 4.1 退款闸门＝业务侧受理态（否决"会话内两段式"）

被否方案（首调用返回待审、买家说"确认"才落库）的四条硬理由：
① **直接打红 14 条退款 gold 且无退路**（首轮不派发 `applyRefund` → `tool_ok=false`；gold 是内容级禁面）；
② **闸门错位**（拦的是"买家打没打字确认"，不是"人有没有审"）；
③ **重载已锁定术语**（`CONTEXT.md:69` 的「待办动作」语义是"信息不全、尚未执行"，把"审批待确认"塞进同一槽即改写术语）；
④ 买家永不确认时无审计、无工单、靠 TTL 静默消失。

**采用方案三**：买家侧仍**一轮办完**（受理 + 确定性话术）；资金放行由人工在**异步审核队列**推进。
关键判断：**HITL 不必是对话轮次，可以是状态迁移的门**。

### 4.2 幂等重放：模型之前判（限客户端显式 token + query 哈希相等）

| | 模型之前判 | 模型之后判（现状） |
|---|---|---|
| 依据 | `idempotencyToken` + query 哈希 | 模型这轮是否又发了同 tool 同 args |
| 正确性 | 兑现契约，**与模型行为解耦** | 模型不发即空答案（3/3 复现） |
| 误伤面 | 客户端**复用同一 token 发不同请求**会被误回放 | 无（但是**漏放**不是误伤） |

用 `queryHash` 相等作第二条件后，误伤面收敛到**客户端契约违约**（token 的语义本就是"同一逻辑请求"）；
派生 token（模型前无参数可算）不走预回放，这是**有意保留**的边界。
与票 59 不是同一处 seam，唯一交叠是 `ReplayReply`（可共用，不合并成一张票）。

### 4.3 检索录放门守什么、不守什么

守：融合流水线的确定性与四个常数、语料 sha、夹具完整性。不守：活体 hit@5 准确率。
**→ 不闭合 round19 登记第 5 项**（那要回放含模型的整条问答链，代价高一个量级）。

### 4.4 告警为什么这次算"触发已到"

见票 67。这是本轮唯一一处「ADR 排除理由被技术路径推翻」的判断，也是 ADR 0046 里
最需要写清楚的一段（它与审批闸门的"政策越过"性质完全不同，不许混为一谈）。

---

## 5. 冻结线与依据

- **票 57**：ADR 0031 明文「事实错误、回归、崩溃和现有门禁要求的修复不受上述限制」。**不需要 ADR**。
- **票 58**：兑现既有 `idempotencyToken` 契约语义，不新增功能面、不改判据、不新增 intent/工具/事件；
  同时关闭 round20 登记第 5 项。**建议**按「现有门禁要求的修复」处理；若所有者认为它构成行为面变更，并入 ADR 0046。
- **票 59-62**：需 **ADR 0047**（退款审核语义与契约：受理/放行/驳回 + `ToolStatus.PENDING_APPROVAL` +
  声明式审批策略 + 推导回滚 + 买家读回 + 三个被否方案留档）+ **ADR 0046** 覆盖其政策越过部分。
  ADR 必须诚实写明：审计把审批闸门当 P0「新发现」，但本仓早在 `round19-spec-…:130` 登记为**政策决定**；
  且所选设计**并未满足该触发的第一半**（2 轮预算重新论证）—— 依据是**第二半「资金动作放行成为产品要求」+ 所有者政策**；
  并列出「ADR 0008 / 0036 / 0009 未被推翻」的机器证据（`PlanExecutionTest` 原样通过）。
- **票 64-67**：ADR 0046 的 round22 部分；其中 **65 / 67 记为"触发已到"**、**64 / 66 记为"政策越过"**。
- **不许为让结果变绿改任何判据/阈值/gold/分母**；不许为放行自己收窄门禁（round20 `F1c` 的教训）。

---

## 6. 14 项裁定的记录（问答留档）

| # | 决策点 | 裁定 | 关键理由 |
|---|---|---|---|
| 1 | 「完整的系统」指哪一种 | **v1.0 冻结线内的政策覆盖轮** | ADR 0024/0040 的定位与对外口径不动 |
| 2 | 退款闸门的契约面 | **业务侧受理态 + 新增 `ToolStatus.PENDING_APPROVAL`** | 闸门的**可证明性**就是它的价值；复用现有值会让它在观测面不可见 |
| 3 | 闸门覆盖范围 | **只退款，判定点声明式**（分类挂 `ToolName`，强制执行在 biz-mock） | 判据是「不可逆或涉及资金」而非「是否写库」；先例 `IdempotencyService.isWrite:55` |
| 4 | 审核入口 | **端点 + 调试台审核面板** | 只存在于 curl 的闸门等于不存在（审计给 OpsPilot 的正是这句判词） |
| 5 | 买家怎么知道审核结果 | **扩 `queryOrderDetail` 的 `OrderView`** | 推翻草案原判：gold 只断言 `tool`/`args`/`slotAsk`、**不判答案文本**，故构造上碰不到判据 |
| 6 | 告警规则 | **做实触发线（promtool + 最小规则）** | ADR 0024 的排除理由被 promtool 推翻；这是"触发已到"而非"政策越过" |
| 7 | 活体预算 | **A 全套**（录夹具 + 审批验收 + task 批）；B 为预设回退 | 没跑成的一律按未达成登记、不许摘红 |
| 8 | 审核驳回的回滚与留痕 | **不加迁移，回滚用推导** | 先前状态可由 `paidAt`/`shippedAt`/`deliveredAt` 无损推导；且不记系统自己都不认的"审核人" |
| 9 | 退款审核队列的命名 | **新增术语 + 点名与既有「复核队列」的区别** | 两者都会被读成"待人工队列"，而 `CONTEXT.md:163` 已定「两张表、两件事」 |
| 10 | 申请号是否进事件流 | **不加字段，从审核队列反查** | 不造第二本账（ADR 0030 否决过"网关侧加工单审计副本"） |
| 11 | ADR 结构 | **两份**：`0046` 轮范围与逐票依据 + `0047` 退款审核契约 | 照 round17 先例（`0033` 开轮 + `0034`-`0040` 逐特性） |
| 12 | `REFUNDED` 终态 | **登记保留 + 到账边界写进话术** | 它的触发原文要求"真实诉求"，而本仓无真实支付通道 —— 自造 actor 等于制造需求 |
| 13 | token 计量 | **加 `source` 标签，不加新指标名** | 名字不变 → 计数不变；照票 47 的 `result` 标签先例 |
| 14 | 轮次形状 | **拆两轮**：round21 先交闭环，round22 机器背书 | 两半文件面几乎不重叠；仓库近几轮是 3-6 票的节奏；前一半含审计 P0 |

---

## 7. 明确不做（登记不执行，各挂触发条件）

| 项 | 理由 / 触发条件 |
|---|---|
| **反馈 `trails` 外置 Redis** | 单实例演示无收益；多实例化是触发线（ADR 0040 已有会话总线的同型触发） |
| **`REFUNDED` 终态推进** | 沿用 round19 登记第 1 项。本轮只把「到账不在承诺范围」写进买家话术（Q12） |
| **输入侧上下文裁剪** | round19 登记第 7 项；动它会改 Prompt → 可能改 180 条 gold 读数。本轮只把 `estimatedPromptTokens` 当观测攒证据 |
| **知识反向沉淀自动咬合** | round19 登记第 8 项；`markReviewed` 是队列终点、无代码连线 |
| **批量向量化** | 对 90 块无收益（秒级 vs 亚秒）。触发 = 语料块数 ≥ 500 或入库时长成为瓶颈 |
| **L2 阈值 0.95 / rerank / 纪元物理 purge** | **禁区**：0.95 召回 0 是保守正确（80% 拦截率由 L1+SingleFlight 承担，`threshold-calibration.md:22` 明写）；rerank 缺的是**能造分歧的证据集**（票 64 顺带补）；`bump()` 逻辑闭合已成立 |
| **`feedback` / `plansteps` 两条活体红** | **判据一字不改**（ADR 0043 明令禁止改窄判据适配实现） |
| **CI 覆盖活体准确率数字** | round19 登记第 5 项；票 64 只覆盖排序确定性，**不闭合它** |
| **拦截率 74% vs 80% 裁决** | ADR 0030 第 4 条；三个选项一个都不选 |

---

## 8. 两份分域计划的冲突与统一记录

| 冲突 | 两侧说法 | 统一裁决 |
|---|---|---|
| **票号撞车** | 两份都用 57-60 | 统一重编；并因每轮各需一张收口票而上移一位（见 §2 脚注） |
| **指标名计数耦合** | 双方各自新增指标都会改计数 | 票 57 只修正为**当前值 52**；票 66 加标签不加名（计数不变）；票 59 会 +1（→53），由**票 63 收口时重算并写换代指针** |
| **`README.md:852` 改不改** | RAG 专家建议改成 52 | **不改**。那是 `commit=8c4b616` 的历史落点读数，按 ADR 0021 必须原样 + 换代指针（主控推翻专家建议，Q 之外单独记） |
| **谁需要 ADR** | Architect 提 1 份；RAG 提 2 份 | 合并为 **2 份**（`0046` 轮范围 + `0047` 退款契约），照 round17 先例 |
| **token 计数会不会 +1** | RAG 自己既说「52→53」又说「旧名不动」 | 按**旧名不动、只加标签** → 计数不变 |
| **"不做"清单重合** | 两份都有（告警/输入裁剪/知识沉淀/trails） | 合并进 §7；告警与退款读回**本轮移入 scope**（Q6 / Q5） |

---

## 9. 每票 Handoff 必须回答的三个现场追问

1. **为什么审批闸门做在业务侧的状态迁移上，而不是做在会话轮次里？**
   （答：闸门拦的是"钱动没动"不是"买家打没打字"；会话轮次形态会打红 14 条 gold 且重载「待办动作」术语）
2. **为什么给 `ToolStatus` 新增一个值，而不是复用 `OK` / `STATE_NOT_ALLOWED`？**
   （答：复用会让闸门在观测面不可见 / 语义错 / 会误导 `failedStep` 把受理当失败）
3. **告警这次为什么算"触发已到"，而审批闸门算"政策越过"？**
   （答：`promtool test rules` 证的正是 ADR 0024 说"证不了"的「该响时会不会响」，故排除理由不再成立；
   审批闸门的第一半触发（2 轮预算重新论证）并未满足，依据是第二半 + 所有者政策 —— 两者性质不同，不许混为一谈）
