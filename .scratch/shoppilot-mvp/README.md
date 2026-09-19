# ShopPilot Tracker

这里是本仓的正式 ticket tracker，不是临时草稿目录。目录名里的 `.scratch` 是历史命名；路径已被 README、ticket、审计脚本和提交记录大量引用，不要为了改名而移动。

最后整理日期：2026-09-18。

## 当前状态

- 当前交付路线见 [`docs/PROJECT_PLAN.md`](../../docs/PROJECT_PLAN.md)：v1.0 收口、3 分钟作品集、面试掌握与冻结；它不新增功能 frontier。
- `v1.0.0` 已冻结，tag `v1.0.0` 指向 CI run `35104751284` 验证通过的 release commit `7f4334c`；默认不开功能票，重开条件见 ADR 0030 五条与 ADR 0031 的面试反馈触发；round17（票 34-41）按 ADR 0033 记录的所有者政策覆盖执行；编排层选型与 provider 缝的触发线见 ADR 0032。
- 2026-09-16：v1.0 release candidate 的 ADR、release note、3 分钟作品集与票 21-32 问答补录均已落地；本地 JVM verify 221 绿、量具 40/40、收口审计 `PASS 93 / FAIL 0 / SKIP 2`。
- 2026-09-18：round16 质量轮增加网关主链路 JVM 集成缝，当前 JVM verify 为 `3 + 12 + 209 = 224` 绿；历史 round14/round15 的 221 读数仍保留为当时落点。
- 2026-09-19（晚）～09-20（凌晨）：票 41 工具循环语义钉死——超限对齐 ADR 0008 字面（预算检查后模型仍要工具 → `TOOL_ROUNDS_EXHAUSTED` 落工单）、`parallel_tool_calls:false` 请求约束 + 多调用防御分支、写动作守卫；票 34 评测量具进 CI（selfcheck + rescore 门禁）；票 35 Prompt 版本化（外置 v1.0.0.md + meta.json fail-fast + SSE/评测报告携带版本）；票 36 情绪门（词典层 0 token 定案 + dev 口径 LLM 分类兜底 + EMOTION_ESCALATION 第 10 降级因 + 工单 priority=high）；票 37 满意度反馈闭环（显式点踩落 biz-mock feedback 表 + 三个隐式信号计数 + ingest 待复核队列与复核流转）；票 38 三渠道契约（channel 包 + webhook/email 入站 + ChatAdmission 提取 + 限流/meta/计数按渠道）；风格票（StyleService 档位矩阵 + 基座+注入段拼装 + SSE meta 带 style；无编号，票号 39 归 Plan）；有意不做清单成文（README 按 ADR 0040 增"有意不做 + 触发条件"小节；无编号）；票 39 Plan 有序步骤（前序依赖表达式 + 前步失败即中止 + `shoppilot_plan_steps_total` 分账，硬闸门 95.0% → 95.0% 通过）。
- 2026-09-20（凌晨，活体栈恢复时抓到并修复的两个真缺陷）：`FeedbackService` 双构造器致网关无法启动（`9a0052d`）；情绪分类器把平静业务查询误判 URGENT 导致全量评测动作类掉到 22-72%（`daa40cf` 从严校准 + 分类提示词外置为版本资产）。当前 JVM verify 为 `3 + 15 + 249 = 267` 绿。
- 2026-09-19：票 33 做了一次双轴复核（Standards / Spec），两条轴都抓到工具循环用例假绿；已补请求体断言并做变异对照（删掉 `AgentStateMachine` 里的 tool 回填即变红）。同一天的登记型改动还有 ADR 0032 与 `.gitattributes`。
- 2026-09-19（下午）：外部审查（5 份跨项目报告）交叉核实后并入 round17——两个发现升级为前置票 41（工具循环语义钉死），登记项落 round17 spec 附录；round17 草稿（spec、ADR 0033-0040、评测集 part4-7、票 41）首次入仓。
- 2026-09-20（凌晨）：round17 收口——全票（34-39、41、风格票、有意不做成文票）已完成并各自留 Handoff；票 39 硬闸门同一 180 条 gold 改前/改后均 95.0%（分意图逐项一致），产物入库登记；`interview-qa.md` 重生成至 157 问/覆盖 42 个 ticket；README 主链路与架构章节补齐 round17 模块；审计 E5/E5b 常数与 B7 禁面按换代指针更新。当前 JVM verify 为 `3 + 15 + 249 = 267` 绿。
- 当前没有其他 `ready-for-agent` 的开放 ticket。票 01-39 与 41、风格票、有意不做成文票均已收口；round17 已收口。
- `done` 与 `implemented` 在本 tracker 中都表示已收口；差异只是早期票和后续 round 的用词。
- Git push 与 PR 由 `.github/workflows/ci-subset.yml` 跑干净 runner 的构建、JVM 测试与 0 token 评测门禁（票 34：判据自检 + 离线 rescore 比对）；全量 17 步活体验收仍是作者本机证据。
- 下一轮不能从旧 `ready-for-agent` 字样推断。重开条件与仍然挂红的裁决见 [`docs/adr/0030-round14-closure-scope-and-reopen-triggers.md`](../../docs/adr/0030-round14-closure-scope-and-reopen-triggers.md)。

