# 14 — 降级原因枚举、工单落点与 local 模式验证

**What to build:** 现场把物流接口打成全超时，用户收到模型组织的人话说明加一个可查证的工单号；把模型端点指向不存在的地址，`local` 降级链路真的能接管。落实 ADR 0009、0012、0015。

**Blocked by:** 12 — 写操作幂等与状态前置校验；13 — 双层限流

**Status:** done

**Verify:** 注入 `failRate=1.0` -> 用户收到 `TOOL_UNAVAILABLE` 工单而非 500；把模型端点指向不存在地址 -> 走 LLM 降级；`local` profile 下政策问答与业务办理各跑通一条。

- [x] 降级原因枚举齐备且一一映射：`LLM_TIMEOUT` `LLM_CIRCUIT_OPEN` `LLM_BUDGET_EXCEEDED` `TOOL_UNAVAILABLE` `INTENT_UNRESOLVED` `RATE_LIMITED` `SLOT_UNRESOLVED`
- [x] 每种 reason 有对应自动化用例或故障注入脚本，能稳定复现，不靠"运气不好才会触发"
- [x] 转人工在 biz-mock 落 `tickets`（含 transcript，按租户隔离），`ticketId` 经 SSE `fallback` 事件回执
- [x] 工单支持状态流转（`OPEN -> ASSIGNED -> RESOLVED`），网关侧只读查询端点 + 状态变更端点
- [x] Resilience4j 熔断配置：biz-mock 侧以 `failRate=1.0` 注入可打开熔断，恢复后半开关闭合
- [x] `local` profile 指向 Ollama（`qwen2.5:3b` + `bge-m3`），做一次端到端 smoke：政策问答与业务办理各一条，记录 3B 模型下的真实表现与失败模式
- [x] 用例：`failRate=1.0` 时用户收到 `TOOL_UNAVAILABLE` 工单而非 500

## Handoff notes

**关键决策**

1. **模型侧故障注入走运行期开关，不重启进程。** `LlmFaultInjector` 让 `timeout|unavailable|budget` 三种注入抛出与真实故障**完全相同**的 `LlmException`，走同一条 `mapLlmFailure` 映射。真等 Ollama 超时不可控，改端点要重启，两种都没法进 CI。
2. **注入只在 `ops.enabled` 为真时生效。** 生产把 ops 关掉，注入路径直接短路，不留一个"其实还能触发"的暗门。
3. **运维端点全部经网关代理，`X-Internal-Token` 只在网关进程里出现。** 工单是租户级数据，身份取自已验签的 `TenantContext`，不接受路径/查询参数里的租户号；故障注入与演示复位是平台级动作，额外要求 `X-Ops-Token`。
4. **限流是唯一会自我放大的降级。** 被限流的请求本身就发生在洪峰上，每个 429 落一单等于把工单表变成 DDoS 目标。按 `(tenant, customer)` 合并成一张单，窗口 5 分钟，后续命中只加计数。实测 8 次 429 → 1 张工单。
5. **工单状态是枚举 + 转移表，不是自由文本。** 之前 `PATCH status` 能把工单写成 `BOGUS`，一个拼错的 `RESOLVED` 就让工单永远进不了人工队列。`RESOLVED` 是终态（409），未知值 400，不存在 404——三种失败分开，脚本才断言得准。
6. **`INTENT_UNRESOLVED` 需要一个显式开关才谈得上"可复现"。** 90 条语料上稠密召回几乎总能返回候选，"检索为空 → 打负标记"这条分支在真实流量里几乎不命中。要么承认它是死代码，要么给一个打标记的运维端点让它可演示——选了后者，并把它写进已知限制。
7. **清缓存不能用推纪元代替。** 纪元同时是检索过滤器（`kb_epoch` 是 ES 与 Qdrant 的必选条件），推一次等于把 90 条政策条款整体摘出检索范围，表现为"所有政策问答突然降级"。`POST /ops/cache/flush` 只清 L1 正文、负标记与 L2 向量表，纪元不动。这个坑是本轮踩出来的。
8. **`local` 模式 3B 模型的真实失败模式：它倾向用自然语言追问槽位，而不是发 function call。** 结果是 `maxSlotAsks`、`SLOT_ASK` 状态、`SLOT_UNRESOLVED` 全成摆设。修法不是改 prompt 求它，而是网关在"ACTION 意图 + 首轮没调工具 + 没产出任何业务事实"时自己派生工具、自己抽槽位、自己数追问次数。改地址的 7 个槽位仍交模型抽（正则会把能办的单子一路问成转人工），这条记进已知限制。
9. **显式转人工下沉到 T0 规则层，不由 embedding 可用性决定。** 验收跑出的真事故：`转人工` 原来只有 T1 质心认得，08:49:21 一次 bge-m3 超时让判定 fail-closed 成 UNKNOWN，请求改走模型，25 秒后拿到的是模型自述——既没有 `fallback` 帧也没有工单。七种降级原因里只有这一种依赖向量服务，而它偏偏是"用户亲口喊人来"。现在 转人工/转个人/人工客服/真人客服/人工服务/转接人工/人工介入 在 T0 定案且**排在实体与第一人称之前**（`90001 这单搞错了，转人工` 要的是人不是订单查询），否定词只看关键词前 2 个字符，「别转人工」不触发。见 ADR 0017。

