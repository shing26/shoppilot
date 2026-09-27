# round20 规格：修 22 步矩阵的四条红（三条事实性修正 + 一条 orderNo 溯源）

> 依据：ADR 0045（重开依据与范围）｜基线：HEAD `e458677`（round19 已收口并推送，票 50 的读数验证同日完成）
> 红步来源：`logs/acceptance-run-20260924-180518.log`（512 s、18 步绿 / 4 步红）
> 取证：2026-09-25 对四条红逐条做根因取证（结论与逐字证据见下节）

## 基线复核（2026-09-25，对仓现场核对）

**四条红的根因分三类，其中三条不在被测代码里**——这是本轮最需要写清的事，因为它决定了修法不碰被测代码：

| 步 | 实测读数 | 根因 | 是不是被测代码的问题 |
|---|---|---|---|
| `feedback` | 4 PASS / 3 FAIL | ②③ 两条读数**在结构上不可能通过**（`Get-Counter` 被管道调用而函数没有 `ValueFromPipeline`，参数整体前移）；① 的刺激与自己的注释矛盾（发的是正常政策问句，本就不该降级） | **否**，脚手架缺陷 |
| `emotion` | 7 PASS / 1 FAIL | `EMO-ESC-02` 的问句含「转人工」，ADR 0042 规定显式转人工优先 → 落 `USER_REQUESTED` 是**正确行为** | **否**，用例数据与已锁定 ADR 冲突 |
| `plansteps` | 0 PASS / 7 FAIL | local 3B 不产生两步链（`steps="2"` 恒 0）；语义已由 `PlanExecutionTest` 5 项钉住。**登记材料另有两处不实** | **否**（判据保留、只改登记） |
| `action` | 8 PASS / 3 FAIL | 3B 照抄 schema 示例值 `10023`；`isFabricatedOrderNo` 只校验格式（`\d{1,12}`）拦不住；**没有第二道来源校验** | **是**，真功能缺陷 |

**取证到的关键事实（逐条附落点）**

1. **`Get-Counter` 的管道绑定错误**：`scripts/verify-feedback.ps1:27` 声明 `function Get-Counter([string]$Metrics, [string]$Name, [string]$Tags)`，**无 `ValueFromPipeline`**；`:103/106/116/123` 却写成 `Get-Metrics | Get-Counter "name" 'tag'`。用仓库自己的 pwsh 7.4.20 实测：管道形式下 `Metrics=[name] Name=[tag] Tags=[]`（参数前移一格），于是 `:28` 的 `$_ -like "$Name*$Tags*"` 永不命中 → 返回 `0.0` → `0 -gt 0` 假 → 恒 FAIL。对照 `negative` 用的是位置形式（`:39/:93`），所以它能读到真实值。
2. **`negative` 的刺激缺失**：`:36` 注释写「用一个必然走降级的问题（不在政策库且无工具诉求）」，而 `:42` 实际发的是用来验引用块的正常政策问句——实测 `intent=POLICY_RETURN cache=NONE degraded=false`，`fallbackReason` 为空，按 `FeedbackService.java:107` 就不该自增。**注释描述的是意图，代码做的是另一件事。**
3. **`EMO-ESC-02` 是 20 条问句里唯一含升级词的一条**：机械核对 8 条升级样本 + 12 条非升级样本，只有它含 `T0RuleLayer.ESCALATE_WORDS`（`triage/T0RuleLayer.java:50`）里的「转人工」；`EMO-ESC-07` 的「真人领导」与 `EMO-ESC-08` 的「人工处理」都不在词表内。且 `eval/cases-part4-emotion.jsonl:2` 的 `"intent": "ACTION_LOGISTICS"` 同样已失效（活体被 T0 判成 `ESCALATE`）。
4. **`plansteps` 的两处不实登记**：① round17 spec 的 F3 行（`:106`）把 `PlanExpressionTest` 列为 0 token 覆盖源，**该文件在仓内不存在**（只有 `main/.../agent/PlanExpression.java`），实际覆盖者是 `PlanExecutionTest` 第 2/3 条；**全仓只此一处引错**（取证时曾误记为"F3 行与票 39 都引"，票 55 已核实并更正——票 39 引的是正确的 `PlanExecutionTest`）；② `steps=2:0 aborted:7` 里的 `aborted:7` 是**进程启动以来累计**的 Prometheus 计数器，被当成本次 7 条用例的读数。
5. **`action` 同时也是 gold 未达成**：`eval/cases-part2-action.jsonl:30` 的 `ACT-LOG-12` 逐字要求 `"slotAsk": true, "mustNotContainArgs": ["orderNo"]`（同类还有 `ACT-LOG-11`、`ACT-ORD-11/12`）。所以这条红不是验收脚本自创的口径——**修好它会让 gold 更绿**。
6. **四个 schema 描述不对称**：只有 `QueryOrderDetailRequest.java:6` 带「用户未提供时必须追问而非猜测」，`QueryLogisticsRequest` / `ApplyRefundRequest` / `ModifyDeliveryAddressRequest` 三条只写「平台订单号，例如 10023」。本问句被 T0 判成 `ACTION_LOGISTICS`，模型选的正是 `queryLogistics`。
7. **模型已给参数时网关没有第二道**：`deriveActionCall` 的入口条件是 `rounds == 0 && !toolUsed`（`AgentStateMachine.java:405-408`），模型一发工具就整体跳过；`ToolDispatcher.dispatch(call, token)` 的签名里没有 `query`，拿不到"用户说过什么"。

