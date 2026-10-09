# Changelog

本文件记录 ShopPilot 的迭代轨迹。

**版本单位是「轮」(round)，不是 semver。** 全仓只打过一个 tag（`v1.0.0`），因为项目按轮推进、每轮对应一批票、每张票至少挂一篇 ADR。轮次与票号是这一层的主要索引，语义化版本在这个粒度上没有信息量。

| 索引 | 位置 |
| --- | --- |
| 轮次 spec | `.scratch/shoppilot-mvp/round*-spec-*.md` |
| 票 | `.scratch/shoppilot-mvp/issues/` |
| 决策 | [`docs/adr/`](docs/adr/)（编号 0001–0051，**0022 有意预留未占用**，见 ADR 0024 编号说明） |
| 指标证据 | [`docs/EVIDENCE.md`](docs/EVIDENCE.md) |
| 发布声明与冻结策略 | [`RELEASE.md`](RELEASE.md) |

日期取该轮首次提交日。下表 ADR 列是该轮**新增**的决策，不是全部改动。

---

## [Unreleased]

`v1.0.0` 之后的工作，共 **158 个提交**，尚未打新 tag。**round15 – round22 逐轮记在下面；round23 – round31 九轮在这个文件里还没有按轮补录**（逐轮读数以 [`docs/EVIDENCE.md`](docs/EVIDENCE.md) 与 tracker 为准，本文件的轮次小节只到 round22）。round16 – round22 都是 `RELEASE.md` 冻结策略下的重开（round17 起由所有者政策覆盖，不再伪装成触发式重开）。

### 展示面与 CI 产物修复 · 2026-10-10

不是功能轮：改的是「别人第一眼看到什么」，外加一条从 round30 起就一直红着的 CI。README 首屏原先只有文字墙；两条最该拿出来的链路（语义缓存的极性守卫、涉资动作的人机协同）此前没有任何现场证据；而 `main` 上的 `ci-subset` 自 round30 起每次推送都失败。

| 项 | 内容 |
| --- | --- |
| README 首屏 | 「三十秒：一次请求怎么流过这座网关」——入站 → INTAKE 情绪门 → TRIAGE 三级级联 → CACHE_READ / RETRIEVE → PLAN → TOOL_EXEC（2 轮硬上限、涉资只出草案）→ SLOT_ASK → REPLY → CACHE_WRITE → FALLBACK，逐格在 `docs/CODE_MAP.md` 有源码落点；「数字看板：防线开 vs 关」是同机同计数器口径下逐档关防线的对照 |
| 两段现场录像 | [`docs/refund-hitl-demo.gif`](docs/refund-hitl-demo.gif)（涉资动作的人机协同，6 帧、328 KB）与 [`docs/polarity-guard-demo.gif`](docs/polarity-guard-demo.gif)（极性守卫，5 帧、308 KB）。帧全部来自浏览器截图 + `logs/gateway.log` 原始行 + `/actuator` 计数器读数，**没有一帧是画出来的**；四个采集/合成脚本全在仓，且自带断错（SSE 一帧没抓到、日志里没有极性守卫行 → 抛错不产出） |
| CI 修复 | 重建 `shoppilot-gateway/src/main/resources/static/workspace/` 构建产物并连同 `index.html` 提交，新哈希与 CI 自己产出的一致 |

**读数**：复跑 `.\mvnw.cmd -B -ntp verify` 四模块 **`10 + 67 + 383 + 45 = 505`** 全绿；连续两次 `npm run build` 输出逐字节相同（产物确定）；`git diff --check` 干净。**一处记账更正**：round30 记的 biz-mock **66** 实为 **67**（源码一个字节没动，重跑仍是 67），历史读数不动、更正登记在 `docs/EVIDENCE.md`。

**未达成照登**（三条都写进了 README 的对应段落）：① 坐席点「放行」之后系统里**没有任何链路**把结果推回买家浏览器（出站投递只认 webhook / email / 回执工单三类，退款审核只发审计事件），录像因此停在放行那一刻，没有编「买家收到通知」那一帧；② 系统**没有** `[PolarityGuard] Antonym polarity detected, bypass L2 cache.` 这行日志，真实打印的是 `L2 语义命中被极性守卫拒绝: polarity-conflict (cached=… incoming=…)`；③ 最初想演示的「我想要申请换货 / 我不想换货了」实测余弦只有 **0.8851**，够不到 L2 命中、守卫不会被触发，改用项目自己标定过的那一对（同极性 0.9980 命中 / 反义 0.9799 被拒）。

