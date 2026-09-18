# ShopPilot Tracker

这里是本仓的正式 ticket tracker，不是临时草稿目录。目录名里的 `.scratch` 是历史命名；路径已被 README、ticket、审计脚本和提交记录大量引用，不要为了改名而移动。

最后整理日期：2026-09-16。

## 当前状态

- 当前交付路线见 [`docs/PROJECT_PLAN.md`](../../docs/PROJECT_PLAN.md)：v1.0 收口、3 分钟作品集、面试掌握与冻结；它不新增功能 frontier。
- `v1.0.0` 已冻结，tag `v1.0.0` 指向 CI run `35104751284` 验证通过的 release commit `7f4334c`；默认不开功能票，重开条件见 ADR 0030 五条与 ADR 0031 的面试反馈触发。
- 2026-09-16：v1.0 release candidate 的 ADR、release note、3 分钟作品集与票 21-32 问答补录均已落地；本地 JVM verify 221 绿、量具 40/40、收口审计 `PASS 93 / FAIL 0 / SKIP 2`。
- 2026-09-18：round16 质量轮增加网关主链路 JVM 集成缝，当前 JVM verify 为 `3 + 12 + 209 = 224` 绿；历史 round14/round15 的 221 读数仍保留为当时落点。
- 当前没有 `ready-for-agent` 的开放 ticket。票 01-32 均已收口。
- `done` 与 `implemented` 在本 tracker 中都表示已收口；差异只是早期票和后续 round 的用词。
- Git push 与 PR 由 `.github/workflows/ci-subset.yml` 跑干净 runner 的构建与 JVM 测试；全量 17 步活体验收仍是作者本机证据。
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

## 如何新增或领取工作

1. 先读 `docs/adr/0030-round14-closure-scope-and-reopen-triggers.md`，确认这不是一个已有明确“当前不做”决定的事项。
2. 新工作先有 round spec，再拆成单个 tracer-bullet ticket；不要直接往旧 ticket 后面续写。
3. ticket 至少写清 `What to build`、`Blocked by`、`Status`、机器可跑的 `Verify` 和验收项。
4. 一次只领取一个 frontier ticket，收尾补 `## Handoff notes`，再提交。
5. 过期待办不要用删历史解决；在校正说明中写清原状态、校正依据和日期。

全仓接手入口见 [`AGENTS.md`](../../AGENTS.md)，代码地图见 [`docs/CODE_MAP.md`](../../docs/CODE_MAP.md)，证据地图见 [`docs/EVIDENCE.md`](../../docs/EVIDENCE.md)。