## 票据拆分

| 票 | 标题 | 依据 | 依赖 | 估时 |
|---|---|---|---|---|
| 53 | `feedback` 步：修 `Get-Counter` 调用形式与 `negative` 刺激 | ADR 0031:10 | 无 | 0.5 天 |
| 54 | `emotion` 步：`EMO-ESC-02` 重分类为显式转人工样本 + 新增一条强情绪样本 | ADR 0031:10 | 无 | 0.5 天 |
| 55 | `plansteps`：更正两处不实登记 + 该步在 local 档登记为已知不达成 | ADR 0031:10 | 无 | 0.5 天 |
| 56 | `orderNo` 溯源守卫（含 `resumePending` 与 gold 回归复核） | 所有者政策覆盖 | 无 | 1 天 |

**建议顺序：55 → 53 → 54 → 56**（依据 ADR 0045 的 Consequences：本机资源已紧，把最省栈的一次放最前面）。

## 验收判据（每票一组，机器可复跑）

### 票 53 — `feedback` 步读数与刺激

1. `scripts/verify-feedback.ps1:103/106/116/123` 的 `Get-Counter` 调用改成与 `:39/:93` 一致的位置形式（或给函数加 `ValueFromPipeline`），**二者取一并在注释里写明为什么**。
2. `negative` 的刺激换成**真的会走 FALLBACK** 的请求（`转人工` 走 `USER_REQUESTED` 已在 ADR 0042 后由 `verify-fallback.ps1` 7/7 稳定复现，是现成的选择），并把 `:36` 的注释改成与代码一致的描述。
3. **修前红 / 修后绿两组活体读数**（ADR 0045 的 Consequences 强制要求）：修前后各跑一次 `pwsh -NoProfile -File scripts/verify-feedback.ps1`，两次输出都入库路径式记录；只给修后读数不算收口。
4. **不许改 `shoppilot-gateway/src/main/java/.../feedback/FeedbackService.java` 与 `agent/ToolDispatcher.java` 的计数逻辑**。若修完读数后 `implied_retry` 仍不增，按票 55 的处置登记为模型能力边界并保留红值。
5. `implied_retry` 若转绿：在 Handoff 里写清两次 `applyRefund` 是否真的都发了（读 `shoppilot_llm_write_nudge_total` / `tool_result` 事件），别把"恰好绿了"当成"链路通了"。

### 票 54 — `emotion` 步的用例重分类

