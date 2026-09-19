# 风格引擎 — StyleService：基座 + 注入段拼装，不做二次改写（ADR 0038）

> round17 spec 票据表里的无编号票（"随票 35 完成即开工"）；**票号 39 属于 Plan 有序步骤（ADR 0036），不要挪用**。

**What to build:** 按 ADR 0038 新增 `style` 包：`StyleService` 在 REPLY 前把「channel × emotion × intent」三元组映射为档位（FORMAL / FRIENDLY / CONCISE，配置资产 `style/profiles.yml`，改档位不改代码），档位映射为一段注入提示词拼在票 35 的版本化基座之后——回答正文仍由同一次 LLM 调用产出，不做回答后处理改写、不二次调用。启动期校验注入段 ≤200 字（中文 token 保守代理）且不含数字（业务事实只能来自检索或工具结果）；降级话术随档位联动（FRIENDLY 在话术前加安抚短句，来自配置）；SSE meta 增 `style` 字段，与 `promptVersion` 共同构成提示词形态归因；`shoppilot_style_applied_total{style}` 计数。UNCERTAIN 情绪一律回落 FORMAL（不猜不讨好）。

**Blocked by:** 票 35（PromptCatalog 基座）、票 36（情绪维度）、票 38（渠道维度）——均已收口。

**Status:** implemented（2026-09-19；全量 JVM `3 + 15 + 244 = 262` 绿，量具 40 PASS，rescore exit 0，CI 见 Handoff）

- [x] `style/profiles.yml` 配置资产：4 条规则（email→FORMAL；ANGRY/URGENT/UNCERTAIN→FORMAL；DISSATISFIED→FRIENDLY；app/miniapp 其余→CONCISE）+ 默认 FORMAL + 三档注入段 + FRIENDLY 降级安抚前缀
- [x] `StyleService`：`tierFor(channel, emotion, intent)`（按序首条命中）、`assemble(base, tier)`、`injection(tier)`、`fallbackPrefix(tier)`；四类坏配置启动期拒绝（注入超限/含数字/未知档位/缺 rules），测试用内存 profiles 驱动
- [x] `AgentStateMachine` 接线：情绪门判定后算档位 → 计数 + `sink.style(tier)` + 拼装 systemPrompt → 随参数穿透到 runModelPath / resumePending / askSlot 与全部 15 个降级出口
- [x] `EventSink.style(String)` 默认方法（SSE 覆盖、NOOP 与测试替身零改动）；`SseEventSink` meta 增 `style` 字段
- [x] 降级话术联动：`fallback()` 组合 `styleService.fallbackPrefix(tier) + reason.userMessage()`（安抚短句进配置资产）
- [x] `StyleServiceTest` 6 项 0 token：part7 六条用例矩阵逐条对齐、UNCERTAIN/URGENT 回落、注入无数字与长度上限、拼装形态、话术前缀来源、四类坏配置
- [x] `GatewayMainPathJvmTest` 集成断言：app 渠道 + CALM → CONCISE，模型请求的 system 消息 = 版本化基座 + CONCISE 注入段（同一次调用），计数落位
- [x] `verify-style.ps1` 活体脚本（SSE meta 档位断言 + email 链路可达；UTF-8 BOM、PS 5.1 语法零错误）
- [x] 全量 `mvnw verify` 绿；`verify_eval_judge.py` 40 PASS；rescore 门禁 exit 0

**Verify:**

```powershell
.\mvnw.cmd -B -ntp verify
python scripts\verify_eval_judge.py
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\check-ps-syntax.ps1
# 活体（需 dev 栈）：
pwsh -NoProfile -File scripts\verify-style.ps1
```

预期：全量三模块 `3 + 15 + 244 = 262` 绿；量具 40 PASS；rescore `tool_diff=4` exit 0；活体脚本 PASS 3 / FAIL 0。

**验收项**

