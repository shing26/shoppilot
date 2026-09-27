# 56 — `orderNo` 溯源守卫：模型自报的订单号必须出自本轮对话

**What to build:** 补上"模型自己编了个格式合法的订单号"这一道防线。现状唯一守卫 `ToolDispatcher.isFabricatedOrderNo`（`:116-118`）只校验格式（`ORDER_NO = \d{1,12}`），所以本地 3B 照抄工具 schema 描述里的示例值 `10023` 时拦不住，工具被真的派发出去撞库——而这条行为**同时也是 gold 未达成**。

**Blocked by:** None。

**Status:** implemented（2026-09-27）。

**依据：所有者政策覆盖**（新功能行为——新增一层参数来源判定，不是"修既有门禁"）。ADR 0045 已记录为覆盖，不适用 ADR 0031 触发条件。

口径（ADR 0045 已定，本票只执行）：

- **这条红同时也是 gold 未达成**：`eval/cases-part2-action.jsonl:30` 的 `ACT-LOG-12` 逐字要求 `"slotAsk": true, "mustNotContainArgs": ["orderNo"]`（同类还有 `ACT-LOG-11`、`ACT-ORD-11/12`）。所以**修代码让它绿，而不是改 gold 去适应代码**——**该文件是内容级禁面，本票只复核不改动**。
- **现状没有第二道**：`deriveActionCall` 的入口条件是 `rounds == 0 && !toolUsed`（`AgentStateMachine.java:405-408`），模型一发工具就整体跳过；`ToolDispatcher.dispatch(call, token)` 的签名里没有 `query`，拿不到"用户说过什么"。
- **什么算"出自本轮对话"必须先定**：范围要覆盖当前 query、本会话历史轮次、以及**续办轮**买家补充的 query（`askSlot` 之后 `resumePending` 的路径 `AgentStateMachine.java:591-602`，`extractSlots` 能取到）——漏掉续办就会误伤补充流程。
- **命中时的行为是 `slot_ask` 追问，不是 FALLBACK**：复用既有 `missingSlots = ["orderNo"]` → `askSlot` 出口（`:324-329` 的 fabricated 分支就是现成形态），不新增出口、不动 10 状态枚举。
- **叠加而不是替换**：格式校验继续拦明显不合格式的值，新增的溯源校验拦"格式合法但不出自对话"的值。
- **不许用黑名单**（把 `10023` 加进拒绝集）：语义错误，换个示例值即失效，且把"防编造"退化成"防一个已知字符串"。
- **schema 描述对称化只能作辅助**：把 `QueryOrderDetailRequest.java:6` 那句「用户未提供时必须追问而非猜测」补到另外三个请求上，是顺手的改善，但**不构成收口证据**——prompt 级劝阻对 3B 的有效性未证，`ACT-LOG-12` 要的是行为保证。改描述要保证 `ToolSchemaGeneratorTest.java:50` 的 `contains("10023")` 仍成立。

- [x] Handoff 里先写清"出自本轮对话"的判定范围（含续办轮），再动代码
- [x] 守卫落在**派发之前**，对 `isFabricatedOrderNo` 是叠加（改名为 `isUntrustedOrderNo`：格式 + 溯源两道）
- [x] 命中时走既有 `missingSlots=["orderNo"]` → `askSlot`，不新增出口、不动 10 状态枚举
- [x] JVM 用例：`ToolDispatcherOrderNoProvenanceTest` 4 条（格式非法仍拦 / 格式合法但没说过被拦 / 正对照 / 历史买家轮次算）
- [x] **正对照**：`orderNoFromTheCurrentQueryIsTrusted` + 既有的端到端工具循环用例（query 含 `90001`、照常派发）
- [x] **续办用例**：`GatewayMainPathJvmTest.resumedTurnTrustsTheOrderNoTheBuyerJustSupplied`（pending 会话 + 本轮补单号 → 照常派发）
- [x] **变异对照**：把溯源那行改成 `return false` → **3 条红**（1 单元 + 2 端到端），恢复即绿
- [x] dev 口径动作意图评测不退化：跑了 `ACTION_ORDER` / `ACTION_LOGISTICS` / `ACTION_ADDRESS`（54 条）——**`ACTION_REFUND` 因日预算不足未跑**（见 Handoff，按未覆盖登记）
- [x] 活体：`verify-action-loop.ps1` 由 8 PASS / 3 FAIL 转为 **11/11 PASS、exit 0**（修前 `logs/acceptance/action.log`；修后 `logs/r20-t56-action-after.txt`）
- [x] `.\mvnw.cmd -B -ntp verify` 全绿（gateway 269 → **276**）；`python scripts/check_coverage.py` exit 0（gateway LINE 57.95% → **58.52%**）
- [x] schema 描述对称化**未做**（登记为可选辅助；见 Handoff 说明它为什么不构成收口证据）

