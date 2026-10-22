# ShopPilot Architecture

> 10 分钟版。当前数字与缺口看 [`STATUS.md`](STATUS.md)；逐条决策理由看 [`docs/adr/`](adr/)；历史走读看 [`design-walkthrough.md`](design-walkthrough.md)（historical）。

## 1. 它是什么 / 不是什么

**是**：一个单机可复现的客服对话运行时原型。买家一句话进入网关，经鉴权、意图、缓存、检索、有界工具循环，走到政策答复、业务办理或一张可查工单；退款等涉资动作必须人工放行。

**不是**：生产系统。没有真实商家数据、真实支付、真实 IdP、多实例验证或 SLA。官方产品演示默认 `demo`（意图感知模板）+ 真实 `bge-m3`；压测用 `perf`。两者衡量的都是编排层，不是模型能力。

## 2. 当前拓扑（以代码为准）

```
浏览器 / 飞书 / webhook / email
        |
        v
shoppilot-gateway :8082   （鉴权、限流、意图、缓存、检索、Agent、SSE、运维代理）
   |            |            |
   v            v            v
Redis        Qdrant + ES   biz-mock :8091（订单/退款/身份/审计）
                              |
                              v
                         ticket :8092（工单/分流/领取/SLA/出站）
```

- 独立部署的是 **3 个服务**：gateway / biz-mock / ticket；`shoppilot-tool-api` 是共享契约库。
- **knowledge 服务没有拆**（票 91 裁定不做，登记 R1）；身份域落在 biz-mock（ADR 0058）。
- 事件骨干真实存在的是 `audit` 与 `channel.outbound` 两条；`ticket.routing` 只有契约（ADR 0054/0055）。
- 这是有意的取舍：单机 6–7GB 的资源账下，再拆一个 JVM 换不到演示流量下的收益。**架构文档不再把它说成“四域已落地”。**

## 3. 五个关键决策

### 3.1 缓存放在意图判定之后（ADR 0003 / 0016）

- **决策**：先判意图，再查 L1/L2；动作意图不进缓存。
- **换回**：少拦约 6 个点；换来不跨意图串答（“怎么退款” vs “为什么不给退款”）。
- **代价/现状**：0.95 工作点上 L2 召回实测为 0，拦截全部来自 L1 精确哈希；极性守卫是给反义对补的词法闸，不是“L2 有效”的证据。**不要把它讲成多级语义缓存已生效。**
- **重开条件**：换 embedding / 两阶段召回 / 真实改写对能把 0.95 工作点召回做上去，再谈 L2 的价值。

### 3.2 有界工具循环 + 涉资人工闸（ADR 0008 / 0036 / 0047 / 0063 / 0069）

- **决策**：工具轮硬上限 4；计划 ≤4 步；读工具操作性失败只反思一次；写工具不反思；退款只提案（`PENDING_APPROVAL`），放行必须已验签坐席。
- **换回**：不追求“自由 Agent 直到完成”；用可预算的轮次换时延上限与可审计性。
- **代价/现状**：`AgentStateMachine` 是一个 1300+ 行的过程式编排类，新增守卫靠分支；状态枚举只用于 trace/SSE，不是转移表。重构计划见 §5。
- **重开条件**：出现真实三步以上且步间无依赖的意图，且工具 P99 > 500ms。

### 3.3 租户隔离的三道防线（ADR 0004 / 0005 / 0025）

- **决策**：租户只来自已验签令牌；查询由 `@TenantId` 拼谓词；跨租户一律 404（不确认存在）；会话键含“店铺 + 买家”。
- **现状**：这条是真落地的，有 JVM 用例与活体脚本。
- **已知边界**：`findById` 不自动拼租户，少数路径手写比对；`findOwned` 在 customerId 为空时退化为 `findById`（fail-open 边界）。任何新查询都必须显式过归属。

### 3.4 降级与可查工单（ADR 0009 / 0026）

