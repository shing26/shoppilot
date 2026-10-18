# 147 提示词换代：v1.1.0 → v1.2.0

**Status:** ready-for-agent

## What to build

把 agent 系统提示词换代到 **v1.2.0**，实质改动只有一处：**第 4 条尾部扩写一句**
（「如果系统随后给了失败上下文，换一种做法（换工具或换参数）再试一次，仍然失败就照实说明并建议转人工。」），
其余 10 条原文不动、仍保持 **11 条**。可独立验证的行为：`PromptCatalog` 加载 v1.2.0、
规则数仍 11、SSE `meta` 与评测报告的 `promptVersion` 全部变成 `v1.2.0`。

- 新版本文件 `prompts/agent-system/v1.2.0.md`（从 v1.1.0 复制 + 第 4 条尾句扩写）；`meta.json` 指向 v1.2.0。
- **历史版本保留**（v1.0.0 / v1.1.0 不删，ADR 0037 家法）。
- **四处钉子同步**：`PromptCatalogTest`（版本 + **11 条规则数** + 首尾锚点）、`GatewayMainPathJvmTest` 的
  `promptVersion` 断言、`ChannelFlowJvmTest` 的两处 `promptVersion` 断言。

## Blocked by

None — can start immediately（内容已在 141 裁定，落地即版本三件 + 钉子）。

## 口径

- 依据：[ADR 0069](../../../docs/adr/0069-round35-agent-autonomy-upgrade-reopen-basis.md) §五；141 的裁定是唯一口径。
- **预算数字不进提示词**（`SHOPPILOT_AGENT_MAX_TOOL_ROUNDS` 是唯一源；第 9 条「轮次有限」原文不动）。
- **不教失败分类**（①②③不进提示词）、不写工具名、不加新条。
- 不动：第 3 条纯咨询兜底、第 7 条不猜不骗、第 10 条复述合并结果、第 11 条 200 字上限。
- 评测读数按 v1.2.0 归因（与 v1.1.0 不可混比）——与票 149 同轮。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
git diff --check; git status --short
```

## Handoff notes

（解决时补：关键决策、验证落点、三个现场追问）
