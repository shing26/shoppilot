# 147 提示词换代：v1.1.0 → v1.2.0

**Status:** implemented（2026-10-19）
**Claimed by:** zcode session 2026-10-19（round35 实现轮：先 147 后 148）

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

**关键决策**：

1. **版本三件照 141 的裁定落地**：`v1.2.0.md` = `v1.1.0.md` **逐字节复制 + 第 4 条尾句一句**（其余 10 条一字未动），
   `meta.json` → `v1.2.0`；**历史版本 v1.0.0 / v1.1.0 只增不删**（ADR 0037 家法），`PromptCatalog` 的 fail-fast 机制未动。
   加的那句：**「如果系统随后给了失败上下文，换一种做法（换工具或换参数）再试一次，仍然失败就照实说明并建议转人工。」**
2. **新增一条钉子（票面的四处之外的第 5 处，落点在同一份 `PromptCatalogTest`）**：
   `v12DiffersFromV11ByRuleFourOnly` —— 以 **v1.1.0 正文文件作对照基线**，机器证明「v1.2.0 与 v1.1.0 的差异恰好只有这一句」，
   同时断言正文里**不含「4 轮」/「4 步」**（预算数字不进提示词：配置是唯一源）。
   这一条把票面口径里此前只能靠人读 diff 的那半句（「其余 10 条原文不动」）变成了机器可查，
   并顺带钉住「历史版本文件必须还在」——没有 v1.1.0 就没有对照基线。
3. **四处钉子同步**：`PromptCatalogTest`（版本 + 11 条规则数 + 首尾锚点）、`GatewayMainPathJvmTest:501`、
   `ChannelFlowJvmTest:83/99`。**`ChannelFlowJvmTest:140` 的 `"v1.0.0"` 未动**——那是另一条用例自造的合成值
   （验回执工单复用），不是生产资源派生的版本，不在钉子清单里。
4. **不动的事**（防漂移）：第 3 条纯咨询兜底、第 7 条不猜不骗、第 9 条「轮次有限」、第 10 条复述、第 11 条 200 字上限；
   不教①②③分类、不写工具名、不加新条（仍 11 条）；R11 不在本票。

**验证落点**：

- 定向：`.\mvnw.cmd -B -ntp -pl shoppilot-gateway -am -Dtest='PromptCatalogTest,ChannelFlowJvmTest,GatewayMainPathJvmTest' -Dsurefire.failIfNoSpecifiedTests=false test`
  → **40 条全绿**（PromptCatalogTest 7 / ChannelFlowJvmTest 5 / GatewayMainPathJvmTest 28）。
- **变异对照实测为真**：把 v1.2.0 里那句删掉 → `v12DiffersFromV11ByRuleFourOnly` 红
  （`expected: <true> but was: <false>`，走进 `assertTrue(current.contains(tail))`），还原即绿。
- 全量：`.\mvnw.cmd -B -ntp verify` → **BUILD SUCCESS**；`git diff --check` / `git status --short` 干净。
  **口径照登**：这次 verify 的工作树里同时有**并行在途的票 143**（配置 4 轮）的改动，
  故四模块读数为 `10 + 73 + 426 + 45 = 554`（基线 424 上：票 143 的默认值断言 1 + 本票 1）；
  **本票自身的判据不依赖票 143 的改动**（提示词资产与钉子都是独立文件/独立行）。
- **一条本机现象**：首次 `verify` 在 gateway 的 `repackage` 失败（`Unable to rename ... .jar.original`）——
  根因是**有一个运行中的网关进程锁着 fat jar**（PID 35076，`java -jar D:\ShopPilot\shoppilot-gateway/target/...jar`）；
  按 `scripts/stop.ps1` 停掉 8082/8091 后复跑即绿。下次遇到同样报错先停栈，不要在仓库里找代码原因。

**三个现场追问**：

1. 为什么只改一句、却要新版本文件？——提示词是**归因单位**：`promptVersion` 随 SSE meta 与评测报告分发，
   改了字不改版本号，旧读数就变成了「不知道测的是哪一版」（ADR 0037）。所以历史版本只增不删、改动必须换代。
2. 为什么第五处钉子要拿 v1.1.0 当基线？——「只改了这一句」是本票的核心口径，diff 才是它的证据；
   把 v1.1.0 留成对照基线，等于把「有人顺手多改一条」变成一条会红的用例（多改一条即红）。
3. 评测读数怎么归因？——`meta.json` 切到 v1.2.0 后，SSE `meta` 与报告里的 `promptVersion` 自动变 v1.2.0；
   **v1.2.0 的读数与 v1.1.0 不可混比**（票 140 的实测基线标的是 v1.2.0，票 149 的活体跑批同轮）。