1. `scripts/verify-emotion.ps1` 里把 `EMO-ESC-02` 从 `$escalations`（8 条词典层升级）移出，**新增**一个「显式转人工」类目，期望 `fallbackReason = USER_REQUESTED`，且**不**要求 `priority = high`（只有 `EMOTION_ESCALATION` 才传 `high`，见 `AgentStateMachine.java:654-656`）。
2. **新增一条不含任何升级词的强情绪样本**补回「词典层升级」的计数（add-only，不删 `EMO-ESC-02`）。新增样本必须先机械核对**不含** `ESCALATE_WORDS` 的七个词，且它的情绪必须能被词典层单独定案（不依赖第二层——local 档只有词典层，见 ADR 0043）。
3. `eval/cases-part4-emotion.jsonl` 按 **add-only** 处理：新增显式转人工 case 与新的强情绪 case；`EMO-ESC-02` 那一行的 `expect.reason` 若要改，**必须同步** `scripts/eval_suites.py` 的 emotion 分支（`:69-76`，它把 `EMOTION_ESCALATION` 硬编码成唯一的升级证据）与其 24 条 `selfcheck` 夹具，并跑 `python scripts/eval_suites.py` 确认 `SUITE SELFCHECK` 仍全绿。
4. `SentimentGateTest.java:36-44` 的 `LEXICON_ESCALATIONS` 若含 `EMO-ESC-02` 原串，同步调整（该用例不经状态机，ADR 0042 不影响它，但样本要同源）。
5. 自述换代：round17 spec 与 `README.md` 里「词典层 8 条」的计数按新基数改写（8 → 新的条数），并留换代指针说明为什么变。
6. **修前红 / 修后绿**两组活体读数（同票 53 第 3 条的要求）。

### 票 55 — `plansteps` 的登记更正（不改判据）

1. 更正 `PlanExpressionTest` 引用（round17 spec 的 F3 行 `:106`），改成实际覆盖者 `PlanExecutionTest` 第 2/3 条。**注意只此一处**：`issues/39-plan-ordered-steps.md` 引的是正确的 `PlanExecutionTest`，不在改动面。
2. 在 round17 spec 的 F3 行与 `docs/EVIDENCE.md` 的矩阵行里写明：`aborted` 是**进程生命周期累计计数器**，`steps=2:0` 与 `rejected:0` 才是"从未达成"的硬证据。
3. 补一句可自证的取证口径：`verify-plan.ps1` 的 `Get-ToolSteps`（`:24-26`）只筛 `TOOL_EXEC` 行、丢了 `round=` 行，所以**归因无法从该脚本自证**；要看完整响应 trace 的 `round=` 序列，或加读 `shoppilot_llm_multi_tool_calls_total`（识别"一次回复塞两个调用、代码只取第一个"这个真实混淆项）与 `shoppilot_llm_write_nudge_total`。
4. 把该步在 local 档**登记为已知不达成**（判据一字不动），措辞要与 `README.md` 已有的「未达成照登」一致。
5. 可选（不作为收口必要条件）：把 local 模型换 `qwen2.5:7b` 试一次两步链。**必须走 `.env`**（`SHOPPILOT_LOCAL_LLM_MODEL`，WMI launcher 只认 `.env`，见 EVIDENCE），零仓内 diff；若试了，读数按"可选实验"登记，不改任何判据。
6. `git diff --check` + `git status --short` 干净；本票**不产生代码改动**（`.scratch/`、`docs/`、`README.md` 之外的路径都不该出现）。

### 票 56 — `orderNo` 溯源守卫

