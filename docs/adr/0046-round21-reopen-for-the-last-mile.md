# round21 重开：闭环的最后一公里（指标数事实修正 / 幂等重放 / 退款审批闸门 / 买家读回 / 调试台面板）

Context: 触发物是一份外部审计 `D:\WorkBuddyData\Agent项目七维架构审计-v2-分类修正-20260927.md`（§4.2 逐项目取证、§7 改进优先级）。该文档**属参考不属裁决**，因此它对 ShopPilot 的四条指控 + 一处数字纠正逐条对仓重核过，结论是**只有一条是全新发现**：

- **高风险动作无人工确认**：成立。`BizMockService.java:165-218` 的 `applyRefund` 在槽位齐备、归属命中、订单可退、金额不超实付之后，于**一个事务**里插 `Refund(status="PROCESSING")`（`:204`）并把 `Order` 置为 `REFUNDING`（`:206`），`:202-209` 一次提交；全链唯一闸门是 `ToolDispatcher.executeWrite` 的幂等两态。三模块 `src` 下 `approval|confirm` **0 命中**。**但这不是新发现**：`round19-spec-trust-observability.md:130` 登记节第 3 项已把它登记为**政策决定**，登记的触发条件写明是「ADR 0008 的 2 轮时延预算被**重新论证**」**且**「资金动作放行成为产品要求」。
- **零告警规则**：成立，但触发线（ADR 0030 第 5 条：本仓出现可机器寻址的 Prometheus/Alertmanager 形态）不在本范围内 —— 见「非目标」。
- **「记忆仅会话级、写入同步、多实例不可用」**：**说得不精确**。会话**已是 Redis 外置**（`SessionStore.java:115-119`，键含 tenant 与 customer），限流（Redisson）与 token 预算（`llm/TokenBudget`）同为 Redis 后端 —— 「多实例不可用」对这三者不成立。真正进程内的只有 `feedback/FeedbackService.java:50` 的 `trails`（隐式反馈重复窗口）。
- **180 条活体评测不进 CI**：成立，`round19-spec-…:133` 登记第 5 项早已登记。
- **README 写 41 个指标、实测 52**：**成立，且比审计说的更糟**。按 README 自述数法（三模块 `src/main` 去重）实算 = 52；而 `round18-spec-scoring-dimension-completeness.md:14` 在 round18 时点就记「**51 个指标名 / 59 个注册点**」→ README 的 41 **从 round18 起就落后**，round19 又加了 `shoppilot_embedding_latency_seconds`。这是**纯事实错误**。

**审计没看出来的那一格**：它给 D4（RAG）/D6（评测）都判 ✅ —— 架构层成立 —— 但它没问「**谁在机器上守着这条链**」。实测 CI 里**没有任何一条断言在守检索排序**（`eval_suites.py:130-235` 的 24 条夹具按 `kind ∈ {emotion, channel, plan, style}` 分发，四个 kind 都不读 `ruleIds`/`citations`/RRF 序），而唯一那份 RAG 质量证据 `docs/retrieval-comparison.md` 的 16 条查询 `dense` 与 `hybrid` **名次完全相同**（`:30-31`、`:61`）→ 对 RRF 回归**判别力为零**。这一格不在本 ADR 范围内，登记给 round21 之后的轮次（见 Consequences）。

至此，这个系统断在两处**最后一格**：**闭环最后一公里**（钱动了没人看过；客户端同 token 重试拿空答案）与**机器背书最后一格**（最强的子系统进 CI 覆盖为 0）。本轮只做**前一格**。

依据必须逐条分清，不许图省事统一贴一个标签（ADR 0045 立的写法）：**票 57** 是纯事实错误，走 ADR 0031 第 10 行「事实错误、回归、崩溃和现有门禁要求的修复不受上述限制」，**不需要任何政策覆盖**；**票 58** 兑现既有 `idempotencyToken` 语义、不新增功能面、不新增 intent/工具/事件，按同一行处置；**票 59-62** 是新的功能与契约行为，**必须**记为本轮重开的核心不是该条件的触发，而是**项目所有者在知情政策下做出的显式覆盖决策**——与 ADR 0033、0041、0044、0045 同形，**不许伪装成面试反馈触发，也不许写成「审计发现的 bug 必须修」**。

