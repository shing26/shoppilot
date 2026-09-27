# 58 — 幂等重放时机前移：请求级回放索引，判重放不再取决于模型

**What to build:** 把「客户端重试不该重复执行、应回放」这条承诺从**模型行为**上解下来。现状的重复检测只在 `ToolDispatcher.dispatch`→`executeWrite`（`:93`）里，也就是**模型这一轮又发了一次同样的工具调用**才触发；模型不发就没有回放、也没有降级话术，请求直接走到 `done` 而答案是空的（round20 spec 登记节第 5 项，探针 **3/3 复现**，SSE trace `PLAN round=0 → corrective=applyRefund → PLAN round=0 → done`、`completionTokens=1`、`answer` 为空）。

**Blocked by:** None。

**Status:** implemented（2026-09-28）。

**依据：ADR 0031 第 10 行（兑现既有 `idempotencyToken` 契约语义，不新增功能面、不新增 intent/工具/事件）**。ADR 0046 已记明本票按同一行处置。

口径（ADR 0046 / ADR 0047 已定，本票只执行）：

- **回放点在模型之前，但在情绪门之后。** 位置选在 `run()` 里情绪短路分支之后、`session.hasPending()` 之前。两条理由：① **不扰动情绪门的既有优先级** —— ADR 0034 与 ADR 0042 的排序（ADR 0042 把显式转人工提到情绪判定之前）是两次回归换来的，回放不该抢在安全出口之前；② 放在 `hasPending` 之前，因为回放**不是**槽位补齐。
- **回放不走 fallback 出口。** 回放不是降级；落工单会污染「降级 = 枚举 − 1 = 9」的口径，也撞 ADR 0009「转人工 = 落可查工单」的语义。它走 `sink.duplicateSubmit(...)`——那条事件本来就是为「幂等命中、业务动作没再执行」准备的（`EventSink.java:24` 的注释即此）。
- **索引只存指针，不复制结果。** 请求级键的值是 `工具名|请求指纹|幂等键`，回放时再按幂等键取结果。**同一事实不开两个出口**（ADR 0030 的 Considered Options 逐字否决过"网关侧加工单审计副本"：「副本与真单之间没有一致性协议」）。
- **指纹是第二条件，不是装饰。** `queryHash` 相等才算命中；不等就**放行给正常链路**。这样「客户端复用了旧 token 却换了要求」按新请求处理（那是客户端契约违约），而"重复执行"仍由 post-model 那道幂等兜住。
- **只在客户端显式给 token 时才算指纹。** 派生 token 在模型之前算不出来（它要工具参数），而"客户端复用旧 token"这个问题也只存在于显式 token 下。指纹为 null 时**既不写也不查**索引 —— 行为与 ADR 0008 时期逐字一致（5 参 `begin` 重载保留，就是这条）。
- **`abandon` 要把指针与结果一起清掉**：业务拒绝时留下一个指向已删结果的指针，下次重试会拿到空回放。
- **确定性话术由 `ReplayReply` 渲染**：回放发生在模型之前，那一刻没有模型输出可用，所以答案必须由网关按工具结果写出来。解析不出结果时给一句保守的通用话，**不编具体字段**。
- **`complete` 写索引的时机是"首次执行成功"**，与结果落键同一处；`PENDING` 占位不算结果（不能把"正在跑"回放成"已完成"）。