- **决策**：降级不是 500，也不是一句“已转人工”；落在可反查的工单里，原因枚举可区分。
- **现状**：降级原因枚举 10（其中降级 9）；工单服务承载四类来源；出站结果回流只承诺“2xx 算送达”。
- **已知边界**：业务 Mock 进程整体消失时，审批单开不出来；本轮把退款工单调用移出事务并加缺失计数，**通用 outbox 仍未做**。

### 3.5 编排自研，只留 provider 缝（ADR 0032）

- **决策**：不引入 Spring AI / LangChain4j；`LlmClient` 一条缝，`OpenAiCompatibleLlmClient` / `OllamaLlmClient` / `MockLlmClient` / `DemoLlmClient` 四种实现。
- **换回**：没有两套循环、两处指标口径；代价是状态机、重试、反思、工具 schema 全部自己维护。
- **现状**：工具 schema 由 record 反射生成（`ToolContracts`），跨模块共用 DTO，这一点比框架更可控。

## 4. 失败模式（按“面试官会问”排序）

| 失败 | 现在的行为 | 证据/边界 |
|---|---|---|
| LLM 慢/挂 | 超时 → 熔断 → 降级 + 工单；工具轮有硬上限 | `FallbackReason` 10 枚举；perf 首字 300ms 是 Mock 下限 |
| 缓存冷启动雪崩 | 进程内向量 LRU + 同问句 SingleFlight；检索失败不算零命中 | 历史事故里 35264 → 18 次；单飞锁 TTL 仍短于 20s LLM 超时（登记） |
| 同一订单并发退款 | DB `active_order_id` 唯一索引 + 网关 Redisson 订单锁 | 本轮新增；`RefundInvariantTest` 绕过网关直接并发验证 |
| 工单服务不可达 | 退款仍提交，`ticketId` 空 + `shoppilot_refund_ticket_missing_total` | 资金正确性优先；补偿登记为 debt |
| ops 端点越权 | `/ops/**` 默认拒绝，仅 `/ops/tickets/mine` 买家白名单 | 本轮新增；`OpsAuthorizationInterceptorTest` |
| 真 Redis 事件断流 | 事故复盘后加了配置一致性判据；但 CI 仍不跑真 Redis | [`incident-2026-10-silent-failure-modes.md`](incident-2026-10-silent-failure-modes.md) |
| CI 绿但系统不通 | 测试大量 mock；没有 Testcontainers | 这是当前最大的验证缺口，不要用“600+ 条测试”替代“跑通” |

## 5. 已知缺口与重开条件

1. **`AgentStateMachine` 1300+ 行**：目标拆成 `TurnOrchestrator` + Retrieve/Plan/Execute/Reflect/Compose/Persist handlers，显式 `TurnContext`，每类 <400 行；触发 = 下一次要动工具循环语义。
2. **退款工单补偿**：当前 afterCommit + 计数，没有 outbox；触发 = 出现真实退款无工单。
3. **L2 零召回 / 双引擎打平**：保留但不再宣称收益；触发 = 换 embedding 或真实查询集能证明增益。
4. **单实例边界**：跨实例 SingleFlight 未验证；触发 = 出现第二实例部署。
5. **真实模型评测**：当前 180 条 gold 无 holdout；触发 = 需要对外承诺准确率之前。
6. **前端**：两个产品入口约 1400 行，无路由/状态库/单测；触发 = 产品面继续投入。

## 6. 怎么读这个仓

- 当前状态：[STATUS.md](STATUS.md)
- 请求链路与首改文件：[CODE_MAP.md](CODE_MAP.md)
- 决策历史：[docs/adr/](adr/)（70 篇；不是每篇都仍是现状）
- 证据与未达成：[EVIDENCE.md](EVIDENCE.md)（historical ledger；首屏只信 STATUS）
- 事故复盘：[incident-2026-10-silent-failure-modes.md](incident-2026-10-silent-failure-modes.md)