Decision: round21 范围锁定 **票 57-63**，一条一票。

| 票 | 内容 | 依据 | 依赖 |
|---|---|---|---|
| 57 | 指标名计数换代（41 → 52）+ 两份活体报告加 provenance 表头 | ADR 0031:10（纯事实错误） | 无 |
| 58 | 幂等重放时机前移：请求级回放索引，判重放不再取决于模型是否重发工具调用 | ADR 0031:10（兑现既有契约语义） | 无 |
| 59 | 退款审批闸门：业务侧受理态 + 异步人工审核 + `ToolStatus.PENDING_APPROVAL` | **所有者政策覆盖**（新功能行为） | ADR 0047；建议在 58 后 |
| 60 | 买家读回：`OrderView` 承载退款审核态，并把「到账」写成明确边界 | **所有者政策覆盖** | 59 |
| 61 | 调试台审核面板（列表 / 放行 / 驳回） | **所有者政策覆盖** | 59 |
| 62 | 审批闸门活体验收（`verify-refund-approval.ps1` + 矩阵 add-only 加步） | **所有者政策覆盖** | 59-61 |
| 63 | round21 收口（spec 登记节 / EVIDENCE / tracker / CODE_MAP / 审计常数换代 / 指标名重算换代） | — | 57-62 |

具体口径：