- [x] `IdempotencyService`：`Guard` 带 `clientToken` / `requestFingerprint`；6 参 `begin` 重载（5 参版保留）；`complete` 写请求级指针；`abandon` 两个键一起清
- [x] `IdempotencyService.lookupByClientToken(...)`：token 非空 **且** 指纹相等才算命中；`PENDING` / 结果被 TTL 收走 / Redis 不可用一律返回 empty
- [x] 新增 `agent/ReplayReply.java`：按工具渲染确定性话术（退款带申请编号与状态，改址带订单号）
- [x] `ToolDispatcher`：4 参 `dispatch` 重载（3 参版保留）+ `lookupReplay(...)`；`executeWrite` 传指纹
- [x] `AgentStateMachine`：回放钩子 + `replayAnswer(...)` 出口 + 两处写路径 dispatch 带指纹
- [x] 单元：`IdempotencyServiceRequestReplayTest` **9 条**（命中 / 指纹不等不命中 / 派生 token 不写索引 / 不跨买家跨租户 / abandon 清指针 / 结果被收走不命中 / PENDING 不算结果 / 无 token 无指纹不查 / Redis 停机返回空）
- [x] 端到端：`GatewayMainPathJvmTest` **2 条**（同 token 重放 → 含申请编号的确定性答案 + 零模型调用 + 零业务调用 + 不落工单；正对照：无 token 时不查索引）
- [x] **变异对照**：把回放钩子的条件改成 `if (false)` → `repeatedRequestReplaysTheFirstResultWithoutCallingTheModel` **转红**（NPE `lastReply is null`，证明它真的走到了模型），还原即绿
- [x] 既有两个 mock 桩随签名加宽同步（`PlanExecutionTest` 的 `begin`、`ConversationOwnershipTest` 的 `dispatch`/`never()`）
- [x] 全量：`.\mvnw.cmd -B -ntp verify` → `3 + 21 + 287 = 311` 绿（gateway 276 → **287**）
- [x] 覆盖率棘轮：`check_coverage.py` exit 0，gateway LINE 58.52% → **59.47%**（门槛 54.0）
- [x] 门禁：`verify_eval_judge.py` **40/40**；`eval_suites.py` **ok=24**；`git diff --check` 干净
- [ ] **未做**：活体 `verify-idempotency.ps1`（需起栈）；gold 180 条回归（需额度）。见 Handoff「未覆盖」

## Handoff notes

**关键决策**

- **位置：模型之前，但情绪门之后。** 分域调研给的措辞是「在 `triageEngine.triage` 之前」；实现时我把它再往后收了半格，放到情绪短路分支之后。理由是 `ADR 0042` 刚刚（票 45）把「显式转人工优先于情绪判定」钉死，而那次修复的教训正是"某个新增的判定插到了既有优先级前面"。回放是一次**回答**，它不该越过安全出口 —— 情绪门仍然先说话。这条与 ADR 0046 的"在 triage 之前"不冲突，是它的收紧。
- **索引存指针而不是结果。** 两个候选：把 `resultJson` 复制进请求级键（一次读），或只存 `工具名|指纹|幂等键`（两次读）。取后者，因为本仓对"同一事实两本账"有明确前科否决（ADR 0030 否决「网关侧加工单审计副本」的原文：「副本与真单之间没有一致性协议，多出一个看似权威的第三本账」）。代价是一次额外的 Redis 读，换来的是"结果的唯一真相仍在幂等键上"。
- **`abandon` 必须清指针**，否则业务拒绝（`STATE_NOT_ALLOWED` 等）之后，指针会指向一个已被删掉的结果键 —— 下次重试命中指针却取不到结果，会得到一个空回放。实现里两处删除是并列的，不是先后依赖。
- **5 参 `begin` 与 3 参 `dispatch` 两个重载都保留了。** 这不是为了省测试改动，而是它们各自代表一条**真实存在的语义**：指纹为 null 时"既不写也不查"索引，行为与 ADR 0008 时期逐字一致。保留它是把那条语义写成可调用的形状，而不是把旧签名当兼容层。
- **一处必须一起改的断言**：`ConversationOwnershipTest` 里两条 `dispatch(any(), any(), any())` 的桩与断言要一起升到 4 参。**特别提醒那两条 `verify(never())`** —— 如果只改桩不改断言，`never()` 会因为条件落在另一个重载上而**恒真**：测试看起来还在守，实际已经失效。这是"签名加宽"这类改动最容易留下的假绿，写在这里提醒下一个人。
- **`ReplayReply` 的失败姿势是"少说"。** 解析不出 `payload` 时给「这次请求已经处理过了，本次没有重复执行。」——不拼具体字段。宁可少说一句，也不把凭空拼出来的申请号说给买家。

