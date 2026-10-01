# 69 工单统一实体：三个混血体收敛为一张工单表

**Status:** implemented（2026-10-01）

## What to build

仓里「需要人工介入」是三个混血体：① 降级工单（`FallbackReason` → ticket 表）② 满意度复核队列（feedback）③ 退款审批队列（`Refund.PENDING_REVIEW`，round21 建）。本票建**一个统一工单实体**与**三种来源**，作为分流的分母（ADR 0055）。

- `Ticket` 实体：`id`、`tenantId`、`source`（`DEGRADE` / `FEEDBACK_REVIEW` / `REFUND_APPROVAL`）、`queue`、`priority`、`status`、`slaDeadline`、`assignee`、`payload`（JSON：降级原因/审核单号/反馈 id）、时间戳。
- Flyway **`V3__unified_ticket.sql`**（V1 基线、V2 复核队列索引已占）；`ddl-auto: validate` 不变 → 迁移缺表即启动红。
- 降级路径**直接写新表**（这就是渐进迁移，不做双写）；复核与退款审批切到新表，**旧表只读保留一版**。
- 写路径的租户隔离沿用既有三道防线（`@TenantId` + 仓储层 + 服务层显式比较——`findById` 不经 `@TenantId` 是本仓已登记的坑）。

## Blocked by

无（round23 第一票）。依据 ADR 0055，定位依据 ADR 0052。

## 口径（一个字都不能动）

README 公开的「降级原因 枚举 10 / 降级 9」以**降级工单数量**为口径之一。迁移后：新表 `source=DEGRADE` 的行数必须等于迁移前旧表行数；三个来源的映射关系写进 `docs/EVIDENCE.md`。**不得为了让数字好看而合并或去重工单。**

## 验收