1. **先定口径再动代码**：在 Handoff 里写清"什么算参数出自本轮对话"——范围必须覆盖当前 query、本会话历史轮次、以及续办轮买家补充的 query（`askSlot` 之后 `resumePending` 的取值路径 `AgentStateMachine.java:591-602`，`extractSlots` 能取到），否则会误伤续办。
2. 守卫落点必须在**派发之前**且对 `ToolDispatcher.isFabricatedOrderNo` 是**叠加而不是替换**：格式校验继续拦明显不合格式的值，新增的溯源校验拦"格式合法但不出自对话"的值。
3. 命中溯源校验时的行为**必须是 `slot_ask` 追问，不是 FALLBACK**：复用既有 `missingSlots = ["orderNo"]` → `askSlot` 出口（`AgentStateMachine.java:324-329` 的 fabricated 分支就是现成形态），不新增出口、不动 10 状态枚举。
4. **gold 回归复核（本票的重点）**：`eval/cases-part2-action.jsonl` 里缺槽位类用例（`ACT-LOG-11`、`ACT-LOG-12`、`ACT-ORD-11`、`ACT-ORD-12`）应转绿；同时逐条确认**所有"用户确实给了订单号"的路径不误伤**——特别复核 `ACT-LOG-*`/`ACT-ORD-*`/`ACT-RFD-*` 里带了 `90001`-`9000x` 的历史用例，以及 dev 口径的 180 条评测读数不因此退化。
5. **不许用黑名单**（把 `10023` 加进拒绝集）：语义错误，换个示例值即失效。
6. JVM 用例：新增"模型自报的 orderNo 不在对话里 → 转 slot_ask 而非派发"的确定性用例（0 token，Mockito 桩喂一个伪造单号）；并加一条正对照"query 里出现过的 orderNo → 照常派发"（否则守卫很容易写成"一律拒掉"）。
7. **变异对照**：把溯源判定摘掉 → 新用例必须红；恢复即绿。
8. 活体：`pwsh -NoProfile -File scripts/verify-action-loop.ps1` 由 8 PASS / 3 FAIL 转为全过（修前红 / 修后绿两组读数）。
9. schema 描述对称化（把 `QueryOrderDetailRequest` 那句「用户未提供时必须追问而非猜测」补到另外三个请求）**只能作为辅助**，且必须在 Handoff 里写清它**不构成收口证据**——prompt 级劝阻对 3B 的有效性未证，`ACT-LOG-12` 要的是行为保证。改描述时要保证 `ToolSchemaGeneratorTest.java:50` 的 `contains("10023")` 断言仍成立。

## 收口状态（2026-09-27）

四张票全部实现并各自留 Handoff。**全量 22 步矩阵复测：805 s、20 步绿 / 2 步红**（落点 `logs/acceptance-run-20260927-161245.log`）——四条红里 **`action` 与 `emotion` 转绿**，剩下两步正是本轮登记为"间歇"与"已知不达成"的那两个。JVM `3 + 21 + 276 = 300` 绿（gateway 269 → 276）。

| 票 | 状态 | 关键落点 |
|---|---|---|
| 53 `feedback` 读数与刺激 | implemented | 加 `Get-Implied` 助手消掉三参数调用；`negative` 刺激换成 `转人工`；退款改用拥有者 C001 + 幂等键按进程取；四次连跑 3×`6/1` + 1×`7/0`——`implied_retry` 间歇，机制见票面 |
| 54 `emotion` 用例重分类 | implemented | `EMO-ESC-02` 移入"显式转人工"（`USER_REQUESTED` + 不带 high）；新增 `EMO-ESC-09` 补位；**并修掉一处中止**（队列反查缺 bearer → 401 → 中止，20 条断言从未运行）→ `PASS 30 / FAIL 0` |
| 55 `plansteps` 登记更正 | implemented | 删掉不存在的 `PlanExpressionTest` 引用（全仓仅此一处）；`aborted` 生命周期语义；补取证口径；该步**判据一字不改**、local 档登记为已知不达成 |
| 56 `orderNo` 溯源守卫 | implemented | `isUntrustedOrderNo`（格式 + 溯源）；判据面 = 买家说过的话；`verify-action-loop.ps1` 8 PASS/3 FAIL → **11/11** |

**四步红现在的性质（都已如实登记，没有一条是"看着红了就改判据"）**