2026-09-16 的整理修正了四处历史状态位：

- 票 16、18、19 已实际收口，但票面误留在 `ready-for-agent`。
- round13 spec 的票 21-26 已实际完成，但 spec 误留在 `ready-for-agent`。
- 票 28、30 文件尾部各误粘了下一张票的草稿；已移除，正式票 29、31 保留在独立文件中。

状态位的原则：以验收证据、Handoff notes 和后续提交为准；发现历史 `ready-for-agent` 与实现证据冲突时，先校正 ticket，再判断 frontier。

## Round 与 Spec

| Round | 范围 | Spec/入口 | 状态 |
| --- | --- | --- | --- |
| round3 | 票 01-20，初始 MVP 到材料固化 | [`round3-plan.md`](round3-plan.md)；审计 [`round3-closeout-audit.py`](round3-closeout-audit.py) / [`round3-closeout-audit.txt`](round3-closeout-audit.txt) | done |
| round13 | 票 21-26，后端外壳加固 | [`round13-spec-backend-shell-hardening.md`](round13-spec-backend-shell-hardening.md) | done |
| round14 | 票 27-31，停机/配置/指标/记账/崩溃归因 | [`round14-spec-shutdown-metrics-and-evidence-closure.md`](round14-spec-shutdown-metrics-and-evidence-closure.md) | done |
| round15 | 票 32，CI 子集门禁 | [`round15-spec-ci-subset-gate.md`](round15-spec-ci-subset-gate.md) | done |
| round16 | 票 33，网关主链路 JVM 集成缝 | [`round16-spec-gateway-main-path-jvm-tests.md`](round16-spec-gateway-main-path-jvm-tests.md) | done |
| round17 | 票 34-41，对齐完整落地级电商客服链路（外部审查补强并入） | [`round17-spec-architecture-completeness.md`](round17-spec-architecture-completeness.md) | done |

## Ticket 索引