## Handoff notes

**"出自本轮对话"的口径（按票面要求，先定后动代码）**

判据面 = **买家说过的话**：本轮 `query` + 会话历史里的**买家轮次**（`SessionStore.Session.turns()` 里 `role=="user"` 的那些），拼成一个字符串传给 `ToolDispatcher`。

三条边界是刻意划的，不是顺手：

- **不含助手回复**。助手可能转述过模型上一次编的单号——把它算进来，模型编一次就能给自己背书，第二道守卫白设。
- **不含检索回来的政策条款**。条款正文里出现数字，不等于买家报过单号。
- **不含前步工具结果**（见下面的"已知边界"）。

**续办路径天然覆盖**，不需要额外接线：`resumePending` 的参数由 `extractSlots(tool, query)` 从**本轮补充的那句话**里取，而那句话就是传入的 `query`；上一轮的原始诉求在 `turns()` 里，也在判据面内。

**关键决策**

- **守卫放在 `ToolDispatcher`，而不是状态机**。它的类 javadoc 本来就写着「缺必填槽位一律交回状态机追问，绝不猜（猜订单号等于拿别人的订单）」——"什么算猜"正是它声明的职责；放状态机等于把同一件事拆到两处（CODE_MAP 已把"两边各持有部分槽位策略"记为待偿的债，不在这轮加剧它）。
- **签名加一个 `dialogue` 参数，并把 `isFabricatedOrderNo` 改名 `isUntrustedOrderNo`**：两道判据（格式 + 溯源）合成一个谓词与一个 `fabricated` 标志，`AgentStateMachine` 那侧一行都不用改——它早就在 `fabricated()` 上做 `askSlot`，走的是既有追问出口。**没有新增出口、没有动 10 状态枚举。**
- **子串匹配，接受一处已知误拒**。买家把单号拆开写（`900-02`）、模型又归一化成 `90002` 时会被判成不可信、多问一次。宁可多问一句也不拿没出处的东西撞库——追问的代价是体验，撞库的代价是越权探测。换成更宽的匹配（例如只比数字串）会让"模型从别处抄个数字"重新可行，得不偿失。
- **判据面不含前步工具结果，而且这是个有触发条件的边界**：今天够用——四个工具都不返回「别的订单」，计划链后步引用的单号本来就是买家报给前步的那个值，所以在买家话里找得到。**若将来加入 `queryOrderList` 这类一次返回多单的工具**，后步就可能引用买家从没报过的单号，那时必须把前步结果一并纳入判据面，否则计划链会被误拦。写进了 `isUntrustedOrderNo` 的 javadoc 与 round20 spec 的登记节。
- **schema 描述对称化没做，也不该当成收口证据**。把那句「用户未提供时必须追问而非猜测」补到另外三个请求上只是 prompt 级劝阻，对 3B 的有效性未证；`ACT-LOG-12` 要的是**行为保证**。这轮用行为守卫拿到保证，描述对称化留作可选清理。
- **两个老用例的 fixture 必须改，改法是补问句里的单号——这一点值得记下来**：`GatewayMainPathJvmTest.multipleToolCallsInOneReplyDispatchOnlyTheFirst` 原先问句是「我的订单到哪了」（**没有单号**）而桩里填 `90001/90002`；`PlanExecutionTest` 的 `futureOrMissingReferenceRejected`（「引用缺失字段」+ `90001`）与 `failedFirstStepAbortsRemainingPlan`（「查不到就别退了」+ `99999`）同样如此。**它们一直在隐式依赖"编造单号会被放行"这条路径**——而 gold 的 `ACT-ORD-11`/`ACT-LOG-11` 明确要求这种问法必须追问。所以改 fixture 是让它们回到各自要测的东西（多调用防御 / 表达式拒收 / 前步失败中止），不是放宽。
- **`ACT-ORD-13` 的 True→False 不是本守卫造成的，两条独立理由**：① **结构上不可能**——守卫跑在模型返回之后，只能把"派发"改成"追问"，改变不了模型选哪个工具；该行实测 `actual_tool=queryOrderDetail` 且答案里有「当前订单90001状态为…」，说明工具**真的派发了**。② 这条正是 README 早已登记的边界样本「改地址+问状态双诉求只办了后者」，属模型在双诉求上的选取抖动（且 `90001` 就在问句里、根本不触发守卫）。