**采录像时踩到的一条链**（根因与修复都登记在 `docs/EVIDENCE.md`）：Redis 丢了 `shoppilot:kb:epoch` → 纪元回退成 1 而 Qdrant/ES 里的 chunk 是纪元 2/5 → 检索恒空 → 写回资格不满足 → L2 从不写入 → 极性守卫一次都不触发。连跑两次 `--spring.profiles.active=ingest` 把纪元对齐到 3 后复现成功。**这一步红不代表防线失效**，但它照出一个可观测缺口：「检索为空」与「真的没查到」在指标上分不开。

### round22 — RAG 的机器背书 · 2026-09-28

补的是「**谁在机器上守着这条链**」。触发物是 round21 期间对仓核实出的三条：CI 的 24 条夹具四个 kind 都**不读** `ruleIds`/RRF 序（改坏融合没人会红）、唯一那份 RAG 证据的 16 条查询 `dense` 与 `hybrid` **名次全同**、评测四列都是单维请求质量而**没有一列是端到端终局**。

**依据分两类，不许混称**：65 / 67 记为「**触发已到**」（各自触发原文成立，不需要政策覆盖）；64 / 66 记为「**政策越过**」，其中 **64 的触发条件就是它自己**（「出现可离线复跑的录制/回放路径」），引自己当依据是循环论证——收口时不许讲成「登记第 5 项的触发已成立」。

| 票 | 内容 | 依据 | ADR |
| --- | --- | --- | --- |
| — | round22 重开范围与逐票依据的四分类 | — | [0048](docs/adr/0048-round22-reopen-for-machine-backing.md) |
| 64 | 检索融合 0 token 录放回归门：由录制的两路序**重算**融合比对 top-K 前缀，四个常数从生产 `application.yml` 绑定、语料 sha 现算；夹具 10 条、**10/10 在 top-5 上 dense≠fused**（原 16 条是 0/16）；同交付 `MarkdownChunkerTest`（还 `CODE_MAP` 那笔债） | **政策越过** | [0049](docs/adr/0049-retrieval-fusion-replay-gate.md) |
| 65 | task 级判据最小形态：只读 `plan[]`/`context.ruleIds`/`fallbackReason` 三个既有字段，`task_done` **单独成列、不给总分、不并入四列** | **触发已到** | [0050](docs/adr/0050-task-level-judgement-minimal-form.md) |
| 66 | token 计量加 `source=provider\|estimate` 标签：**名字不变 → 指标名计数不变**（仍 53）；不改 `TokenBudget` 放行语义 | **政策越过** | — |
| 67 | 告警最小集 + `promtool test rules`：4 条规则 × 两侧断言（该响 / 不该响）；**实测抓到一处会静默失效的写法**（`rate(fallback)/rate(requests)` 两侧标签集不同 → PromQL 默认不匹配 → 规则永不触发而 `check rules` 照样 SUCCESS，两侧 `sum()` 才对） | **触发已到** | [0051](docs/adr/0051-alert-rules-and-promtool.md) |
| 68 | 收口：审计常数换代、EVIDENCE / CODE_MAP / tracker 同步、指标名复核（**53，本轮不换代**） | — | — |

**读数**：JVM `3 + 21 + 287` → **`5 + 29 + 304 = 338`** 绿；覆盖率 gateway 59.47% → **62.79%**；CI **五步 → 八步**（+task 判据 / +检索录放门 / +告警规则测试）；本机矩阵 23 步 → **25 步**，落点 `logs/acceptance-run-20260928-134721.log`（**786 s、23 绿 / 2 红**）；收口审计 **PASS 86 / FAIL 1 / SKIP 8**（唯一一红 `F1c`）；干净 runner 上 `ci-subset` **success**（八步全绿）。

**未达成照登**：票 64 **不闭合** round19 登记第 5 项（不覆盖活体 hit@5）；**gold 180 条活体重跑未做，真因是「条件不成立」**（日预算 `260000` < 180 条所需约 40-60 万，不越 ADR 0012 的闸门）；票 65 的活体语义正例已补（`task_done 3 / 没办成 0 / 未观测 0`，更早那次 `1/2/0` 是环境红、两次并列供着）。

### round21 — 闭环的最后一公里 · 2026-09-28

补的是**钱动了有没有人看过**、以及**客户端重试拿不拿得到答案**。触发物是一份外部审计对 ShopPilot 的四条指控 + 一处数字纠正；**逐条对仓核实后只有一条是全新发现**（README 写的指标名数从 round18 起就落后）。

