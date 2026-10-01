# round23 Spec：工单域竖切（统一实体 → 分流 → 坐席工作台）

> 状态：**已拆票（2026-10-01）**，本轮范围 = program 的第一条端到端竖切。
> 依据：ADR 0052（定位）/ 0053（四域服务）/ 0054（事件骨干）/ 0055（工单与分流）/ 0057（前端）、
> program 路线 [`system-program-customer-service.md`](system-program-customer-service.md)。
> 身份域（ADR 0056）**不在本轮**，作为下一轮主体；本轮坐席台用现有 ops token 门控，缺口照登。
> agent 自主性升级（[`agent-autonomy-spec.md`](agent-autonomy-spec.md)）在本轮之后。

## 0. 所有者裁定（2026-10-01，票 69 收口后 grilling 六问）

下面六条是**所有者当场拍板**的，实现时不得重开；每条都改变了本 spec 初稿的写法，逐条在对应票面留偏离登记。

| # | 议题 | 裁定 | 对初稿的影响 |
|---|---|---|---|
| A | 本轮要不要活体验证 | **只交 JVM 层证据，活体一律按未达成登记** | 全栈约 7 GB、本机剩 0.5 GB，要跑就得停 nexus/opspilot/moa 的容器。连带：票 73 的浏览器断言、票 74 的分档机制在本轮都只能是纸面计划 |
| B | 工单数据归属 | **`tickets` 表随数据迁到工单服务自持**；降级单由网关改投工单服务，退款审批与反馈复核改调工单服务 API 落单 | 票 72 从「抽服务」变成「抽服务 + 迁数据 + 改三个生产者」。退款「受理→开单」跳进程后不再同事务：**审批的真源是 `refunds.PENDING_REVIEW`（审核队列读它），工单是受理侧的工作项**，那个窗口有界且不动资金安全 |
| C | 事件 topic 范围 | **只建 `audit`**（其生产端本轮就真实存在：退款放行/驳回、复核完成、规则变更）；`channel.outbound` 与 `ticket.created` **只落契约** | 票 71 从「三 topic 族」缩到一条真实现 + 两份契约。为没有消费者的 topic 造代码是本仓明禁的 |
| D | 0 token 门禁形态 | **接口 + 测试用内存实现**；生产实现走 Spring Data Redis Streams，内存实现只在测试包 | 「真实 Redis 上的行为」未验证，照登；「契约→发布→消费→ACK→幂等→pending 归零」的语义在 JVM 层钉死 |
| E | 前端构建产物 | **入库**到 `shoppilot-ticket/src/main/resources/static/` | **偏离 ADR 0057**（它写的是产物不入库）。理由：干净克隆 `./mvnw verify` 直接打出带界面的 jar，「干净克隆可复跑」这条更重 |
| F | 坐席登录缺口 | **保持登记**，本轮用 ops token 门控；身份域（ADR 0056）作为下一轮主体 | 本轮已无活体验证，再加一整个身份域就是又一批不可验证的代码 |

**A 决定了一条纪律**：71-75 的「未达成」栏会很诚实但很长——这是选择的结果，不是偷工。

## 1. 主线（一条竖切打到底）

**降级/审批/点踩产生工单 → 按规则表分流进队列 → 坐席在工作台领取 → 处理并回写 → 买家侧可读回状态。**

选它做第一条竖切的理由：它是 program 点名需求里**唯一能被完整证明**的一段（工单产生有历史、分流可对账、领取与回写有事件），且不依赖尚未落地的身份域。

## 2. 本轮不做什么（登记不执行）

- 身份域真账号（0056）——下一轮；本轮坐席台门控沿用 ops token，**缺口照登不得静默**。
- 渠道出站（`channel.outbound` 消费端）——本轮只立 topic 与契约，不做投递。
- 知识服务 / 业务与工具服务的服务化拆分——本轮不动。
- LLM 分流、自动派单 push（0055 已否决并登记）。
- 买家端新前端（0057 双入口的第二个入口）——本轮只做坐席工作台。

## 3. 票序（blockers-first）

| 票 | 范围 | Blocked by |
|---|---|---|
| [69](issues/69-unified-ticket-entity.md) | 工单统一实体 + 三来源 + Flyway V3 迁移 + 分母登记 | — |
| [70](issues/70-routing-rules-and-priority.md) | 分流规则表 + 优先级 + SLA 计时 | 69 |
| [71](issues/71-event-backbone-streams.md) | Redis Streams 三 topic 族 + 消费组 ACK + 幂等消费 | 69, 70 |
| [72](issues/72-ticket-agent-service.md) | 工单与坐席服务独立成模块（`shoppilot-ticket`，端口 8092） | 71 |
| [73](issues/73-agent-workspace-frontend.md) | 坐席工作台（Vite+Vue3 第一入口，CI 加前端构建步） | 72 |
| [74](issues/74-verification-tiering.md) | 验证分档（日常档/全栈档）+ 事件对账门禁 | 71, 72 |
| [75](issues/75-round23-closeout.md) | 收口（EVIDENCE/CODE_MAP/README/DELIVERY/CHANGELOG/审计常数） | 69-74 |

## 4. 硬约束（继承 program 与 ADR）

- **一条判据都没动**：gold 180 条、阈值、`judge()`、降级枚举 10 / 降级 9 的公开口径，一个字不改。
- **本机资源**：全栈约 7 GB、日常档子集约 3.5 GB；**全栈档仅清场日**，不得为跑全栈停别的项目容器。
- **门禁纪律**：0 token、可机器复跑（ADR 0024 的筛子在 program 期间继续有效——见 0052 换代指针）。
- **迁移纪律**：`ddl-auto: validate` 全档不变 → 新表必须走 Flyway 迁移，否则启动当场红。