- **退款闸门取「业务侧受理态」，明确否决「会话内两段式」（买家说"确认"才落库）。** 四条硬理由：① 两段式的首轮**不会派发 `applyRefund`**，`TOOL_EXEC` trace 里没有该工具名 → `run_tool_eval.py:154-161` 的 `tool_ok=false` → 14 条退款成功用例全红，而 gold 是**内容级禁面**；② 闸门错位——它拦的是"买家有没有打字确认"，不是"人有没有审"，而本轮的指控是**资金动作无人工确认**；③ 它会重载 `CONTEXT.md:69` 已锁定的「待办动作」语义（那是"信息不全、尚未执行"，`SessionStore` 的 `pendingTool/pendingArgs` 与 `resumePending` 正服务槽位补齐）；④ 买家永不确认时无审计、无工单、靠 TTL 静默消失。**核心判断一句话：HITL 不必是对话轮次，可以是状态迁移的门** —— 人工等待落在异步审核队列，不占对话轮次。
- **本 ADR 的所有权覆盖只用到登记项触发条件的第二半，必须逐字说明。** `round19-spec-…:130` 的触发是「ADR 0008 的 2 轮时延预算被**重新论证**」**且**「资金动作放行成为产品要求」。所选设计**刻意不去扰动 ADR 0008**（人工等待不在对话轮次里），因此**并未满足第一半**；依据是第二半（所有者把资金动作放行定为产品要求）+ 本轮的所有者政策覆盖。**不许**把这条写成「触发已到」。
- **ADR 0008 / 0036 / 0009 一字不动，且要有机器证据。** 2 轮工具预算、Plan ≤2 步与前步失败即中止、转人工落可查工单，全部保持原样；收口时以 `PlanExecutionTest` 原样通过作为未扰动的证据。
- **闸门覆盖范围只到退款，判定点声明式。** 判据是「该动作是否**不可逆或涉及资金**」，不是「是否写库」——所以 `modifyDeliveryAddress`（有版本、可再改回）不入闸门。分类挂 `shoppilot-tool-api` 的 `ToolName`（与 `intent()` 同层），强制执行在 biz-mock；先例是 `IdempotencyService.isWrite(ToolName):55`。这样下次加闸门是加一行分类，不是重设计。
- **闸门必须在契约面上可见：新增 `ToolStatus.PENDING_APPROVAL`。** 复用 `OK` 会让闸门在 `tool_result` 与 trace 里**不可见**（「已受理」与「已放行」同形，审计时无法证明门存在）；复用 `STATE_NOT_ALLOWED` 语义错（这是**受理**不是**拒绝**）且会命中 `AgentStateMachine.failedStep`（`:701-704`）被当成「前步失败」；复用 `IDEMPOTENT_REPLAY` 语义相反。已核新增枚举值安全：全仓无 `ToolStatus.values()` 遍历、无穷尽 switch（只用 `==` 比较）。**不新增 SSE 事件**（受理态走既有 `tool_result.status`，与 ADR 0044「只加字段不加事件」同形）、**不新增 `FallbackReason`**（受理不是失败，落工单会污染「降级 9」的口径与 ADR 0009 语义）。
- **`applyRefund` 受理后保留 `Order→REFUNDING`，不加 `prior_status` 列、不做 V3 迁移。** 订单表已有 `paidAt`/`shippedAt`/`deliveredAt`（`Order.java:82-89`），而 `refundable()` 只允许 `PAID|SHIPPED|DELIVERED`（`OrderStatus`）—— 先前状态可**无损推导**，驳回时据此回滚。也不落「审核人」：`CONTEXT.md:93` 已定「运维凭证证明的是『允许你动』，不证明身份」，记下来会是一个系统自己都不认的字段。审核发生过这件事由**状态迁移 + 指标 + request-id 日志**证明，那正是票 59 新增 `ToolStatus` 值换来的可证明性。
- **买家读回走既有 `queryOrderDetail`，不新增 intent 与工具。** gold 的断言形状是 `{"tool","args","slotAsk"}`（`eval/cases-part2-action.jsonl` 逐行如此），**没有任何一条断言答案文本**，所以在 `OrderView` 上加退款审核态**构造上碰不到判据**；`CONTEXT.md:102` 明列「新增意图需同步四处」，不走那条路。同时把「到账不在承诺范围」写进话术——让 `REFUNDED` 那格从「沉默的缺口」变成「写下来的边界」。
- **不需要申请号进事件流。** 机器可断言「受理了」的是 `tool_result.status = PENDING_APPROVAL`，而申请号的权威来源是审核队列端点（`GET /api/refunds/pending`，票 59 本来就要建）。同一事实两个出口是本仓明确否决过的形状（ADR 0030 的 Considered Options 否决过「网关侧加工单审计副本」：「副本与真单之间没有一致性协议，多出一个看似权威的第三本账」）。
- **`CONTEXT.md` 只增词、不改既有条目，且新词必须点名它与既有「复核队列」的区别。** `CONTEXT.md:163` 的**复核队列**是反馈点踩那条队列（`review_status: PENDING → REVIEWED`）；本轮引入的是**退款审核**队列 —— 两者都会被人读成"待人工处理的队列"，而本仓已立「工单与复核队列是两张表、两件事」的纪律。顺带钉住「受理 vs 放行」这组（`PENDING_APPROVAL` 需要中文对应词）。
- **幂等重放前移到模型之前，但只对客户端显式 token + query 哈希相等生效。** 这才是 `idempotencyToken` 存在的意义（"客户端重试不该重复执行、应回放"）；现状把这条承诺挂在「模型肯不肯再发一次工具调用」上，是设计缺陷而不是模型缺陷（round20 登记第 5 项，探针 3/3 复现）。用 `queryHash` 相等作第二条件后，「用户换了要求却复用旧 token」会放行给正常链路，误伤面收敛到**客户端契约违约**；post-model 幂等保留为第二道网；派生 token（模型前无参数可算）不走预回放，这是**有意保留**的边界。回放点落在 `AgentStateMachine` 而不是 `ChatController` —— 后者的同步/流式两条路径已有重复决策（`docs/CODE_MAP.md:89` 登记的债），放状态机一处即两条通路同时生效，不加重该债。
- **契约改动只允许一处，且不可省。** `ToolDispatcher.executeWrite`（`:107-112`）把 `PENDING_APPROVAL` 也视为可 `complete`；不改这行，受理结果不落幂等，第二次同 token 会落到 biz-mock 的 `IDEMPOTENT_REPLAY` 而不经网关的 `duplicate` 分支，`duplicate_submit` 事件消失、`verify-idempotency.ps1` 转红。
- **覆盖率是硬约束。** gateway LINE 门槛 `54.0`（实测 58.52%，余量 4.52pp）、**biz-mock 门槛 `76.0`（实测 77.49%，余量仅 1.49pp）**、tool-api `40.0`。票 59 新增的 `reviewRefund` 与审核控制器**一律配 JUnit 用例**，否则 `scripts/check_coverage.py` 直接红 —— 这是本轮最容易踩的坑。
- **票 57 只改当前口径的两处，历史落点那处保持 41 + 加换代指针。** `README.md:199` 与 `docs/interview-qa.md:574` 写的 41 是当前口径（实为 52）→ 改数，**数法描述一字不改**；而 `README.md` 里 round14 落点段（`commit=8c4b616`）那句「指标名按三模块 `src/main` 去重后为 41」是**历史读数**，按 ADR 0021「两套读数并列」与仓库「旧值原样供着 + 换代指针」的家法**不许改成 52**。两份活体报告（`retrieval-comparison.md` / `threshold-calibration.md`）只加 provenance 表头，**不重生成正文**（重生成会改读数，属证据口径变更）。

