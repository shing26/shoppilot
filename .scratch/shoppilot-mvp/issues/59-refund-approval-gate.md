# 59 — 退款审批闸门（后端）：业务侧受理态 + 异步人工审核 + `ToolStatus.PENDING_APPROVAL`

**What to build:** 把「资金放行」从受理动作里拆出来，成为必须由人触发的状态迁移。今天 `BizMockService.applyRefund` 在槽位齐备、归属命中、订单可退、金额不超实付之后，于**一个事务**里插 `Refund("PROCESSING")` 并把 `Order` 置 `REFUNDING` —— 受理与放行是同一个动作，中间没有第二双眼睛。本票让申请落 `PENDING_REVIEW`（受理），资金放行（`PROCESSING`）只能由审核动作推进；审核驳回（`REJECTED`）按订单已落的时间戳推导回滚，买家可再申请。**核心判断：HITL 不必是对话轮次，可以是状态迁移的门** —— 所以 ADR 0008 的 2 轮工具预算与 ADR 0036 的 Plan ≤2 步一字不动。

**Blocked by:** None（ADR 0046 + 0047 已立；建议在票 58 之后——两票共用 `ReplayReply` 渲染器，58 已收口）。

**Status:** implemented（2026-09-28）。

**依据：所有者政策覆盖**（新功能与契约行为）。ADR 0046 记明本票是「项目所有者在知情政策下做出的显式覆盖决策」，**不许伪装成面试反馈触发，也不许写成「审计发现的 bug 必须修」**；所选设计**刻意不扰动 ADR 0008**，因此只用到登记项触发条件的**第二半**（资金动作放行成为产品要求），**不是「触发已到」**。

口径（ADR 0047 已定，本票只执行）：

- **受理与放行拆成两态。** `applyRefund` 落 `Refund(status="PENDING_REVIEW")`（原 `PROCESSING`），返回 `ToolStatus.PENDING_APPROVAL`；**保留** `Order → REFUNDING`（受理即冻结后续改动）。`PENDING_REVIEW → PROCESSING` 是**唯一不可逆迁移**；`PENDING_REVIEW → REJECTED` 可逆。
- **`shoppilot-tool-api`**：`tool/view/ToolStatus.java` 新增 `PENDING_APPROVAL`（已核安全：全仓无 `values()` 遍历、无穷尽 switch，只用 `==` 比较，`BizMockClient.interpret` 的 `valueOf` 直接接受新串）；`ToolName` 新增**声明式审批策略**（与 `intent()` 同层），本票只有 `APPLY_REFUND` 返回 true，判据是「该动作是否**不可逆或涉及资金**」而非「是否写库」（先例 `IdempotencyService.isWrite(ToolName):55`）。`ToolResponse.succeeded()` **保持** `OK || IDEMPOTENT_REPLAY` 不变（避免涟漪）。
- **`shoppilot-biz-mock`**：`applyRefund` 按上改；新增 `reviewRefund(refundId, decision, note)`（`APPROVE` → `PROCESSING`；`REJECT` → `REJECTED` + 按推导回滚订单：`deliveredAt → DELIVERED`、`shippedAt → SHIPPED`、`paidAt → PAID`；已审过的再审 → `STATE_NOT_ALLOWED`）；新增 `web/RefundReviewController`（`GET /api/refunds/pending`、`POST /api/refunds/{id}/review`），走既有 `InternalAuthFilter`。**不加 V3 迁移、不加 `prior_status` 列、不落「审核人」**（回滚可推导；`CONTEXT.md:93` 已定运维凭证不证明身份）。
- **`shoppilot-gateway`**：`agent/ToolDispatcher.executeWrite` 的 `complete` 判据从 `== OK` 扩成 `== OK || == PENDING_APPROVAL`（**唯一不可省的网关改动**——不改则受理结果不落幂等，第二次同 token 落到 biz-mock 的 `IDEMPOTENT_REPLAY` 而不经网关 `duplicate` 分支，`duplicate_submit` 消失、`verify-idempotency.ps1` 转红）；`AgentStateMachine` 在 `dispatch` 后加 `PENDING_APPROVAL` 分支：发 `tool_result` → `ReplayReply.pendingApproval(...)` 受理话术 → 直接收尾（**不打第二跳模型**）；新增指标 `shoppilot_refund_pending_total`。
- **不新增 SSE 事件、不动 10 状态枚举、不新增 `FallbackReason`**（受理不是失败，落工单会污染「降级 9」口径与 ADR 0009 语义）；受理态走既有 `tool_result.status`。
- **gold 影响逐类不退化**：退款 18 条只查 `tool`/`args`，`expectStatus` 缺省 → `status_ok` 恒真；`NOT_FOUND` 类在受理插入前返回；缺槽位类不派发。离线 rescore 期望差异集合仍须**恰好 4 条**（`ACT-ORD-09/11/16/17`）。
- **覆盖率是硬约束**：biz-mock LINE 门槛 `76.0`，实测余量仅 **1.49pp** —— `reviewRefund` 与审核控制器**一律配 JUnit 用例**，否则 `scripts/check_coverage.py` 直接红。

