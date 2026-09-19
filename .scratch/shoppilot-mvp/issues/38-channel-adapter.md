# 38 — ChannelAdapter 三渠道契约：web / webhook 族 / email 归一接入

**What to build:** 按 ADR 0035 新增 `channel` 包：`Channel` 标签（web/app/miniapp/webhook/email）+ `ChannelAdapter` 入站归一契约（统一入参 + 回包能力声明）。实现三个适配器：`WebSseAdapter`（既有 /chat 与 /chat/stream 的形态搬运，行为零变更）、`WebhookAdapter`（app/miniapp/webhook 三个标签共用的整段 JSON 入口，无流式）、`EmailAdapter`（主题并入诉求原文；全链路后结果落回执工单 `reason=EMAIL_REPLY`，无实时回包通道时工单即交付形态）。渠道进入：SSE meta 回显、`shoppilot_rate_limited_total` 增 channel 标签、新计数 `shoppilot_channel_requests_total{channel}`；同步响应与渠道响应携带 channel。**入站准入拆出 `ChatAdmission`**（CODE_MAP 预告的深模块）：四条路径（/chat、/chat/stream、/webhook/{channel}、/email）共用请求计数、渠道计数、限流判定与限流落单，新增准入规则只改一处。

**Blocked by:** 票 34/41（均已收口）。

**Status:** implemented（2026-09-19；全量 JVM `3 + 15 + 237 = 255` 绿，量具 40 PASS，rescore exit 0，CI 见 Handoff）

- [x] `channel` 包：Channel（含 fromPath 校验）、ChannelContext（ThreadLocal，与 TenantContext 同纪律）、ChannelAdapter 接口 + 三实现
- [x] `ChannelController`：`POST /api/v1/support/webhook/{channel}`（仅 app/miniapp/webhook，web/email 与未知标签 400）+ `POST /api/v1/support/email`
- [x] `EmailReceiptWriter`：回执工单落点（reason 固定 EMAIL_REPLY，与降级单可区分；下游不可达如实返回）
- [x] `ChatAdmission` 提取：四条路径共用；`ChatController` 的两条既有路径同步改造（行为保持：429 + Retry-After + X-Fallback-Ticket 语义不变）
- [x] 渠道可观测：SSE meta 增 `channel` 字段（SseEventSink 构造器注入）；`shoppilot_rate_limited_total{dimension,channel}`（键含 channel 防止跨渠道计数串位；/actuator/metrics 按名聚合对既有消费者兼容）；`shoppilot_channel_requests_total{channel}` 计数
- [x] 测试 6 项：webhook 整段 JSON + 计数、无效渠道 400 且不进编排、email 主题归并 + 回执单、email 降级复用已有工单不叠单、限流 429 带渠道、SSE meta 双字段（mock SseEmitter 捕获真实事件体）
- [x] `verify-channel.ps1` 活体验收（三渠道答案一致 / 跨渠道续接与跨买家隔离 / email 回执反查 / 渠道计数；UTF-8 BOM、PS 5.1 语法零错误）
- [x] 全量 `mvnw verify` 绿；`verify_eval_judge.py` 40 PASS；rescore 门禁 exit 0

**Verify:**

```powershell
.\mvnw.cmd -B -ntp verify
python scripts\verify_eval_judge.py
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\check-ps-syntax.ps1
# 活体（需 dev 栈）：
pwsh -NoProfile -File scripts\verify-channel.ps1
```

预期：全量三模块 `3 + 15 + 237 = 255` 绿；量具 40 PASS；rescore `tool_diff=4` exit 0；活体脚本 PASS 6 / FAIL 0。

**验收项**

