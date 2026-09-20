# Changelog

本文件记录 ShopPilot 的迭代轨迹。

**版本单位是「轮」(round)，不是 semver。** 全仓只打过一个 tag（`v1.0.0`），因为项目按轮推进、每轮对应一批票、每张票至少挂一篇 ADR。轮次与票号是这一层的主要索引，语义化版本在这个粒度上没有信息量。

| 索引 | 位置 |
| --- | --- |
| 轮次 spec | `.scratch/shoppilot-mvp/round*-spec-*.md` |
| 票 | `.scratch/shoppilot-mvp/issues/` |
| 决策 | [`docs/adr/`](docs/adr/)（编号 0001–0040，**0022 有意预留未占用**，见 ADR 0024 编号说明） |
| 指标证据 | [`docs/EVIDENCE.md`](docs/EVIDENCE.md) |
| 发布声明与冻结策略 | [`RELEASE.md`](RELEASE.md) |

日期取该轮首次提交日。下表 ADR 列是该轮**新增**的决策，不是全部改动。

---

## [Unreleased]

`v1.0.0` 之后的工作，共 **24 个提交**，尚未打新 tag。其中 round16 与 round17 是 `RELEASE.md` 冻结策略下的两次重开。

### round17 — 架构完整度重开 · 2026-09-19 起（进行中）

**这是一次显式的政策覆盖，不是触发式重开。** ADR 0033 原文记录：冻结期新功能本应由「面试同一缺口被问两次」触发（ADR 0031），本轮重开**不满足**该条件，是项目所有者在知情该政策的前提下做出的覆盖决策，并要求「重开必须诚实记录为政策覆盖，而不是伪装成面试反馈触发」。

范围 M1–M5，全部走旁挂插入，主链路 10 状态机不扩枚举。

| 票 | 模块 | ADR |
| --- | --- | --- |
| — | round17 重开范围与准入筛子 | [0033](docs/adr/0033-round17-reopen-for-architecture-completeness.md) |
| 36 | 情感门 SentimentGate（词典优先前置过滤 + EMOTION_ESCALATION） | [0034](docs/adr/0034-sentiment-gate-lexicon-first-before-triage.md) |
| 38 | 多渠道接入 ChannelAdapter（webhook / email，契约级） | [0035](docs/adr/0035-channel-adapter-normalizes-inbound-to-one-contract.md) |
| — | Plan 升级为有序步骤（仍在两轮界内） | [0036](docs/adr/0036-plan-is-ordered-steps-inside-the-two-round-bound.md) |
| 35 | Prompt 版本化（T-3 的前置） | [0037](docs/adr/0037-prompt-text-externalized-with-version-meta.md) |
| — | 风格引擎 StyleService（档位表 + base+injection 组装） | [0038](docs/adr/0038-style-profile-injection-not-rewriting.md) |
| 37 | 满意度反馈闭环（显式 + 隐式 + 人工复核回流） | [0039](docs/adr/0039-feedback-explicit-implicit-with-human-review-reflow.md) |
| 41 | 工具循环语义钉住（耗尽降级 + 多调用防御） | — |

**非目标成文化**：[0040](docs/adr/0040-distributed-session-scale-and-dag-parallelism-are-intentional-non-goals.md) —— 十万级并发会话（Redis Streams）、多工具并行 / DAG、真实渠道集成、自动回流再训练。同时按该 ADR 把非目标写入 README。

### round16 — 网关主路径 JVM 测试 · 2026-09-18 → 09-19

- 票 33 网关主路径 JVM 测试。[0032](docs/adr/0032-orchestration-is-hand-built-with-a-provider-seam.md) 记录编排为手写实现并保留 provider seam
- 票 34 评测量具进 CI —— selfcheck + 离线 rescore 门禁比对（基线 224 → 228）。ADR 0033 把它列为 T-3 开工的前置硬闸门，在此兑现

### round15 — CI 子集门禁 · 2026-09-16

- 票 32 CI 子集门禁。这是 ADR 0030 五条触发式重开条件中**第 1 条**（「下一轮开工第一票，不需要任何外部事件」）的兑现

### 其他

- 2026-09-17 补齐 v1 走查文档（`docs/interview-guide.md` 线）
- 2026-09-19 登记 L1 复测产物与 ci-subset 基线读数（`docs/EVIDENCE.md`）

---

## [v1.0.0] — 2026-09-16（冻结发布）

tag `v1.0.0` → commit `7f4334c`（CI run `35104751284` 已验证）。发布声明、三条未达标红线与冻结策略见 [`RELEASE.md`](RELEASE.md)。

### round14 — 收口轮 · 2026-09-15 → 09-16

**决策**：[0030](docs/adr/0030-round14-closure-scope-and-reopen-triggers.md)（新证据按拆口处置 + **五条触发式重开条件**）、[0031](docs/adr/0031-interview-feedback-is-the-sixth-reopen-trigger.md)（面试反馈成为第六条重开触发）