**需同步更新的既有测试（被测行为变了，不是改 gold）**：`TenantIsolationAndIdempotencyTest` 中两条断言受受理态改变影响 —— `:118` 的 `"OK"` → `"PENDING_APPROVAL"`；并发用例 `:157` 的 `filter("OK")` → `filter("PENDING_APPROVAL")`（总数与 `IDEMPOTENT_REPLAY == 49` 不变）。Handoff 里必须把「被测行为变了」与「改判据/gold」**分开写**。

- [x] `ToolStatus` 新增 `PENDING_APPROVAL`；`ToolName` 新增声明式审批策略（仅 `APPLY_REFUND` 为 true）
- [x] `applyRefund` 落 `PENDING_REVIEW` + 返回 `PENDING_APPROVAL`；`Order→REFUNDING` 保留
- [x] `reviewRefund`：三态迁移正确；REJECT 按时间戳推导回滚（驳回后买家能再申请）；重复审核 → `STATE_NOT_ALLOWED`
- [x] `RefundReviewController`：`GET /api/refunds/pending` + `POST /api/refunds/{id}/review`，走 `InternalAuthFilter`
- [x] gateway：`executeWrite` 的 `complete` 判据扩到 `PENDING_APPROVAL`；`AgentStateMachine` 受理分支（`tool_result` + 确定性话术 + 直接收尾）；`ReplayReply.pendingApproval(...)`
- [x] 指标 `shoppilot_refund_pending_total`
- [x] `reviewRefund` 与控制器 JUnit 用例（覆盖率）
- [x] `TenantIsolationAndIdempotencyTest` 两条断言随被测行为同步（非改 gold）
- [x] **`PlanExecutionTest` 原样通过**（ADR 0036 未扰动的机器证据）
- [x] 全量 `verify` 绿；`check_coverage.py` exit 0；`verify_eval_judge.py` 40/40；离线 rescore 差异恰好 4 条

**Verify**
```bash
./mvnw.cmd -B -ntp verify
python scripts/check_coverage.py
python scripts/verify_eval_judge.py
python scripts/run_tool_eval.py --rescore ...
```
（活体 `verify-idempotency.ps1` 属票 62；本票按未覆盖登记。）

## Handoff notes

**关键决策**

- **受理与放行拆成两态，闸门落在业务侧状态迁移上。** `applyRefund` 落 `Refund("PENDING_REVIEW")` 并回 `ToolStatus.PENDING_APPROVAL`，订单仍在同一事务里置 `REFUNDING` —— 「受理即冻结」同时是「同一订单只允许一笔在办退款」的守卫（`refundable()` 在 `REFUNDING` 时 false，二次申请被既有 `STATE_NOT_ALLOWED` 挡住，无需新增唯一性检查）。资金放行只能由 `reviewRefund(APPROVE)` 推进。**核心判断：HITL 不必是对话轮次，可以是状态迁移的门** —— 所以 ADR 0008 的 2 轮预算与 ADR 0036 的 Plan ≤2 步一字未动（`PlanExecutionTest` 原样通过）。
- **回滚用推导，不加列、不做迁移。** `REJECT` 后按 `deliveredAt → DELIVERED`、`shippedAt → SHIPPED`、`paidAt → PAID` 推导回滚，与 ADR 0047 决策四一致；`note` 接受但不落库（审查人自用的理由在 v1 没有消费者）。
- **网关唯一不可省的一行**：`ToolDispatcher.executeWrite` 的 `complete` 判据从 `== OK` 扩成 `== OK || == PENDING_APPROVAL`。不扩则受理结果不落幂等，第二次同 token 落到 biz-mock 的 `IDEMPOTENT_REPLAY` 而不经网关 `duplicate` 分支。用 `ToolDispatcherApprovalTest` 单独钉住（断言 `complete` 被调、`abandon` 未被调）。
- **受理出口复用 `ReplayReply` 而不是新渲染器**：新增 `ReplayReply.pendingApproval(tool, json)`，与票 58 的 `render(...)` 同一 helper 的两个方法；受理话术确定性、**不打第二跳模型**（`pendingApprovalAnswer` 一次规划调用后直接收尾），且明确写出到账边界（「审核通过后到账由支付渠道处理，不在本客服承诺范围内」）。
- **受理不落工单、不新增 `FallbackReason`。** 受理不是失败；`pendingApprovalAnswer` 不走 `fallback(...)`，`fallbackReason` 为空、`shoppilot_fallback_*` 不动 —— 避免污染「降级 9」的口径与 ADR 0009 语义。