| # | Ticket | 状态 | 范围 |
| --- | --- | --- | --- |
| 01 | [`01-skeleton-and-compose.md`](issues/01-skeleton-and-compose.md) | done | 三模块骨架与中间件容器栈 |
| 02 | [`02-mock-jwt-identity.md`](issues/02-mock-jwt-identity.md) | done | mock JWT 发签与身份注入 |
| 03 | [`03-biz-mock-data-foundation.md`](issues/03-biz-mock-data-foundation.md) | done | biz-mock 数据底座与租户感知仓储 |
| 04 | [`04-knowledge-ingestion.md`](issues/04-knowledge-ingestion.md) | done | 政策知识库离线入库 |
| 05 | [`05-thin-vertical-slice.md`](issues/05-thin-vertical-slice.md) | done | 最细竖切：政策答案与 SSE |
| 06 | [`06-l1-cache-admission-writeback.md`](issues/06-l1-cache-admission-writeback.md) | done | L1 缓存、写回资格与 singleflight |
| 07 | [`07-intent-cascade.md`](issues/07-intent-cascade.md) | done | 意图三级级联判定 |
| 08 | [`08-es-bm25-rrf-fusion.md`](issues/08-es-bm25-rrf-fusion.md) | done | ES 倒排召回与 RRF |
| 09 | [`09-l2-semantic-cache-epoch.md`](issues/09-l2-semantic-cache-epoch.md) | done | L2 语义缓存与纪元失效 |
| 10 | [`10-tool-contract-http-boundary.md`](issues/10-tool-contract-http-boundary.md) | done | 工具契约与跨进程边界 |
| 11 | [`11-action-loop.md`](issues/11-action-loop.md) | done | 查订单与查物流闭环 |
| 12 | [`12-write-idempotency-state-guard.md`](issues/12-write-idempotency-state-guard.md) | done | 写操作幂等与状态校验 |
| 13 | [`13-rate-limiting.md`](issues/13-rate-limiting.md) | done | 双层限流与通道语义 |
| 14 | [`14-fallback-tickets-local-mode.md`](issues/14-fallback-tickets-local-mode.md) | done | 降级原因、工单与 local 模式 |
| 15 | [`15-debug-console.md`](issues/15-debug-console.md) | done | 单文件调试台 |
| 16 | [`16-tool-calling-eval.md`](issues/16-tool-calling-eval.md) | done | Tool Calling 标注评测 |
| 17 | [`17-threshold-calibration.md`](issues/17-threshold-calibration.md) | done | 语义缓存阈值标定 |
| 18 | [`18-load-test-dual-curves.md`](issues/18-load-test-dual-curves.md) | done | 双曲线、虚拟线程与节约率 |
| 19 | [`19-materials-star.md`](issues/19-materials-star.md) | done | README、演示与 STAR 材料 |
| 20 | [`20-action-order-attribution.md`](issues/20-action-order-attribution.md) | done | ACTION_ORDER 归因与量具修复 |
| 21 | [`21-bind-address-gated-credential-fail-fast.md`](issues/21-bind-address-gated-credential-fail-fast.md) | done | 密钥与运维端点绑定地址守护 |
| 22 | [`22-conversation-owned-by-shop-and-customer.md`](issues/22-conversation-owned-by-shop-and-customer.md) | done | 会话按店铺 + 买家归属 |
| 23 | [`23-dependency-health-outside-readiness.md`](issues/23-dependency-health-outside-readiness.md) | done | 依赖健康与就绪门分离 |
| 24 | [`24-mdc-trace-correlation-across-async.md`](issues/24-mdc-trace-correlation-across-async.md) | done | MDC 跨异步边界关联 |
| 25 | [`25-rest-error-envelope.md`](issues/25-rest-error-envelope.md) | done | REST 统一错误出口 |
| 26 | [`26-debug-console-remaining-defects.md`](issues/26-debug-console-remaining-defects.md) | done | 调试台剩余交互缺陷 |
| 27 | [`27-writeback-pool-shutdown-seam-and-saturation.md`](issues/27-writeback-pool-shutdown-seam-and-saturation.md) | implemented | 写回池停机与饱和计数 |
| 28 | [`28-config-format-validation-and-secret-non-echo.md`](issues/28-config-format-validation-and-secret-non-echo.md) | implemented | 配置格式/范围校验 |
| 29 | [`29-state-gauges-four-groups.md`](issues/29-state-gauges-four-groups.md) | implemented | 四组运行时状态指标 |
| 30 | [`30-bookkeeping-closure-readme-scopes-and-registries.md`](issues/30-bookkeeping-closure-readme-scopes-and-registries.md) | implemented | README 作用域与欠账登记 |
| 31 | [`31-crash-attribution-and-heap-caps.md`](issues/31-crash-attribution-and-heap-caps.md) | implemented | JVM 崩溃归因与堆上限 |
| 32 | [`32-ci-subset-gate.md`](issues/32-ci-subset-gate.md) | implemented | 干净 runner 的 CI 子集门禁 |
| 33 | [`33-gateway-main-path-jvm-tests.md`](issues/33-gateway-main-path-jvm-tests.md) | implemented | 网关主链路 JVM 集成缝 |
| 41 | [`41-tool-loop-semantics-pinned.md`](issues/41-tool-loop-semantics-pinned.md) | implemented | 工具循环语义钉死（round17 前置票） |
| 34 | [`34-eval-subset-ci.md`](issues/34-eval-subset-ci.md) | implemented | 评测子集进 CI（round17 前置票） |
| 35 | [`35-prompt-versioning.md`](issues/35-prompt-versioning.md) | implemented | Prompt 版本化（ADR 0037） |
| 36 | [`36-sentiment-gate.md`](issues/36-sentiment-gate.md) | implemented | SentimentGate 情绪门（ADR 0034） |
| 37 | [`37-feedback-loop.md`](issues/37-feedback-loop.md) | implemented | 满意度反馈闭环（ADR 0039） |
| 38 | [`38-channel-adapter.md`](issues/38-channel-adapter.md) | implemented | 三渠道契约接入（ADR 0035） |
| 39 | [`39-plan-ordered-steps.md`](issues/39-plan-ordered-steps.md) | implemented | Plan 有序步骤（ADR 0036；硬闸门 95.0% → 95.0%） |
| — | [`style-engine.md`](issues/style-engine.md) | implemented | 风格引擎（ADR 0038；无编号票，票号 39 归 Plan） |
| — | [`readme-non-goals.md`](issues/readme-non-goals.md) | implemented | 有意不做清单成文（ADR 0040；无编号票） |

## 如何新增或领取工作

1. 先读 `docs/adr/0030-round14-closure-scope-and-reopen-triggers.md`，确认这不是一个已有明确“当前不做”决定的事项。
2. 新工作先有 round spec，再拆成单个 tracer-bullet ticket；不要直接往旧 ticket 后面续写。
3. ticket 至少写清 `What to build`、`Blocked by`、`Status`、机器可跑的 `Verify` 和验收项。
4. 一次只领取一个 frontier ticket，收尾补 `## Handoff notes`，再提交。
5. 过期待办不要用删历史解决；在校正说明中写清原状态、校正依据和日期。

全仓接手入口见 [`AGENTS.md`](../../AGENTS.md)，代码地图见 [`docs/CODE_MAP.md`](../../docs/CODE_MAP.md)，证据地图见 [`docs/EVIDENCE.md`](../../docs/EVIDENCE.md)。
