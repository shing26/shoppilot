# 146 写动作守卫补行业门（R11）

**Status:** implemented（2026-10-19）

**Claimed by:** zcode implement session 2026-10-19（round35 实现轮：145 + 146；145 同轮收口 R12）

## What to build

把「写动作守卫（票 41 纠偏）不吃行业门」这个已登记缺陷修掉：非电商租户被 T1 判成
`ACTION_REFUND` / `ACTION_ADDRESS` 时，模型没有可调工具，纠偏不应再触发一枪并给模型
「直接调用 applyRefund 把它真的办掉」的指令（与 prompt 第 7 条「无工具时照实说明办不了」冲突，
存在诱导谎报「已提交」的风险）。

可独立验证的行为：酒店 / 未登记租户的办理类诉求里，`shoppilot_write_nudge_total` 不增、不追加模型调用，
模型按「照实说明办不了」作答；电商租户的纠偏行为逐字不变。

- 纠偏条件加 `domain == Domain.ECOMMERCE`（或按 `toolsFor` 非空判——取实现上不重复判定的那一处）。
- 补一条 HOTEL 级 JVM 用例（当前 `AgentStateMachine` 无酒店级纠偏用例）。

## Blocked by

None — can start immediately（建议排在 [144](144-reflection-branch.md) 之后：与它同文件，减少改动冲突）。

## 口径

- 依据：**ADR 0031 第 10 行的事实性修正 / 门禁一致性豁免**——同一道行业门在 `toolsFor` 与网关派生
  两处已吃，守卫漏了同一道；这是漏吃，不是新功能（不占政策覆盖额度）。
- 登记出处：`registered-debt.md` R11（137 的代码核实，未活体复现）。
- 变异对照：去掉 `domain` 门 → 新用例转红；还原即绿。
- 不改提示词（141 已裁定 R11 不在提示词票内）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
git diff --check; git status --short
```

## Handoff notes

**关键决策**：

1. **门开在源头那一处**（票面给的二选一：加 `domain == Domain.ECOMMERCE` 或按 `toolsFor` 非空判）：
   `ToolName expectedWrite = domain == Domain.ECOMMERCE ? expectedWriteTool(intent) : null;`。
   理由：`toolsFor` 与本格读的是同一个 `domain`，在两处各判一次等于把同一条规则写两遍、以后漂移；
   而 `expectedWrite` 下游有**两个**消费者——纠偏一枪（票 41）与「写挂起但轮次用尽」的
   `TOOL_ROUNDS_EXHAUSTED` 分支。后者同样以「守卫能真去调那个工具」为前提，非电商租户置空后
   它也不再触发（本来也调不到），一处改动同时收口两条路径。
2. **电商行为逐字不变**：门的另一边原样保留（同一句「我这就为您提交退款申请」照旧纠偏一次，
   指令文本、`corrective=` trace 行、`shoppilot_write_nudge_total` 计数都不动）。
3. **用法判据的位置没动**：`askSlot` / 网关派生那两处的行业门是早就有的，本票一行未碰
   （ADR 0067 的前置裁剪；票 146 补的是守卫这一格漏吃）。

**验证落点**：

- 新用例两格（`PlanExecutionTest`，0 token）：
  `hotelTenantPromisingARefundIsNotNudged`（HOTEL：规划请求 `tools` 为空、`complete` 只调 1 次、
  `shoppilot_write_nudge_total` 为 0、无 `corrective=` 行、模型原话原样回给买家）与
  `ecommerceTenantPromisingARefundIsStillNudged`（正对照：`tools` 非空、2 次 `complete`、
  第二次消息里含 `applyRefund` 的纠偏指令、计数为 1）。
- **变异对照实测**：把 `expectedWrite` 那行换成不含 domain 门的写法 → `hotelTenantPromisingARefundIsNotNudged`
  **恰 1 条红**；还原即绿（7/7）。记录见本条提交信息。
- 全量 `.\mvnw.cmd -B -ntp verify` 四模块 **`10 + 73 + 446 + 45 = 574`** 全绿（gateway 444 → 446 = 本票两格）。
- 台账：`registered-debt.md` 的 **R11 已挪「已关闭」**；同批把 **R12** 一并挪档（它已被票 145 修掉，
  条目原文「响应字段本身未修，只登记不执行」已成假陈述）——票 150 收口时只做复核，不必再挪一次。

**三个现场追问**：

1. 为什么不去改 `expectedWriteTool(intent)` 本身、给它加 domain 参数？——它是**意图 → 写工具**的纯映射
   （无 domain 概念，凭据是 `Intent`），把行业塞进去会让这个纯函数多一个与它无关的输入；
   行业门是网关侧的裁剪，属于调用点。
2. 酒店租户的「我要退款」这一轮现在走哪条路？——照旧进工具循环但 `tools` 为空：模型只能文字作答
   （按 prompt 第 7 条「无工具时照实说明办不了」），不纠偏、不派生（网关派生本来就有同一道门）、
   不落工单；买家拿到的是照实说明，而不是被诱导出来的「已提交」。
3. 为什么正对照也要写成用例？——这条债的病是「**漏吃同一道门**」：只加一条酒店红用例，
   有人把门开成 `domain == Domain.HOTEL ? null : ...`（或干脆恒 null）也能全绿，而电商的纠偏
   （票 41 花过成本才立起来的那道守卫）会静默死掉。两条一起钉，变异才有意义。