**一处契约外的发现（本票内顺手钉住）**：`RefundRepository.findById(id)` **不经 `@TenantId` 谓词**（Hibernate 的租户判别器只作用于查询，不作用于按 id 的 `find`），所以审核端点在跨租户下会命中别店的退款单。处置是**在 `reviewRefund` 里显式比对 `TenantContextHolder.tenantId()` 与 `refund.getTenantId()`**，不符一律 `NOT_FOUND`（与订单归属同一口径：不区分"存在但不可见"）。这条由 `RefundReviewTest.reviewIsTenantScoped` 钉住。**不是改判据**：既有 `OrderRepository` 的归属查询都是派生查询（带谓词）才没暴露这一点。

**被测行为变了（不是改判据/gold）**：`TenantIsolationAndIdempotencyTest` 两条断言随受理态同步 —— `:118` 的 `"OK"` → `"PENDING_APPROVAL"`、并发用例的 `filter("OK")` → `filter("PENDING_APPROVAL")`（`IDEMPOTENT_REPLAY == 49` 与总行数不变）。**gold 一字未改**，离线 rescore 差异仍恰好 4 条（`ACT-ORD-09/11/16/17`）。

**验证落点**

- 全量 `.\mvnw.cmd -B -ntp verify` → **`5 + 28 + 289 = 322` 绿**（tool-api 3→5、biz-mock 21→28、gateway 287→289）。
- 覆盖率棘轮 `check_coverage.py` exit 0：biz-mock LINE **77.49% → 78.64%**（门槛 76.0，此前余量仅 1.49pp）、gateway **59.47% → 59.65%**、tool-api **41.73% → 49.30%**（新增 `ToolContractApprovalTest` 覆盖 `requiresApproval` / `pendingApproval` / 新枚举反解）。
- 门禁：`verify_eval_judge.py` **40/40**；`eval_suites.py` **ok=24**；离线 rescore **cases=180 files=6 tool_diff=4**（期望差异集合未变）。
- `PlanExecutionTest` **原样通过**（ADR 0036 未扰动的机器证据）。
- `git diff --check` 干净。

**未覆盖（按未达成登记，不摘红）**

- 活体 `verify-idempotency.ps1` 与 `verify-refund-approval.ps1` 均未跑（需起栈），属票 62；gold 180 条活体重跑未做（需额度）。**不得声称活体已绿。**

**你需要能当场回答的三个追问**

1. *Q：为什么审批闸门做在业务侧的状态迁移上，而不是做在会话轮次里？* A：闸门拦的是"钱动没动"，不是"买家打没打字"。会话内两段式的首轮不会派发 `applyRefund`，`TOOL_EXEC` 里没有该工具名 → 14 条退款 gold 全红且无退路；而且它会把"审批待确认"塞进 `CONTEXT.md:69` 已锁定的「待办动作」（那里是"信息不全、尚未执行"）。
2. *Q：为什么给 `ToolStatus` 新增一个值，而不是复用 `OK` / `STATE_NOT_ALLOWED`？* A：复用 `OK` 会让"已受理"与"已放行"在 `tool_result` 与 trace 里同形，审计时无法证明门存在；复用 `STATE_NOT_ALLOWED` 语义错（这是受理不是拒绝）且会命中 `AgentStateMachine.failedStep` 把受理当"前步失败"中止 Plan；复用 `IDEMPOTENT_REPLAY` 语义相反。新增值已核安全（全仓无 `values()` 遍历、无穷尽 switch）。
3. *Q：为什么审核端点要显式比对租户，`@TenantId` 不是自动的吗？* A：`@TenantId` 的判别器作用于**查询**，不作用于 `find(id)`。这是本票实测发现的（跨租户审核返回 200）。显式比对后跨租户一律 `NOT_FOUND`，与订单归属口径一致；派生查询（如 `findByStatus...`）仍由判别器自动过滤。