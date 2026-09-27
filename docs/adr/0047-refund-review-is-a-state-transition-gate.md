# 退款审核是资金放行的状态迁移门，不是对话轮次

Context: 本 ADR 由 round21（ADR 0046）的票 59-62 触发。要解决的问题是：`BizMockService.applyRefund`（`:165-218`）在槽位齐备、归属命中、订单可退、金额不超实付之后，于**一个事务**里插 `Refund(status="PROCESSING")`（`:204`）并把 `Order` 置为 `REFUNDING`（`:206`）—— **受理与资金放行是同一个动作，中间没有第二双眼睛**。全仓对该动作的唯一闸门是 `ToolDispatcher.executeWrite` 的幂等两态，而幂等管的是"重复提交"，不是"该不该放行"。

设计空间被两条已锁定的决策夹住：

- **ADR 0008** 只给工具循环 2 轮预算，**ADR 0036** 规定 Plan ≤2 步且前步失败即中止。任何"在对话里插入一次人工等待"的形态都会直接冲击前者。
- **`CONTEXT.md:69`** 已把「待办动作」钉死为"信息不全、尚未执行"，`SessionStore` 的 `pendingTool/pendingArgs` 与 `AgentStateMachine.resumePending` 正服务槽位补齐。把"审批待确认"塞进同一个槽就是改写已锁定术语。

两条约束合起来给出的方向是：**闸门不该落在对话轮次里，而该落在状态迁移上**。人工等待发生在**异步审核队列**中，买家侧仍是**一轮办完**（受理 + 确定性话术）。由此得出的状态机是：

```
买家申请 ──► Refund(PENDING_REVIEW)   订单 → REFUNDING（受理即冻结）
                    │
        ┌───────────┴───────────┐
   人工放行                    人工驳回
        │                        │
Refund(PROCESSING)      Refund(REJECTED)
（资金放行，订单保持     订单按 paidAt/shippedAt/deliveredAt
  REFUNDING）            推导回到先前状态 ──► 买家可再申请
```

**受理即冻结订单**这一条承担两件事：① 语义上表示"这一单正在处理中，不要再改"；② 机制上**它就是"同一订单只允许一笔在办退款"的守卫** —— `OrderStatus.refundable()` 在 `REFUNDING` 时返回 false，所以二次申请会被既有的 `STATE_NOT_ALLOWED` 分支挡住，不需要新增唯一性检查。

Decision:

**一、受理与放行拆成两态，资金放行必须由人触发。** `applyRefund` 落 `Refund(status="PENDING_REVIEW")`，返回 `ToolStatus.PENDING_APPROVAL`；`PENDING_REVIEW → PROCESSING` 是**唯一不可逆迁移**，只能由审核动作推进；`PENDING_REVIEW → REJECTED` 可逆（订单回滚，买家可再申请）。

**二、闸门在契约面上必须可见：`ToolStatus` 新增 `PENDING_APPROVAL`。** 不复用任何现有值 —— 复用 `OK` 会让"已受理"与"已放行"在 `tool_result` 与 trace 里同形（审计时无法证明门存在）；复用 `STATE_NOT_ALLOWED` 语义错（这是**受理**不是**拒绝**）且会命中 `AgentStateMachine.failedStep`（`:701-704`）把受理当成"前步失败"；复用 `IDEMPOTENT_REPLAY` 语义相反。已核新增枚举值不引发编译期涟漪：全仓**无** `ToolStatus.values()` 遍历、**无**穷尽 switch，只用 `==` 比较，且 `BizMockClient.interpret` 的 `valueOf` 直接接受新串。`ToolResponse.succeeded()`（`:28-30`）**保持** `OK || IDEMPOTENT_REPLAY` 不变（避免涟漪），判"已受理"用 `status == PENDING_APPROVAL`。

**三、审批策略是声明式的，挂 `shoppilot-tool-api` 的 `ToolName`（与 `intent()` 同层）。** 判据是「该动作是否**不可逆或涉及资金**」，不是「是否写库」——所以 `modifyDeliveryAddress`（写 `address_history`、有版本、可再改回）**不入闸门**。本轮只有 `APPLY_REFUND` 返回 true。这样下次给别的动作加闸门是**加一行分类**，不是重设计；先例是 `IdempotencyService.isWrite(ToolName):55`。强制执行在 biz-mock（状态真相的所有者）。

**四、回滚用推导，不加列、不做迁移。** 订单表已有 `paidAt`/`shippedAt`/`deliveredAt`（`Order.java:82-89`），而 `refundable()` 只允许 `PAID|SHIPPED|DELIVERED`，故先前状态可**无损推导**（`deliveredAt → DELIVERED`、`shippedAt → SHIPPED`、`paidAt → PAID`）。**不落「审核人」**：`CONTEXT.md:93` 已定「运维凭证证明的是『允许你动』，不证明身份」——记下来会是一个系统自己都不认的字段；也不落 `review_note`（驳回理由在 v1 不承诺给买家，而给审核者自己看的理由没有消费者）。审核发生过这件事由**状态迁移 + 指标（`shoppilot_refund_pending_total`）+ request-id 日志**证明。

