# 37 — 满意度反馈闭环：显式点踩 + 隐式信号 + ingest 待复核队列

**What to build:** 按 ADR 0039 新增 `feedback` 能力（biz-mock 落表 + 网关账本），双信号采集：显式点踩/点赞走 `POST /api/v1/support/chat/feedback`（同步与 SSE 客户端同形，会话结束未评 = 无信号）；三个隐式信号全是既有可观测转移上的计数埋点、状态机零改动——重问同意图（网关会话线索比对，30 分钟窗口）、走到 FALLBACK（ChatController 在结果上观测）、幂等重放（ToolDispatcher 的 duplicate 分支）。DOWN 行自动关联当次会话的工单与检索引用块，进 ingest 待复核队列（`/api/feedback/review-queue`，经运维代理租户隔离可见）；人工复核 PENDING → REVIEWED 单向流转，不自动改写知识库。

**Blocked by:** 票 34（CI rescore 门禁）——已收口。

**Status:** implemented（2026-09-19；全量 JVM `3 + 15 + 231 = 249` 绿，量具 40 PASS，rescore exit 0，CI 见 Handoff）

- [x] biz-mock：`Feedback` 实体（ddl-auto 自动建表）+ `FeedbackRepository` + `FeedbackService`（落行/队列/复核流转）+ `/api/feedback` 三端点（内部令牌 + 租户头，与工单同口径）
- [x] 网关：`feedback/FeedbackService`——会话线索（ConcurrentHashMap 单机内存态）、隐式计数（`shoppilot_feedback_implied_total{kind}`）、显式计数（`shoppilot_feedback_explicit_total{verdict}`）、HTTP 落点留 `FeedbackPoster` 缝（单测 0 网络驱动）
- [x] `ChatController`：`/chat/feedback` 回传端点（verdict 白名单 UP/DOWN，其余 400）；同步与流式完成后 `noteAnswer` 同形更新账本
- [x] `ToolDispatcher`：duplicate 分支计 `implied_retry`
- [x] `OpsController`：`/ops/feedback/review-queue` 运维代理（租户隔离）
- [x] biz-mock `FeedbackReviewQueueTest` 3 项（DOWN 进队列/UP 不进、复核单向流转 409、租户隔离）；网关 `FeedbackServiceTest` 5 项（重问窗口/相邻轮次语义/negative/关联字段随行/下游不可达如实降级）；主链路反馈端点 1 项
- [x] `verify-feedback.ps1` 活体验收（三断言 + 三计数，UTF-8 BOM、PS 5.1 语法零错误）
- [x] 全量 `mvnw verify` 绿；`verify_eval_judge.py` 40 PASS；rescore 门禁 exit 0

**Verify:**

```powershell
.\mvnw.cmd -B -ntp verify
python scripts\verify_eval_judge.py
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\check-ps-syntax.ps1   # verify-feedback.ps1 零错误
# 活体（需 dev 栈）：
pwsh -NoProfile -File scripts\verify-feedback.ps1
```

预期：全量三模块 249 绿；量具 40 PASS；rescore `tool_diff=4` exit 0；活体脚本 PASS 7 / FAIL 0。

**验收项**

1. 点踩 → feedback 表落行（feedbackId 可返回）；关联工单与 ruleId 随行可查；复核队列按租户可见该行。
2. 重问/降级/幂等重放三个隐式信号计数各断言一次（活体走指标端点；单测走 registry）。
3. 复核状态单向流转 PENDING → REVIEWED，重复复核 409；复核动作本身不触发知识库自动改写（ADR 0039 边界）。
4. 已知口径照登：会话线索是单机内存态，重启即清空——重启后未评 = 无信号（不猜测原则），不外推多实例；点赞（UP）不进复核队列，只进 explicit 计数。

## Handoff notes

**关键决策**

- **隐式信号分三处埋点、统一一个指标名**：重问在网关账本（需要跨请求的会话线索，状态机与 SessionStore 都不存意图，ADR 说的"零新增判定逻辑"指的是不新增业务判定——相邻轮次意图比对是账本自己的簿记）；negative 由 ChatController 在结果上观测（fallbackReason 非空即 negative，不进状态机不改 FallbackService）；implied_retry 由 ToolDispatcher 的 duplicate 分支直接计数（唯一能看见幂等命中的既有转移点）。三处共用 `shoppilot_feedback_implied_total{kind}` 一个名字，口径才可分账。
- **重问语义钉在"相邻轮次"**：同会话内第 N 轮意图与第 N-1 轮一致且在窗口内才计数——中间插了别的问题就断开，重新连上两轮才算新一轮重问。这与"买家把同一个问题换个说法再问"的用户直觉一致，也避免长会话里任意两轮同意图被误算。
- **点踩的关联线索走网关账本而非客户端上报**：响应里的 citations/ticketId 虽然客户端也拿得到，但让服务器从自己的线索里取关联，才保证"自动关联"不可被客户端伪造；线索缺失（如重启后）时如实落一条无线索的 DOWN 行，队列照样可见。
- **biz-mock 的 FeedbackView 定义在服务层**：控制层直接引用服务的视图记录，不为同一形状再造第二份（工单的 TicketView 在 tool-api 是因为跨模块契约需要；feedback 只被网关转发字符串，无契约需求）。

**验证落点**

- 全量 `mvnw verify`：`3 + 15 + 231 = 249` 绿（biz-mock +3、网关 +6：FeedbackServiceTest 5 项 + 反馈端点 1 项）。
- 量具 40 PASS；rescore `tool_diff=4` exit 0（本票不改 judge 语义）。
- `verify-feedback.ps1`：活体验收未跑（本机无 dev 栈），登记为 17 步全量验收边界；脚本 PS 5.1 语法零错误。
- CI run 见 Handoff 末行。

**现场追问**

1. *为什么隐式信号只计数不落行？* 队列是给人工看的：每个降级、每次重问都落行会把队列灌成流水账，人工复核就失去意义。计数承载"规模"，点踩行承载"内容"——踩评行上还随行展示会话内观测到的 negative 信号，人工复核时两者都看得到，但队列密度由真实不满（点踩）决定。
2. *会话线索为什么放内存不放 Redis？* 线索只服务"下一次点踩时的关联"这一件事，重启丢失的代价是"那几条点踩没有自动关联"，行照样落、队列照样可见；为它上 Redis 分布式结构是用部署形态的复杂度换一个演示口径不存在的需求（ADR 0039 否决"隐式信号单独存 Redis"的同一条理）。
3. *点赞为什么也落库？* 负率口径（周聚合）需要分母，只有踩没有赞算不出率；UP 行 reviewStatus=NONE 不进队列，存储成本与审计价值对等。
