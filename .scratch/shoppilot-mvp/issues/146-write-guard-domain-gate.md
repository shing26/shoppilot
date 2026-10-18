# 146 写动作守卫补行业门（R11）

**Status:** ready-for-agent

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

（解决时补：关键决策、验证落点、三个现场追问；并在 `registered-debt.md` 把 R11 挪到「已关闭」。）