**五、审核入口是运维端点 + 调试台面板，两个面都不能省。** `GET /api/refunds/pending`（审核队列）、`POST /api/refunds/{id}/review`（`{decision, note}`），走既有 `InternalAuthFilter`；调试台（`gateway/src/main/resources/static/index.html`）加审核队列面板（列表 + 放行 / 驳回），风格照现有工单队列。**只做端点不做面板的形态被否决**：一道只存在于 curl 里的闸门在演示现场等于不存在 —— 那恰是外部审计给另一个项目的判词（"踪影只能在状态机代码里演示"），而它给本项目的正面判词正是"端上可演示"。

**六、买家读回走既有 `queryOrderDetail`，把审核态带进 `OrderView`；到账边界写进话术。** 不新增 intent、不新增工具、不动 `ToolSchemaGenerator` —— gold 的断言形状是 `{"tool","args","slotAsk"}`（`eval/cases-part2-action.jsonl` 逐行如此），**没有任何一条断言答案文本**，所以加字段**构造上碰不到判据**。`REFUNDED` 终态**不推进**（沿用 round19 登记第 1 项），但话术要明确写出边界：「已放行，到账由支付渠道处理」。

**七、申请号的唯一权威出口是审核队列端点，不进 SSE 事件流。** 机器可断言"受理了"的是 `tool_result.status = PENDING_APPROVAL`；申请号从 `GET /api/refunds/pending` 取（可按订单号查）。同一事实开两个出口是本仓否决过的形状（ADR 0030 的 Considered Options：「副本与真单之间没有一致性协议，多出一个看似权威的第三本账」）。`done` 帧与 `tool_result` 都**不加字段**。

**八、术语命名。** `CONTEXT.md` **只增词、不改既有条目**：新增**退款审核 (Refund Review)** 与**待审核 (Pending Review)**，并在定义里**点名它与既有「复核队列」的区别** —— `CONTEXT.md:163` 的**复核队列**是反馈点踩那条队列（`review_status: PENDING → REVIEWED`），对象是被点踩的答案；退款审核的对象是退款申请。两者都会被人读成"待人工处理的队列"，而本仓已立「工单与复核队列是两张表、两件事」的纪律。同时钉住**受理 vs 放行**这组对照。

**九、`ToolDispatcher.executeWrite` 的 complete 判据扩展是本次唯一不可省的网关改动。** 判据从 `== OK` 扩成 `== OK || == PENDING_APPROVAL`（`ToolDispatcher.java:107-112`），否则受理结果不落幂等，第二次同 token 会落到 biz-mock 的 `IDEMPOTENT_REPLAY` 而不经网关的 `duplicate` 分支 → `duplicate_submit` 事件消失 → `verify-idempotency.ps1` 转红。

具体口径：

- **不新增 SSE 事件、不动 10 状态枚举、不动 `FallbackReason`。** 受理态走既有 `tool_result.status`（与 ADR 0044「只加字段不加事件」同形）；受理**不是失败**，落工单会污染「降级 9」的口径与 ADR 0009 的语义（"转人工 = 落可查工单"）。
- **`expectedWriteDone` 沿用现有逻辑**：只要 `dispatch.tool() == expectedWrite` 即为真，所以"受理"视为"办过"，不会触发 `TOOL_ROUNDS_EXHAUSTED` 纠偏。
- **受理话术是确定性的，不打第二跳模型。** 由新增的 `ReplayReply.pendingApproval(tool, json)` 渲染（与票 58 的 `render(tool, json)` 同一个 helper —— 可共用，但两票独立交付）。
- **票 58 先于票 59**：58 的请求级预回放会把 `PENDING_APPROVAL` 的结果当可回放结果渲染，所以"已受理，待审核"在重试时不会变成"已退款"。两票不是同一处 seam（58 在网关的 `IdempotencyService` + 状态机入口，59 在 biz-mock 状态机 + 新端点），只共用那个渲染器。
- **`TenantIsolationAndIdempotencyTest.java:118` 的 `"OK"` 期望要改成 `"PENDING_APPROVAL"`。** 这是**被测行为变了**，不是改判据/阈值/gold —— Handoff 里两者必须分开写，免得被读成后者。
- **覆盖率**：biz-mock LINE 门槛 `76.0`，实测 `77.49%`，**余量仅 1.49pp** —— `reviewRefund` 与审核控制器一律配 JUnit 用例，否则 `scripts/check_coverage.py` 直接红。

