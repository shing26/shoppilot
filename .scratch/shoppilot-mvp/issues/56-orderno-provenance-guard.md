# 56 — `orderNo` 溯源守卫：模型自报的订单号必须出自本轮对话

**What to build:** 补上"模型自己编了个格式合法的订单号"这一道防线。现状唯一守卫 `ToolDispatcher.isFabricatedOrderNo`（`:116-118`）只校验格式（`ORDER_NO = \d{1,12}`），所以本地 3B 照抄工具 schema 描述里的示例值 `10023` 时拦不住，工具被真的派发出去撞库——而这条行为**同时也是 gold 未达成**。

**Blocked by:** None。

**Status:** ready-for-agent（2026-09-25）。

**依据：所有者政策覆盖**（新功能行为——新增一层参数来源判定，不是"修既有门禁"）。ADR 0045 已记录为覆盖，不适用 ADR 0031 触发条件。

口径（ADR 0045 已定，本票只执行）：

- **这条红同时也是 gold 未达成**：`eval/cases-part2-action.jsonl:30` 的 `ACT-LOG-12` 逐字要求 `"slotAsk": true, "mustNotContainArgs": ["orderNo"]`（同类还有 `ACT-LOG-11`、`ACT-ORD-11/12`）。所以**修代码让它绿，而不是改 gold 去适应代码**——**该文件是内容级禁面，本票只复核不改动**。
- **现状没有第二道**：`deriveActionCall` 的入口条件是 `rounds == 0 && !toolUsed`（`AgentStateMachine.java:405-408`），模型一发工具就整体跳过；`ToolDispatcher.dispatch(call, token)` 的签名里没有 `query`，拿不到"用户说过什么"。
- **什么算"出自本轮对话"必须先定**：范围要覆盖当前 query、本会话历史轮次、以及**续办轮**买家补充的 query（`askSlot` 之后 `resumePending` 的路径 `AgentStateMachine.java:591-602`，`extractSlots` 能取到）——漏掉续办就会误伤补充流程。
- **命中时的行为是 `slot_ask` 追问，不是 FALLBACK**：复用既有 `missingSlots = ["orderNo"]` → `askSlot` 出口（`:324-329` 的 fabricated 分支就是现成形态），不新增出口、不动 10 状态枚举。
- **叠加而不是替换**：格式校验继续拦明显不合格式的值，新增的溯源校验拦"格式合法但不出自对话"的值。
- **不许用黑名单**（把 `10023` 加进拒绝集）：语义错误，换个示例值即失效，且把"防编造"退化成"防一个已知字符串"。
- **schema 描述对称化只能作辅助**：把 `QueryOrderDetailRequest.java:6` 那句「用户未提供时必须追问而非猜测」补到另外三个请求上，是顺手的改善，但**不构成收口证据**——prompt 级劝阻对 3B 的有效性未证，`ACT-LOG-12` 要的是行为保证。改描述要保证 `ToolSchemaGeneratorTest.java:50` 的 `contains("10023")` 仍成立。

- [ ] Handoff 里先写清"出自本轮对话"的判定范围（含续办轮），再动代码
- [ ] 守卫落在**派发之前**，对 `isFabricatedOrderNo` 是叠加
- [ ] 命中时走既有 `missingSlots=["orderNo"]` → `askSlot`，不新增出口
- [ ] JVM 用例：桩喂一个伪造（格式合法但不在对话里）的 orderNo → 断言转 `slot_ask`、不派发（0 token）
- [ ] **正对照**：query 里出现过的 orderNo → 照常派发（否则守卫容易写成"一律拒掉"）
- [ ] **续办用例**：`askSlot` 之后买家补充单号的那一轮 → 照常派发（防误伤 `resumePending`）
- [ ] **变异对照**：摘掉溯源判定 → 新用例必须红，恢复即绿
- [ ] dev 口径 180 条评测读数不退化（逐条比，或至少分意图不降）
- [ ] 活体：`verify-action-loop.ps1` 由 8 PASS / 3 FAIL 转为全过（**修前红 / 修后绿两组读数**）
- [ ] `.\mvnw.cmd -B -ntp verify` 全绿；`python scripts/check_coverage.py` exit 0（新增 gateway 代码要带用例，门槛 54.0）
- [ ] schema 描述对称化若做了，在 Handoff 里注明它是辅助、不构成收口证据

**Verify:**

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/check_coverage.py
pwsh -NoProfile -File scripts/verify-action-loop.ps1
```

预期：`verify-action-loop.ps1` 11 条断言全过；全量 JVM 与覆盖率不退化。

## Handoff notes

（收口时补：关键决策、验证落点、三个现场追问）