**验证落点**

- **单元**（0 token）：`ToolDispatcherOrderNoProvenanceTest` 4 条——格式非法仍拦、格式合法但没说过被拦、正对照（问句里有 → 照常派发）、历史买家轮次算。
- **端到端**（0 token）：`GatewayMainPathJvmTest` 新增 3 条——编造单号转 `slot_ask` 且零派发、单号只出现在**助手**旧回复里仍被拦（这条证明"不自证"）、续办轮补单号后照常派发。
- **变异对照**：把 `return dialogue == null || !dialogue.contains(value);` 改成 `return false;` → **3 条红**（1 单元 + 2 端到端），恢复即绿。
- **全量**：`.\mvnw.cmd -B -ntp verify` → gateway 269 → **276 绿**；`check_coverage.py` → `COVERAGE OK`（gateway LINE 57.95% → **58.52%**）。
- **活体**：`verify-action-loop.ps1` **11/11 PASS、exit 0**（修前 8 PASS / 3 FAIL：三条 `slot_ask` 断言全红的正是第 2 段"不给单号"）。落点 `logs/r20-t56-action-after.txt`（本机日志不入库）。
- **gold 回归（部分）**：dev 档跑 `ACTION_ORDER` / `ACTION_LOGISTICS` / `ACTION_ADDRESS` 共 54 条——选对工具 94.4% / 94.4% / 填对参数 93.3%，与 09-10 重标后的同档读数持平；**守卫的真实效果在三条缺槽位用例上**（`ACT-ORD-11`/`ACT-LOG-11`/`ACT-LOG-12`，问句里没有单号）：模型编了一个、被守卫转成 `[orderNo]` 追问，**用户可见答案与 09-10 基线逐字相同**（都是「请提供您的订单号（例如 10023）…」），只是现在即使模型编造也保证如此。
- **未覆盖**：`ACTION_REFUND` **没跑**——日预算在跑到第四条时不足（`日预算 232140/260000，剩余 27860 < 本轮预计 48366`，每条约 50-66k，比预估的 36k 高，因为 dev 档每请求多一跳情绪分类、动作类 prompt 也更大）。按未覆盖登记，**不得声称"四个动作意图都不退化"**。

**你需要能当场回答的三个追问**

1. *Q：加一道溯源，会不会把"买家确实报了单号"的正常路径也拦掉？* A：不会——判据是"这个值在买家说过的话里出现过"，正对照用例就钉这条；续办路径另有专门用例（买家在追问后补单号那一轮照常派发）。**已知会误拒一种**：买家把单号拆开写（`900-02`）而模型归一化成 `90002`，这时会多问一次；我把这个代价明写在 javadoc 里并选了它，理由是追问只损失体验、撞库是越权探测。
2. *Q：`10023` 只是个示例值，为什么不用黑名单？* A：黑名单语义就是错的——`10023` 格式合法，问题不在它"是哪个数"，而在它**没人报过**。黑名单换个示例值立刻失效，而且把"防编造"退化成"防一个已知字符串"。溯源判的是来源，不是取值。
3. *Q：这条红在 gold 里也有对应，为什么说是"修代码"而不是"改判据"？* A：因为 `eval/cases-part2-action.jsonl` 的 `ACT-LOG-12` **本来就写着** `"slotAsk": true` 且 `"mustNotContainArgs": ["orderNo"]`——判据没动，是代码没做到。修完那三条缺槽位用例的行为与 gold 对齐，gold 一个字没改（它还是内容级禁面，本票只复核不改动）。