- 一张工单表可查到三种来源，且每种来源都能从 `payload` 还原它原来的业务上下文。
- 迁移前后降级工单行数一致（迁移脚本自带对账查询，数字不符即红）。
- 旧表保留且只读；工单列表 API 按租户隔离，跨租户读取被拒。
- CONTEXT.md 补术语：工单、工单来源、队列、优先级、坐席、领取、SLA 计时（ADR 0055 Consequences）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify                    # 5 + 29 + 308 + N 全绿（gateway 覆盖率棘轮 63.23 不得掉）
pwsh -NoProfile -File scripts/check-ps-syntax.ps1   # 若动到 ps 脚本
python .scratch/shoppilot-mvp/round3-closeout-audit.py
```

新增用例至少覆盖：实体映射、租户隔离、来源枚举与 payload 往返、迁移后行数对账。

## Handoff notes

### 关键决策

1. **扩表而不是新建表**（与本票初稿的写法不同，按实现改）：初稿写「降级路径写新表、旧表只读保留」。落到代码上发现那样会让**分流规则表有两个分母**——降级在旧表、复核与审批在新表，而票 70 的规则表要读的是「全部待人工处理的东西」。所以 V3 是**把 `tickets` 扩成统一表**：加 `source`/`queue`/`assignee`/`sla_deadline`/`payload`，历史行回填 `DEGRADE`。`source` 一到位，迁移前后「降级工单」的分母就是同一个 `countBySource(DEGRADE)`，不需要任何口径修补。
2. **第四种来源 `CHANNEL_RECEIPT` 是实现期发现的**，ADR 0055 只枚举了三种。邮件回执（`reason=EMAIL_REPLY`，ADR 0035）本来就和降级单共用这张表、只靠 `reason` 区分；折进 `DEGRADE` 会把「渠道异步回执」和「买家转人工」混进同一个队列，正是 `EmailReceiptWriter` 注释里要防的事。故新增该枚举值并在 `TicketSource` 的 javadoc 里写明来历。**ADR 0055 本身一个字未动**（轮内不许改既有 ADR，见下）。
3. **payload 只在「有上游记录」时非空**：反馈复核指回 `feedbackId`，退款审批指回 `refundId`+`orderId`；降级单与渠道回执单自包含，上下文就在 `reason`/`userQuery`/`transcript` 三列里，不为了「字段一律有值」把已有列再抄一遍。退款那条额外加了 `refunds.ticket_id` 列而不是只靠 JSON——放行不可逆，责任链不能建立在解析字符串上。
4. **工单号生成器搬到 `Ticket.nextId`**（`BizMockService.nextTicketId` 保留为委托，`TicketIdTest` 一行未改）：要建一张工单的有三处（降级、复核、审批），序列只有一个实现才有意义。mix80 压测那段来历注释一起搬过去了。
5. **票外修复（一个既有缺陷）**：`findTicket`/`updateTicketStatus` 走 `findById`，而 `find(id)` **不拼接 `@TenantId` 谓词**——跨租户既能读别人的工单，也能改别人的工单状态。新用例 `workItemsAreTenantScoped` 当场把它揪出来（先红 200 → 修后 404）。处置与 round21 的 `reviewRefund` 同口径：显式比对租户，跨租户一律「不存在」。**这是本仓最不可协商的一条（串号 0 次），而它此前就开着。**

### 口径（一个数字都没动）

- README 的「降级原因 枚举 10 / 降级 9」靠 `source=DEGRADE` 保住；`TicketRepository.countBySource` 就是为此加的，用例钉死「新增三种来源后 DEGRADE 计数只随降级单增长」。
- gold 180 条、阈值、`judge()`、`verify_eval_judge.py`、CI 八步：**零改动**。
- 审计：`ROUND_FP` 重锚到 `6bc45f3`（round23 起点；连带解决「轮内给 ADR 0024 加换代指针 → B7 当场判红」）。`G6_EXPECT` **本票不动**——它读的是上次活体矩阵的落点（仍是 2026-09-28 那份 `5 + 29 + 308`），按定义此刻就该是 342；票 69 让当前实测变成 `5 + 36 + 308 = 349`，**换代在票 75 收口时连同新矩阵落点一起做**。提前改常数只会造出「你还没重跑矩阵」的红，而不是「防线失效」的红。收口审计本地读数 **PASS 83 / FAIL 3 / SKIP 9**（`A1`/`A2` 是未提交未推送，`F1c` 是 round20 那笔不可逆的本机日志删除）。

### 验证落点

- `.\mvnw.cmd -B -ntp verify` → **`5 + 36 + 308 = 349` 绿**（biz-mock 29 → 36，新增 7 条）。
- 覆盖率棘轮：biz-mock **79.30% → 79.88%**（门槛 76.0）、gateway 63.23%、tool-api 47.95%，`COVERAGE OK`。
- 新用例 `UnifiedTicketSourceTest` 7 条：降级/渠道回执两个来源、退款审批开单 + 回指、点踩开单而点赞不开、四来源同表 + 分母不被稀释、跨租户 404、V3 已应用且 `source` 为 NOT NULL、来源推导的默认回退。
- **变异对照**：`TicketSource.ofReason` 改成恒返回 `DEGRADE` → `degradeAndChannelReceiptAreDistinctSources` 转红（`expected "CHANNEL_RECEIPT" but was "DEGRADE"`），还原即绿。租户那条的对照更强：用例是先写的，它先红后绿。
- 全量 verify 顺带逮到一处真回归：`SlowQueryPlanTest`（round18 票 43 的慢查询证据）用手写 SQL 插工单，漏了新加的 `source` 非空列 → 已补 `source` 值。**这正是把默认值交给数据库而不是 ORM 的好处**。

### 现场三问

1. **为什么不新建表？** 新建表会让票 70 的规则表有两个分母（降级在旧表、复核与审批在新表），而「全部待人工处理的东西」必须是一张表才谈得上分派。扩表让历史行回填即等于迁移完成，分母不需要任何口径修补。
2. **为什么门内多了一种来源？** 邮件回执早就在这张表里、只靠 `reason` 区分。把它算作降级会让队列规则把「回复邮件」和「买家转人工」分到一处，而这正是那段代码注释要防的混淆。第四种来源是实现期发现，不是设计期遗漏。
3. **为什么工单号生成器要搬？** 建一张工单的地方从一处变成三处。序列只有一个实现才有意义；`TicketIdTest` 钉的是「同毫秒不撞主键」这个 mix80 压测换来的性质，搬实现不搬断言，测试一行未改。