非目标（本 ADR 不授权；票 64-68 已在 round21 spec 中列出并**预先分好依据**，但**各自需要开轮决策**，不在本 ADR 范围内）：

- **检索融合的 0 token 录放回归门**：其依据是**政策越过**，不是触发已到。`round19-spec-…:133` 的触发原文是「出现可离线复跑的录制/回放路径」，而**建这条路径就是该票本身** —— 引自己当依据是循环论证，不许讲成「登记第 5 项的触发已成立」。
- **task-level 判据**：依据是**触发本次成立**（登记第 4 项的触发原文就是「有人提出一条能机器判定『任务是否办成』且现有门禁承载得了的判据」）。
- **告警最小集 + `promtool test rules`**：依据是**触发本次做实**。ADR 0024 把「最小告警集」列为非目标，理由是「本机无 Prometheus 与 Alertmanager 实例，**写了只能证语法、证不了该响时会不会响，正是这条判据要拦的自述**」；而它说的「这条判据」是同一份 ADR 立的总筛子 ——「一项改造只有能在本仓以 **0 token、不依赖一次性活体读数**的形式被机器复跑证明，才进本轮范围」。`promtool test rules` 是**纯离线**的告警规则单元测试：喂合成序列、断言**该响时响、不该响时不响**，证的正是 ADR 0024 说「证不了」的那一句，且满足那条筛子。**因此 ADR 0024 排除告警的核心理由不再成立，ADR 0030 第 5 条的触发线随之真正达成** —— 是「触发已到」，不是「政策越过」。它与票 59-62 的审批闸门（第二半 + 所有者政策）性质完全不同，**不许混为一谈**。
- **反馈 `trails` 外置 Redis / `REFUNDED` 终态推进 / 输入侧上下文裁剪 / 知识反向沉淀自动咬合 / 批量向量化**：继续登记不执行，触发条件不变（见 round21 spec §7）。`REFUNDED` 本轮只把「到账不在承诺范围」写进话术。
- **L2 阈值 0.95 / rerank / 知识纪元的物理 purge**：禁区，一个字都不改。
- **`feedback` / `plansteps` 两条活体红、拦截率 74% vs 80% 裁决**：判据一字不改，按登记保留。

Considered Options:

- **本轮不做、只登记**：否决。放弃的恰好是审计唯一认出来的真缺口（资金动作无闸门）与一处**真实契约缺口**（客户端重试拿空答案）—— 后者甚至不需要政策覆盖就能修，把它一起登记等于用"冻结"掩盖一个已承诺语义未兑现的事实。
- **退款闸门取「会话内两段式」**：否决。四条理由见上；其中第一条（打红 14 条 gold 且无退路）单独就足以否决。
- **退款闸门做在网关侧、biz-mock 不感知**：否决。业务规则必须在状态真相的所有者处强制（`issues/03` 与 `docs/CODE_MAP.md` 的 `bizmock/service` 行本来就是这么划分的）；网关侧做闸门会让"受理"这个状态只存在于网关的内存与会话里。
- **闸门复用现有 `ToolStatus` 值**：否决。见上「闸门必须在契约面上可见」。
- **给 `Refund` 加 `reviewed_at` / `review_note` / `prior_status`（V3 迁移）**：否决。回滚不需要新列（可推导），而留痕的主要价值落在"记审核人"上，那是本仓明确不能证实的字段。
- **申请号进 `done` 帧或 `tool_result`**：否决。审核队列端点已是同一事实的权威出口，再加一个是造第二本账。
- **买家读回新增一个专门的退款进度工具**：否决。要动 `Intent`/`ToolName` 两个枚举、工具集数组、`ToolSchemaGenerator` 与意图清单四处（`CONTEXT.md:102` 明列），碰 gold 的面最大，而扩 `OrderView` 构造上碰不到判据。
- **票 58 的重放放在模型之后（保持现状）**：否决。那等于承认「幂等回放」这条承诺可以因为模型不发工具调用而失效，而 round20 已 3/3 复现它确实会失效。
- **把票 59-62 也写成 ADR 0031:10 的事实性修正**：否决。它们新增功能与契约行为，记成豁免就是**把政策覆盖伪装成事实性修正** —— ADR 0033 起明令禁止的那件事。

Consequences:

- 冻结线对 round21 之后的轮次继续有效；本轮结束后回到 ADR 0031 机制，**不自动续期**。七张票独立可交付，中途冻结任意时刻项目仍自洽（票 57/58 甚至不需要本 ADR）。
- **`ToolStatus` 是跨模块契约案，新增一个值会在三处留下痕迹**：`shoppilot-tool-api`（枚举本体）、`shoppilot-biz-mock`（返回该状态）、`shoppilot-gateway`（`ToolDispatcher.executeWrite` 的 complete 判据与受理分支）。收口时 `docs/CODE_MAP.md` 的跨模块工具 DTO 行要同步。
- **票 59 会改变一条既有 JVM 用例的被测行为**：`TenantIsolationAndIdempotencyTest.java:118` 期望的 `"OK"` 要改成 `"PENDING_APPROVAL"`。这是**被测行为变了**，不是改判据/阈值/gold —— 两者必须在 Handoff 里分开写清，免得被读成后者。
- **gold 180 条逐类不退化**（退款 18 条的 `expectStatus` 缺省 → `status_ok` 恒真；`NOT_FOUND` 类在校验前返回；缺槽位类不派发），离线 rescore 的期望差异集合仍须恰好 4 条（`ACT-ORD-09/11/16/17`）。
- **票 58 会改变一条幂等承诺的兑现路径**：从"依赖模型重发工具"变成"请求级确定性回放"。`verify-idempotency.ps1` 的两次调用是「不同 conversation id、同 query、同 token」→ 按脚本逻辑推断该步**更稳**，但**必须活体实跑确认**，不得按推断声称已绿。
- **本机资源已紧**（主机内存剩余、TIME_WAIT 占满动态端口、多套项目容器争显存，见 `docs/EVIDENCE.md`），票 60/62 都要起栈。活体预算按所有者裁定取「全套」，但**没跑成的一律按未达成登记、不许摘红**（round20 的家法）。
- **round21 收口必须把指标名重算一次并写换代指针**：票 57 修到 52、票 59 加 `shoppilot_refund_pending_total` 后为 53，与 round14/round18 的换代处理同形。**数法（现场 grep 现算）不变。**