1. 3 渠道 × 2 情绪矩阵档位断言：part7 六条风格用例的三元组逐条对齐（`StyleServiceTest`，0 token 机器断言）。
2. 注入段无业务事实断言：无数字 + ≤200 字，启动期拒绝违规配置（配置侧机器断言）；运行侧"回答里出现业务事实型语句"由评测判据另行把关（不在本票 0 token 范围）。
3. 回答正文仍由同一次 LLM 调用产出：集成断言证明 system 消息 = 基座 + 注入段（不是第二次调用）。
4. 已知口径照登：`intent` 是三元组的保留输入，当前映射表只按渠道与情绪分档——触发条件=出现"同一渠道同一情绪但不同意图需要不同语气"的真实需求；同步响应不带 `style` 字段，归因走 SSE meta 与指标——触发条件=离线评测需要按 sync 响应归因时加字段。

## Handoff notes

**关键决策**

- **档位随参数穿透，不用 ThreadLocal**：档位是单次请求内的一次性计算，随 `runModelPath/resumePending/askSlot` 参数一路传到底（含 15 个降级出口）；ThreadLocal 的收益是省参数，代价是虚拟线程复用下的泄漏面（本仓 TenantContext/ChannelContext 是"由边界显式清理"的既有纪律，这里参数传递更省心也更显式）。
- **`fallbackPrefix` 放配置资产而非 `FallbackReason.userMessage(tier)`**：ADR 0038 的字面写法是给 userMessage 加档位参数；本实现把安抚短句挪进 `profiles.yml` 的 `fallback_prefixes`，与档位表同源——改话术不改代码，行为等价（FRIENDLY 档在话术前加安抚短句）。登记为等效实现。
- **注入段的无事实守卫用"无数字"作为机器代理**：政策结论几乎都带数字（七天/48 小时/比例），数字守卫能挡住最常见的事实注入形态；更强的语义守卫（识别"免费/包退"这类无数字事实）留给定版时的评测判据，避免在配置校验里塞一个半吊子 NLP。
- **`EventSink.style` 用默认方法而不是改接口签名**：档位在状态机内算出、meta 之前回填；只有 SSE 出口需要它，NOOP 与测试替身保持零改动。
- **票号纪律**：本票在 round17 spec 里是无编号票；票 39 属于 Plan 有序步骤（ADR 0036）。不得为对齐索引把两者混编。

**验证落点**

- 全量 `mvnw verify`：`3 + 15 + 244 = 262` 绿（网关 +7：StyleServiceTest 6 + 注入集成 1）。
- 量具 40 PASS；rescore `tool_diff=4` exit 0（本票不改 judge 语义）。
- `verify-style.ps1`：活体验收未跑（本机无 dev 栈），登记为 17 步全量验收边界；脚本 PS 5.1 语法零错误。
- 调试台/既有验收脚本只走 web 端点，行为零变更（既有主链路用例未改语义全部保持通过）。
- CI run 见 Handoff 末行。

**现场追问**

1. *为什么风格用"注入提示词"而不是"回答后处理改写"？* 后处理改写每次回答翻倍模型调用，直接击穿 TTFT 预算，且二次改写是幻觉新增点（ADR 0038 的否决理由）。注入段改变的是同一次生成的约束条件——形态变了、事实来源没变。
2. *为什么 email 恒 FORMAL，哪怕用户在邮件里骂人？* 邮件是书面留档交付：正式函件体对"被投诉"这件事本身也是保护（工单/回执会被人工与合规看）。ANGRY 在邮件里同样走 FORMAL 的"先安抚再结论"注入段，语气克制但不冷淡——规则表一条 `channel: [email] → FORMAL` 表达的就是这个产品判断。
3. *UNCERTAIN 为什么必须回落 FORMAL 而不是按渠道默认？* 风格"宁可不讨好，不可不确定"（ADR 0038）：不确定的用户情绪下用活泼语气是事故风险，用正式语气最坏只是不够亲切。规则表里 UNCERTAIN 与 ANGRY/URGENT 同列，防止未来有人调 app 渠道的映射时把它顺带带偏。