| 票 | 内容 | 依据 | ADR |
| --- | --- | --- | --- |
| — | round21 重开范围与逐票依据的四分类 | — | [0046](docs/adr/0046-round21-reopen-for-the-last-mile.md) |
| 57 | 指标名计数换代 41 → 52（改的是生成物的来源票再重跑生成器）；两份活体报告加 provenance 表头 | ADR 0031:10 事实修正 | — |
| 58 | 幂等重放时机前移：请求级回放索引只在**客户端显式 token + query 指纹相等**时命中，判重放不再取决于模型肯不肯重发工具调用 | ADR 0031:10（兑现既有契约语义） | — |
| 59 | 退款审批闸门：受理与放行拆两态（`Refund(PENDING_REVIEW)` → `PROCESSING`/`REJECTED` + `ToolStatus.PENDING_APPROVAL`），资金放行由人工审核推进；**HITL 不必是对话轮次，可以是状态迁移的门**（ADR 0008/0036 一字未动） | **政策越过** | [0047](docs/adr/0047-refund-review-is-a-state-transition-gate.md) |
| 60 | 买家读回：`OrderView.refundReview` 三态 + 到账边界写进 `ToolResponse.message` | **政策越过** | — |
| 61 | 调试台审核面板（列表 / 放行 / 驳回），经网关代理 | **政策越过** | — |
| 62 | 审批闸门活体验收 `verify-refund-approval.ps1` + 矩阵 add-only 加步 | **政策越过** | — |
| 63 | 收口 | — | — |

**读数**：JVM `3 + 21 + 287 = 311` → **`5 + 29 + 290 = 324`** 绿；覆盖率 gateway 59.47% → 59.75%；指标名 **52 → 53**；活体 `verify-refund-approval.ps1` **7/7**、`verify-idempotency.ps1` exit 0、`verify-console.mjs` **40/40**、`verify-action-loop.ps1` **11/11**；矩阵 22 步 → 23 步。

### round20 — 修 22 步矩阵的四条红 · 2026-09-25 → 09-27

**三条走 ADR 0031 第 10 行的事实性修正豁免，一条走所有者政策覆盖**（ADR 0045 一条一条写明依据——既不把政策覆盖伪装成事实性修正，也不把豁免说成"必须开轮才能做"）。

根因取证最重要的结论是：**四条红里三条的根因不在被测代码**。

| 票 | 内容 | 依据 | ADR |
| --- | --- | --- | --- |
| — | round20 重开范围与两条依据的划分 | — | [0045](docs/adr/0045-round20-reopen-for-the-four-live-reds.md) |
| 53 | `feedback` 步：两条读数**在结构上不可能通过**（`Get-Counter` 被管道调用而函数无 `ValueFromPipeline` → 参数前移 → 恒返回 `0.0`）；`negative` 的刺激与自己的注释矛盾；退款用了不拥有该单的买家、幂等键写死 | 事实性修正 | — |
| 54 | `emotion` 步：`EMO-ESC-02` 的问句含 T0 升级词「转人工」，ADR 0042 之后落 `USER_REQUESTED` 是**正确行为**——把它重分类为"显式转人工"、新增 `EMO-ESC-09` 补位；**并修掉该步跑到第 9 条就因 401 中止**（队列反查缺 bearer） | 事实性修正 | — |
| 55 | `plansteps`：更正两处不实登记（引用了**不存在**的 `PlanExpressionTest`；`aborted` 是进程生命周期累计值），该步在 local 档登记为已知不达成——**判据一字不动** | 事实性修正 | — |
| 56 | `orderNo` 溯源守卫：模型自报的单号必须出自买家的话（`isUntrustedOrderNo` = 格式 + 溯源），复用既有 `askSlot` 出口 | **所有者政策覆盖** | — |

结果：22 步矩阵 **805 s、20 步绿 / 2 步红**（`action`、`emotion` 转绿；`feedback` 的 `implied_retry` 间歇、`plansteps` 在 local 档已知不达成——两步都按登记处置，没有一条是"看着红了就改判据"）。票 56 同时修掉了 gold `ACT-LOG-12` 的未达成，**gold 一字未改**。

### round19 — 可信性观测补齐 · 2026-09-24

**这是一次显式的政策覆盖，不是触发式重开。** ADR 0044 原文记录：六维度对仓复核（需求与架构匹配、任务规划、上下文工程、可观测性与评估、人机协同、业务闭环）发现缺口集中在「闭环的最后一公里」与「上下文-可观测的最后一格」；前者既非事实错误也非回归、也没命中 ADR 0030 五条触发线，按 ADR 0031 只登记不执行；后者按所有者政策覆盖开本轮的**旁挂轴**——范围限定「不碰判据、阈值、gold、已锁定 ADR 结论」，一轮一授权、不自动续期。与 ADR 0033/0041 同形。

| 票 | 内容 | ADR |
| --- | --- | --- |
| — | round19 重开范围、旁挂定义与登记节 | [0044](docs/adr/0044-round19-reopen-for-trust-observability.md) |
| 47 | embedding 段服务端计时器 —— 补分段耗时唯一盲区（此前只有向量化那一段没有计时器） | — |
| 48 | Plan 提升为一等记录（工具名/状态/耗时/参数），随 `done` 帧与同步响应输出 | — |
| 49 | `done` 帧携带上下文组成（规则块编号/历史轮数/估算 token），纯观测 | — |
| 50 | 显式输出上限 `max_tokens` / `options.num_predict`（此前全仓零命中） | — |
| 51 | `CONTEXT.md` 补 5 个 canonical term（38 → 43）+ 风格档位已知边界 | — |
| 52 | 登记收口：8 项登记不执行、证据换代、审计重锚 | — |