**验证落点**

- **单元 9 条**（`IdempotencyServiceRequestReplayTest`）：本类用 **Map 支撑的假 Redis**（不是逐次 stub 返回值），因为要验的正是"写进去的指针能不能被读出来"这种往返性质；`ValueOperations.set` 是 void 所以走 `doAnswer`。
- **端到端 2 条**（`GatewayMainPathJvmTest`）：真 `ChatController` + 真 `AgentStateMachine` + 真 `ToolDispatcher`，只把 HTTP/检索/模型换替身。回放那条断言 `$.answer` 含 `RF-777`（**只可能来自被回放的结果**，故非空断言）+ `toolUsed=true`，并 `verify(llm, never()).complete/stream`、`verify(bizMock, never()).call`、`verify(fallback, never()).escalate`。
- **变异对照**：钩子条件改 `if (false)` → 回放那条转红（NPE `lastReply is null`）。这证明它不是一条恒绿断言。
- **全量**：`.\mvnw.cmd -B -ntp verify` → `3 + 21 + 287 = 311` 绿；`check_coverage.py` → `COVERAGE OK`，gateway LINE **59.47%**（门槛 54.0）；`verify_eval_judge.py` **40/40**；`eval_suites.py` **ok=24**；`git diff --check` 干净、`git status` 无本机日志混入。
- **gold 零影响的依据（静态）**：`scripts/run_tool_eval.py:519` 的 payload 是 `{"query": case["query"]}` —— **评测客户端不发 token**，所以本站的预回放对 180 条不触发。这是读代码得到的结论，**不是实测**（见下）。

**未覆盖（按未达成登记，不摘红）**

- **活体 `verify-idempotency.ps1` 未跑**（需起栈：Redis + biz-mock + gateway）。按脚本逻辑推断它**更稳**（两次调用是「不同 conversation id、同 query、同 token」→ 预回放命中，不再依赖模型重发工具），但**推断不算证据**：本票不声称该脚本已绿。
- **gold 180 条回归未跑**（需 dev 额度）。静态依据如上，但**不声称"gold 未漂移"**。
- 两项都登记在本轮收口（票 63）的活体批次里，按所有者裁定的「全套」预算执行。

**你需要能当场回答的三个追问**

1. *Q：为什么回放要放在模型之前？放在幂等层（模型之后）不是更省事吗？* A：因为放在之后，回放与否就取决于**模型肯不肯再发一次工具调用**。本地 3B 在"会话里已有上一轮成功答复"时就不发（round20 登记第 5 项，SSE trace 里 `PLAN round=0 → corrective=applyRefund → PLAN round=0 → done`，`completionTokens=1`、答案为空）。于是 `idempotencyToken` 承诺的"重试应回放"变成了一句看模型心情的话——**这是设计缺陷，不是模型缺陷**，所以修在判定时机上。
2. *Q：把回放提到模型之前，会不会误伤"用户换了要求但客户端复用了旧 token"？* A：不会，这正是 `queryHash` 那道条件的用处：指纹不等就**放行给正常链路**，按新请求处理。token 的语义本来就是"同一逻辑请求"，复用旧 token 发新请求属于客户端契约违约；而"重复执行"这一侧仍有 post-model 幂等兜着，两头都保住。
3. *Q：为什么请求级索引只存指针、结果仍读原来那个键？* A：为了不给同一事实造第二本账。结果复制进索引就意味着存在两份可能不一致的副本（写一半失败、TTL 不同步收走），而本仓对这类形状有明确前科否决。多一次 Redis 读换"结果的唯一真相只有一个键"，这笔账在本项目的量级下是划算的。