| 票 | 内容 |
| --- | --- |
| 27 | 写回池 shutdown seam 与饱和（`caller_runs` 计数、队列深度、停机丢弃计数） |
| 28 | 配置格式校验与密钥不回显 |
| 29 | 状态进指标面 —— 熔断 state+transition、deps/up、dev_defaults、writeback queue gauge |
| 30 | 记账收口、README 作用域书写、登记表；「可查工单」定义进 `CONTEXT.md` |
| 31 | 崩溃归因与显式堆上限（14 份 `hs_err` 全部归因到 native 内存 OOM） |

ADR 0030 的处置特征值得记录：**收口轮不许悄悄扩面，但知道而不写下来更糟** —— 四条新证据分别按「整条进本轮」「拆半合并」「本轮不做但有主」「下一轮第一票」四种方式处置，未做项一律转成**有触发线的挂起**，而非记成欠账。

### round13 — 门禁重锚与可观测 · 2026-09-13 → 09-14

**决策**：[0023](docs/adr/0023-implementation-round-gate-switched-to-content-level-red-lines.md)（门禁改判内容级禁面）、[0024](docs/adr/0024-scope-is-interview-artifact-not-productionization.md)（**范围筛子**：排除外壳项，0022 编号预留即出于此）、[0025](docs/adr/0025-conversation-owned-by-shop-and-customer.md)（会话归属为店铺+买家）、[0026](docs/adr/0026-degradable-dependencies-stand-outside-the-readiness-gate.md)（可降级依赖不进 readiness）、[0027](docs/adr/0027-mdc-propagated-manually-across-async-boundaries.md)（MDC 跨异步边界手工传递）、[0028](docs/adr/0028-rest-error-envelope-covers-gateway-generated-errors-only.md)（REST 错误信封只覆盖网关自产错误）、[0029](docs/adr/0029-dev-defaults-legality-decided-by-bind-address.md)（dev 默认值合法性由绑定地址裁决）

票 21–26（打包 22–25 对应的决策、票 26 调试台余留缺陷）。09-14 落地票 24 的四个坐标进 MDC。

### 标定与修复轮 · 2026-09-09 → 09-12

- 09-09 [0017](docs/adr/0017-explicit-escalation-in-t0-rules.md) 显式转人工进 T0 规则、[0018](docs/adr/0018-cache-eligibility-vector-vs-degradation.md) 缓存准入（向量 vs 降级）、[0019](docs/adr/0019-ttft-bucketed-measurement-and-mock-floor.md) TTFT 分桶测量与 Mock 下限
- 09-10 [0020](docs/adr/0020-coldstart-script-closes-reproducibility-criterion.md) 冷启动脚本收口可复现判据
- 09-11 票 20 [0021](docs/adr/0021-action-order-gold-boundary-relabel-not-tool-merge.md) ACTION_ORDER 最低行归因收口 —— 重标 4 条 gold、修评分器三处缺陷（**归因优先于让工具变强**）
- 09-12 双轴审查（该日 61 个提交）—— 修掉自家量具五处假绿，审计项数钉到 77

### Sprint 1-2 — 竖切打通 · 2026-09-08

票 01–19 一次排定（骨架与 compose、mock JWT、biz-mock 数据底座、知识入库、薄竖切、L1 缓存准入与写回、意图级联、ES BM25 + RRF、L2 语义缓存与纪元、工具契约 HTTP 边界、动作循环、写回幂等与状态守卫、限流、降级工单本地模式、调试台、工具调用评测、阈值标定、压测双曲线、STAR 材料）。

**决策**：[0001](docs/adr/0001-three-mode-llm-dependency-and-metric-scopes.md)–[0016](docs/adr/0016-same-bucket-antonym-polarity-guard.md) 十六篇同日落盘，覆盖三种 LLM 依赖形态与指标口径、biz-mock 独立进程、缓存准入 fail-closed、租户与平台的范围切分、行级隔离三重防线、缓存写回资格与 singleflight、三级意图判定单次调用、有界状态机两轮工具、转人工落库、ES+Qdrant 双引擎混合检索、压测造流与双曲线、DashScope OpenAI 兼容与 token 预算、**五天范围裁剪**、mock JWT 身份注入、回滚裁 agent 打字而非校验、同桶反义极性守卫。

---

## 口径说明

- 本文件**不重述** `RELEASE.md` 的指标与红线。数字与判据的唯一入口是 [`docs/EVIDENCE.md`](docs/EVIDENCE.md)。
- 本文件**不替代** ADR。ADR 记录「为什么这样选」，本文件记录「什么时候发生了什么」。
- 轮次内部的具体验收判据写在各轮 spec 里，不在这里复述。