Considered Options:

- **会话内两段式（首调用返回待审、买家说"确认"才落库）**：否决，四条硬理由。① 首轮**不会派发 `applyRefund`**，`TOOL_EXEC` trace 里没有该工具名 → `run_tool_eval.py:154-161` 的 `tool_ok=false` → 14 条退款成功用例全红，而 gold 是**内容级禁面**、无法为它让路；② **闸门错位**：它拦的是"买家有没有打字确认"，不是"人有没有审"，而本轮的问题是资金动作无人工确认；③ **重载已锁定术语**（`CONTEXT.md:69` 的「待办动作」是"信息不全、尚未执行"）；④ 买家永不确认时无审计、无工单、靠 TTL 静默消失。
- **闸门做在网关侧，biz-mock 不感知**：否决。业务规则必须在状态真相的所有者处强制（`issues/03` 与 `docs/CODE_MAP.md` 的 `bizmock/service` 行就是这么划分的）；网关侧做闸门会让"受理"这个状态只活在网关的内存与会话里，而退款记录是 biz-mock 的。
- **复用 `ToolStatus` 的现有值**：否决。见 Decision 第二条。
- **用 `FallbackReason` 承载"待审核"**：否决。它不是失败；落工单会污染「降级 = 枚举 − 1 = 9」的口径，也会与 ADR 0009「转人工 = 落可查工单」的语义撞车。
- **`Refund` 加 `reviewed_at` / `review_note` / `prior_status`（V3 迁移）**：否决。回滚不需要新列（可推导）；留痕的主要价值落在"记审核人"上，而那是本仓明确不能证实的字段（`CONTEXT.md:93`）。
- **申请号进 `done` 帧或 `tool_result`（照 ADR 0044 票 48/49 的"只加字段"先例）**：否决。那条先例成立的前提是"观测需求没有别的出口"，而这里审核队列端点已是同一事实的权威出口 —— 再加一个是造第二本账。
- **新增一个专门的退款进度读工具**：否决。要动 `Intent`/`ToolName` 两个枚举、工具集数组、`ToolSchemaGenerator` 与意图清单四处（`CONTEXT.md:102` 明列"新增意图需同步这四处"），碰 gold 的面最大；而扩 `OrderView` 构造上碰不到判据。
- **推进 `Refund`/`Order` 到 `REFUNDED` 终态**：否决。到账的权威在支付通道，本仓没有；为它加一个"模拟支付通道"的定时 actor 等于**制造需求去点亮自己的触发条件**（round19 登记第 1 项的触发原文是"出现需要区分受理与到账的**真实诉求**"）。本轮只把边界写进话术。
- **「进闸门」的范围覆盖所有写动作（含改地址）**：否决。改地址写 `address_history`、有版本、可再改回，不是不可逆动作；给它加人工等待会把一个日常动作拉慢，与 ADR 0008「有界」的调性不搭。

Consequences:

- **`ToolStatus` 是跨模块契约**（`shoppilot-tool-api`），新增一个值会在三处留下痕迹：tool-api 的枚举本体、biz-mock 的返回、gateway 的 complete 判据与受理分支。收口时 `docs/CODE_MAP.md` 的跨模块工具 DTO 行要同步。
- **ADR 0008 / 0036 / 0009 一字未动，且要有机器证据**：收口时以 `PlanExecutionTest` 原样通过作为"未扰动 2 轮预算与 Plan ≤2 步"的证据；不新增 `FallbackReason` 作为"未扰动 ADR 0009 语义"的证据。
- **gold 180 条逐类不退化**：退款 18 条（`eval/cases-part2-action.jsonl:55-72`）的 `expectStatus` 缺省 → `status_ok` 恒真（`run_tool_eval.py:215-218`）；`NOT_FOUND` 类在受理插入**之前**返回，仍 `NOT_FOUND`；缺槽位类不派发。离线 rescore 的期望差异集合仍须恰好 4 条（`ACT-ORD-09/11/16/17`）。
- **买家读回的间接风险**：工具结果变了 → dev 模型的答案文本可能变。gold **不判答案文本**，所以 gold 不受影响；但活体那几步的文本断言（`verify-action-loop.ps1`）必须复核。
- **本机活体资源已紧**，票 61 的面板断言要跑 Playwright、票 62 要起栈；活体按"全套"预算执行，但**没跑成的一律按未达成登记、不许摘红**。
- **一次退款申请的生命周期在本 ADR 后是**：受理（`PENDING_REVIEW` / 订单 `REFUNDING`）→ 放行（`PROCESSING`）或驳回（`REJECTED` / 订单回滚）。**到账不在承诺范围**，这句话必须同时出现在买家话术与 `CONTEXT.md` 的相关条目里。