- **`action` 转绿**：票 56 的行为守卫接管——模型编的 `10023` 被转成 `[orderNo]` 追问。这条同时是 gold 未达成（`ACT-LOG-12`），**gold 一个字没改**。
- **`emotion` 转绿**：票 54 让用例追上 ADR 0042；顺带发现该步此前**跑到第 9 条就因 401 中止**，20 条断言从未执行。
- **`feedback` 仍红（6 PASS / 1 FAIL）**：`implied_retry` 间歇——四次连跑 3 红 1 绿，红的那几次 SSE trace 显示模型在含上一轮成功答复的会话里**不肯再发工具调用**，重复请求走不到幂等层。绿的那次计数 `0 → 1` 证明幂等层本身正确。**按登记处置、不修代码去迎合脚本**（登记节第 5 项另有它引出的新发现）。
- **`plansteps` 仍红（0 / 7）**：local 3B 不产生两步链。**判据一字不改**——ADR 0043 明令禁止"把判据改窄去适配实现"。

**CI 五步门禁（本机读数，2026-09-27）**

| 步 | 读数 |
|---|---|
| 1 构建与 JVM 测试 | `3 + 21 + 276 = 300` 绿 |
| 2 判据自检 | `合计 40/40 通过`（票 54 改了 part4，判据未动，自检不受影响） |
| 3 离线 rescore | `RESCORE DONE cases=180 files=6 tool_diff=4` —— **差异集仍恰好那 4 条**，即票 56 的行为守卫没有扰动离线判据（rescore 走的是判据而非网关） |
| 4 套件夹具 | `SUITE SELFCHECK ok=24`（判分器未改） |
| 5 覆盖率棘轮 | `gateway 58.52% / biz-mock 77.49% / tool-api 41.73%` 对门槛 `54.0 / 76.0 / 40.0`，`COVERAGE OK` |

**本轮未覆盖 / 未达成（照登，不摘）**

1. **`ACTION_REFUND` 的 dev 回归没跑**：日预算在跑到第四条时不足（`232140/260000`，该条需 48366）。所以**不得声称"四个动作意图都不退化"**——只覆盖了 ORDER / LOGISTICS / ADDRESS 三条。
2. **`plansteps` 在 local 档仍红**（登记为已知不达成）。
3. **`feedback` 的 `implied_retry` 间歇**（登记为模型行为边界）。
4. **schema 描述对称化未做**（可选辅助，不构成收口证据）。
5. **矩阵耗时 805 s**，比 2026-09-24 的 512 s 长——主要是 `emotion` 步现在真的跑完 30 条（91 s vs 此前中止在 9 条）、`plan` 步 101 s、`eval` 步 105 s。**这不是判据变了，是原本被中止的断言开始跑了。**

## 冻结与收口

- 本轮结束后回到 ADR 0031 冻结机制，**不自动续期**。
- 四条票独立可交付；若只交付票 53/54/55 而票 56 未动，项目仍自洽（`action` 继续红，红的原因已如实登记）。
- **门禁变绿不等于系统变好**：票 53/54 把"恒定失败的读数"修成"真的在测量"，所以本 spec 强制要求**修前红 / 修后绿两组活体读数**。只给修后绿的一律不算收口。
- 本机三条硬限制（见 `docs/EVIDENCE.md`）：TIME_WAIT 占满动态端口 80%（反复出站 HTTPS 撞 `WinError 10048`）、主机内存剩 2.0 GB（网关启动期原生 OOM 风险）、四套项目 17 容器争 4 GB 显存。**跑活体验收前先看这三样。**

## 禁面核对（ADR 0023）