**你需要能当场回答的追问**

- *Q：降级原因你怎么证明不是写在纸上的？* A：`FallbackReason` 枚举 10 种，去掉主动转人工还剩 9 种降级；`scripts/verify-fallback.ps1` 一条命令逐条断言其中 8 种（7 种降级 + 主动转人工），每种打印 `reason` 与 `ticketId`，末尾再经网关代理拉一次工单队列做交叉核对。LLM 三种靠 `LlmFaultInjector`，工具一种靠 biz-mock `failRate=1.0`，意图未决一种靠负缓存标记，槽位一种靠两轮对话拒答，转人工一种靠用户直接说，限流一种靠连打超配额。另 2 种降级（轮次用尽、情绪升级）由 `FallbackReasonTest` 与 `verify-emotion.ps1` 覆盖；`FallbackReasonTest` 6 项守住"枚举齐全 + 每种都落单 + 每种都有话术"。**（2026-09-23 更正：原文写「跑完七种」，枚举漏了 `INTENT_UNRESOLVED` 又把「转人工」计进七种，两处都改；口径见 README 验收对照的「降级原因 N 种」段。）**
- *Q：为什么 `LLM_CIRCUIT_OPEN` 和 `TOOL_UNAVAILABLE` 是两个原因而不是一个？* A：熔断器分别套在模型调用链和业务调用链上，恢复时间差一个数量级——模型超时可以先降级到本地小模型，业务系统超时只能转人工。合并成一个，值班的人就分不出该找算法还是该找交易。
- *Q：限流合并工单，会不会漏掉真实的大面积限流事故？* A：不会漏，只是不重复开单。工单里带 `(tenant, customer)`，另有 `shoppilot_rate_limited_total` 计数器按维度打点，Grafana 看的是计数而不是工单条数。工单是"有人需要被跟进"的凭证，不是监控指标。
- *Q：用户喊转人工，依赖的 embedding 服务挂了怎么办？* A：字面显式表达在 T0 定案，不碰 embedding 也不碰模型，这是事故后补的最低可用线（ADR 0017）；换说法的求助（"叫你们经理过来"）仍走 T1/T2，embedding 超时就 fail-closed 进模型定案，模型也没了才落到 LLM_* 那几种 reason。三层各自兜一段，不承诺任何说法都不依赖服务，这句话写进 README 已知限制。

**local 模式 smoke 记录（2026-09-08，qwen2.5:3b + bge-m3）**

政策问答：`生鲜坏了怎么赔` → `POLICY_FRESH` / T0 / 5 条引用 / 答案逐句可回溯条款。业务办理：`订单 90002 到哪了` → `ACTION_LOGISTICS` → 工具 → 人话。失败模式两条：一是前述"用自然语言代替 function call"；二是政策意图下若下发工具，3B 模型会凭空编订单号（已由"政策意图不下发工具"修掉）。