1. 同一句从 web/app/miniapp 进入答案一致——渠道不进缓存键（ADR 0035 明确否决），同 key 缓存跨渠道共享，活体脚本断言三份答案逐字相同。
2. 跨渠道会话不互串：归属仍是「店铺 + 买家」（ADR 0025），渠道不参与身份推导；同买家跨渠道续接、不同买家同会话 id 隔离。
3. email 全链路落回执工单（reason=EMAIL_REPLY，可按号反查）；答案本身是降级时不叠第二张单。
4. 已知口径照登：`shoppilot_rate_limited_total` 的标签集从 `{dimension}` 变为 `{dimension,channel}`——是按名聚合读取（/actuator/metrics、压测计数器选择器）的兼容变更；按字符串精确匹配 `{dimension="x"}` 的用法会失配，本仓无此类用法（票内已核 verify-plan-actions 与 run_loadtest 的读法）。
5. Webhook/email 无流式（回包能力声明在适配器上）；调试台与既有验收脚本只走 web 端点，行为零变更（既有 3 条主链路 JVM 用例未改一字全部保持通过）。

## Handoff notes

**关键决策**

- **渠道不是隔离边界，只是入站标签**：会话键、缓存键、身份推导都不含 channel（ADR 0025/0035 的既有裁决）；渠道只进三处——meta 回显、限流标签、请求计数。part5 用例的 CROSS 组正是这套语义的验收：同买家跨渠道续接、跨买家同一会话 id 不互串。
- **ChatAdmission 用"提取而非复制"落地**：第四条入站路径出现时，复制准入逻辑到 ChannelController 意味着今后每一条新准入规则都有四处会漏改（CODE_MAP 原话）。提取后四条路径共用一处实现，`ChatController` 净删三个字段（cacheService/rateLimit/fallbackService 都收进 admission）。
- **email 的交付形态是回执工单**：邮件没有实时回包通道，"答案"必须落在可查证的地方才算交付。reason=EMAIL_REPLY 让回执单与转人工降级单在数据上分开——人工队列一眼能分辨。答案自身走了降级（已有工单）则复用那张单，不叠第二张。
- **限流标签扩展选择兼容路径**：channel 加进 tag 集而不是新开指标名，因为 /actuator/metrics/{name} 按名字聚合全部 series（Micrometer 语义），本仓所有读数入口（verify-plan-actions 的 Metric 助手、run_loadtest 的计数器选择器）都按名读取，实测兼容。

**验证落点**

- 全量 `mvnw verify`：`3 + 15 + 237 = 255` 绿（网关 +6：ChannelFlowJvmTest 5 + SseEventSinkTest 1）。
- 量具 40 PASS；rescore `tool_diff=4` exit 0。
- `verify-channel.ps1`：活体验收未跑（本机无 dev 栈），登记为 17 步全量验收边界；脚本 PS 5.1 语法零错误。
- 本机环境事故照登：验证期间宿主内存被同机其它项目的容器群挤占，出现 4 次 native OOM（hs_err/replay 日志按惯例只作本机证据）；期间尝试了普通 fork、限定堆、forkCount=0 三种跑法，最终在内存恢复后的标准 `mvnw.cmd -B -ntp verify` 全绿。与代码无关，与票 31 登记的宿主内存压力同源。
- CI run 见 Handoff 末行。

**现场追问**

1. *为什么 webhook 三个标签（app/miniapp/webhook）共用一个适配器？* 它们的回包能力与归一规则完全一致（整段 JSON、无追问），差异只是标签本身——为每个标签写一个只有常量不同的类，是抄本不是设计。email 单独成形因为它有独有的交付语义（回执工单）。
2. *为什么 email 的主题要拼进 query 而不是单独字段？* 诉求原文是下游全链路（意图判定、检索、评测）的唯一输入契约；主题单独成字段意味着状态机、评测集、缓存键全都要认识第二个字段。拼接（`【主题】x\n正文`）让 email 在链路里与任何渠道完全同形，代价只是回答里可能复述主题——可接受。
3. *限流计数加了 channel 后，"被限流总量"怎么读？* /actuator/metrics/shoppilot_rate_limited_total 依旧给出跨渠道总和；要按渠道拆分用 /actuator/prometheus 的标签选择，`{dimension="buyer",channel="web"}`。压测口径不受影响（0917 产物里的 rate_limited 读数按名读取，语义相同）。