| 本轮要改 | 是否禁面 | 依据 |
|---|---|---|
| `scripts/verify-feedback.ps1`、`verify-emotion.ps1`、`verify-plan.ps1` | **否** | 禁面只含 gold 三文件（part1-3）、`eval/results/` 的改删、顶层 `knowledge/`、`scripts/verify_eval_judge.py`、本轮起点已在库的旧 ADR。三支 `verify-*.ps1` 都不在其中 |
| `eval/cases-part4-emotion.jsonl` | **否，但受"只增不改"纪律** | part4-7 不在 B7 的内容级禁面里；本轮按 add-only 处理（新增 case 为主，`EMO-ESC-02` 行的改动要在 Handoff 里单列理由） |
| `scripts/eval_suites.py` | 否 | 不在禁面；但它承载情绪套件的判据，改动要同步其 24 条夹具 |
| `eval/cases-part2-action.jsonl` | **是（禁面）** | 它在 `GOLD_CASE_FILES` 里。**票 56 因此仅复核不改动**——`ACT-LOG-12` 已逐字写明期望，修代码让它绿，而不是改它 |
| 被测 Java（`ToolDispatcher`、`AgentStateMachine`、`tool/request/*`） | 否 | 不在禁面 |
| `docs/adr/0045-*.md`（新增） | 否 | 新写的 ADR 不在 `prior_adrs`（该集合取自 `ROUND_FP:docs/adr`） |
| `CONTEXT.md`、`docs/EVIDENCE.md`、`docs/CODE_MAP.md`、`README.md`、`.scratch/` | 否 | 同上 |

## 登记不执行

1. **`plansteps` 在 local 档的两步链**：登记为已知不达成（票 55 第 4 条）。要它绿得换 dev/云端模型，或另开一轮讨论"local 档的 Plan 判据该长什么样"——那是判据决策，不在本轮。
2. **`feedback` 三条隐式计数在 3B 下的可得性**：若修完读数后 `implied_retry` 仍不增，登记为模型能力边界（`applyRefund` 是否两次都发得出）。**不做代码侧适配。**
3. **`action` 的 schema 描述对称化单独立项**：本轮把它降为票 56 的辅助手段；若最终证明它对 3B 无效，那"prompt 级劝阻压不住照抄"本身就是一条值得登记的结论。
4. **round19 登记过的八项**（退款终态、工单回流、审批闸门、task 级判据、CI 覆盖活体、拦截率裁决、输入侧裁剪、知识反向沉淀）**继续只登记不执行**，触发条件不变。
5. **同 token 重试可能拿到空答案而不是幂等回放（票 53 取证时发现，候票 57）**：客户端用**同一个 `idempotencyToken`** 重试时，重复检测在 `ToolDispatcher.dispatch` 里，只有模型**真的再发一次工具调用**才会被执行。若模型不发（本地 3B 在已含上一轮成功答复的会话里就不发，见票 53 的 SSE trace：`PLAN round=0` → `corrective=applyRefund` → `PLAN round=0` → `done`，`completionTokens=1`、`answer` 为空、`plan` 为空、无 fallback），请求就走到 `done` 而答案是空的——**既没有回放、也没有降级话术**。我的探针 **3/3 复现**。这把「幂等」这条承诺的可靠性挂在了模型行为上，而 `idempotencyToken` 的存在意义恰是"客户端重试不该重复执行、应回放"。**要立项的决策点是判重放的时机**：放在模型之前就等于"只看 token、不看用户说了什么"（会误伤"用户换了要求但客户端复用旧 token"），放在模型之后就是现状。不在本轮拍。
6. **溯源面不含前步工具结果（票 56 实施时的边界，带触发条件）**：`isUntrustedOrderNo` 的判据面是"买家说过的话"，**不含同一次请求里前一步工具返回的结果**。今天这样够用——四个工具都不会返回「别的订单」，计划链后步引用的单号本来就是买家报给前步的那个值，所以在买家话里找得到（`PlanExecutionTest` 的两步链用例就是这种形状）。**触发条件 = 加入 `queryOrderList` 这类一次返回多单的工具**：那时后步可能引用一个买家从没报过的单号，必须把前步结果纳入判据面，否则计划链会被误拦。已写在 `isUntrustedOrderNo` 的 javadoc 里（代码里能看见，不必只靠这份文档）。
7. **票 56 的 gold 回归只覆盖了三个动作意图**：`ACTION_REFUND` 因日预算不足未跑（`日预算 232140/260000` 时第四条需 48366）。触发条件 = 下一次有预算时补跑；在那之前**不得声称"四个动作意图都不退化"**。