**八项登记不执行**（各带触发条件）：退款终态推进、工单 `RESOLVED` 回流消费点、敏感动作审批闸门、task 级判据、CI 覆盖活体数字、拦截率 74% vs 80% 裁决、上下文输入侧裁剪、知识反向沉淀自动咬合。前三项分别触碰 ADR 0030 第 2 条的未承诺边界、biz-mock 的业务终态语义与 ADR 0008 的 2 轮时延预算论证，不在旁挂定义内。

**三条未达成按实登记**：`verify-console.mjs` 新增的原始 SSE 流断言未真跑（需起栈 + Playwright）；全量 22 步活体矩阵本轮不跑；票 50 的「180 条 gold 未漂移」未验证。判据、阈值、gold 与 `judge()` 一字未动。

### round18 — 评分维度完备性（B8 数据层 + B9 测试体系）· 2026-09-21

**同样是显式的政策覆盖**（ADR 0041），对标《项目开发判断标准-三维度评分体系》补齐维度完备性。

| 票 | 内容 | ADR |
| --- | --- | --- |
| — | round18 重开范围与 B5 挂触发条件 | [0041](docs/adr/0041-round18-reopen-for-scoring-dimension-completeness.md) |
| 42 | Flyway V1 基线（Hibernate 导出后固化）+ `ddl-auto` 全档切 `validate` | — |
| 43 | 慢查询优化：V2 索引迁移 + EXPLAIN 前后对照 —— **落地一个索引、实测后否决另一个** | — |
| 44 | JaCoCo 覆盖率棘轮（LINE 设闸 / BRANCH 只报，按模块分别判定） | — |

CI 门禁从三步扩到四步（新增覆盖率棘轮）。该轮的负结果值得记：工单列表的 `tickets(tenant_id, created_at)` 索引四轮读数符号会翻转，**量不出收益的索引不进仓**。

### 回归修复 · 2026-09-23（票 45/46）

不在重开机制内——按 ADR 0031，事实错误与回归修复不受触发条件限制。

- 票 45 / [0042](docs/adr/0042-explicit-escalation-precedes-sentiment-verdict.md)：ADR 0034 的情绪门位于 INTAKE、先于意图判定，把一句平静的「转人工」判成 URGENT 后抢先落 `EMOTION_ESCALATION`，回归掉了 ADR 0017 的承诺。修法是把优先级定成**显式转人工优先**，不新增出口、不动 10 状态枚举
- 票 46 / [0043](docs/adr/0043-sentiment-second-layer-is-dev-only.md)：情绪门第二层 LLM 分类被误放到 `local`（实现比 ADR 0034 的措辞宽了一档），使 local 下每个未命中词表的请求都多一跳模型调用，「命中路径零模型调用」在这档不成立。修法是把第二层**限定为 dev 口径**

### round17 — 架构完整度重开 · 2026-09-19 → 09-20（已收口）

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
- 2026-09-20 本机全量 22 步活体验收首次真正跑起来（503 s、16 步绿 / 6 步红），六步红的四条根因登记为后续票的输入，**一条判据都没改**
- 2026-09-24 全量 22 步复测（**512 s、18 步绿 / 4 步红**）：`plan` / `hitzero` / `fallback` 三步转绿；`action` 是新出现的红，用「回退到 round19 起点重建后同样红」的对照实验定位为**先前就存在的问题、不是 round19 引入**（机制是本地 3B 模型照抄工具 schema 示例订单号，而格式校验拦不住格式合法的编造值）。同时更正一处归因：此前把活体阻塞记成 `OLLAMA_MAX_LOADED_MODELS=1`，实测报的是显存/主机内存瞬时争抢（`cudaMalloc failed` / `failed to allocate CUDA_Host buffer`），预热后两模型可同时驻留且该变量全程未改
- 2026-09-23 「降级原因 N 种」口径定案：枚举 10 / 降级 9 / 脚本确定性表 7 行是三个不同的集合，换算规则写进 README 验收对照段
- 2026-09-24 交叉核实外部清单（`D:\WorkBuddyData` 两份 HTML）：五处与仓内实测不符（含「Qdrant 距离度量未声明」不成立、ADR 30 实为 42、测试 170 实为 226、降级原因「仓内不一致」已过期、416 条场景分片是双算），登记进 tracker 与 `docs/EVIDENCE.md`

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
