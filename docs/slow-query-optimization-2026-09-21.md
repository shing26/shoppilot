# 慢查询优化前后对照（round18 票 43 / ADR 0041）

> 基线：`fb8eacf` 之后的 round18 工作区｜测量日期：2026-09-21｜测量入口：`SlowQueryPlanTest`
> 原始读数：`shoppilot-biz-mock/target/slow-query-plan-readings.txt`（由用例写出，本文件是它的抄录）

## 结论先说

**计划形态的改善是确凿的，耗时的改善没有量出来。** 本票因此只落了一个索引，另一个实测后否决。

| 查询 | 计划变化 | 耗时 | 处置 |
|---|---|---|---|
| 反馈复核队列 | `FEEDBACK.tableScan` → `IDX_FEEDBACK_REVIEW` | 待办 ≤1% 时四轮持平；10% 时四轮一致变差 | **落地**（V2） |
| 工单列表 | `TICKETS.tableScan` → `IDX_TICKET_CREATED` | 四轮里三轮更差、一轮更好，**符号会翻转** | **否决，不落地** |

两条都不是「编不出数字就换个判据」，而是按票 43 第 6 条照登：保留红值、写清归因与触发条件。

## 靶子（现场核实的真实调用路径）

| 查询 | 调用点 | 此前缺什么 |
|---|---|---|
| `FeedbackRepository.findByReviewStatusOrderByCreatedAtDesc("PENDING")` | `FeedbackService.java:41`（反馈复核队列，ADR 0039 路径） | `feedback(review_status, created_at)` 无索引 |
| `TicketRepository.findAllByOrderByCreatedAtDesc()` | `BizMockService.java:237`（工单列表） | `tickets` 上无可用索引 |

## 索引设计的一个实测发现

第一版给工单列表配的是 `tickets(created_at)` 单列索引，**H2 直接拒绝采用**——计划仍是 `TICKETS.tableScan`。原因是这条查询的谓词是 `@TenantId` 拼出来的 `tenant_id = ?`：单列 `created_at` 索引既不能过滤，走它还要对每一行回表取列，代价模型判定不如顺序扫表。

改成 `tickets(tenant_id, created_at)` 后计划立刻变成 `IDX_TICKET_CREATED: TENANT_ID = 't1'`。**索引必须与租户作用域的谓词对齐**——这是量出来的，不是推出来的。

## 测量口径

- 两万行（`feedback` 与 `tickets` 各两万），灌在独立内存库 `jdbc:h2:mem:slowquery` 里，不污染共享的 `mem:shoppilot`。
- 查询用 `select *` 而不是 `select id`：Hibernate 生成的是全列 select，只取 id 时索引可能变成覆盖索引，量出来的就不是真实查询的账。
- 读结果集时**逐行读每一列但不物化**：等价于 `queryForList` 在数据库侧的全部工作，但不把两万行 × 十一列（`feedback.rule_ids` 还是 CLOB）攒成 `List<Map>`——那会在内存紧张的机器上把分叉 JVM 压崩（本机实测崩过两次，`hs_err_pid21032/28792`，归因是系统物理内存与交换空间耗尽）。只读第一列同样是失真：驱动不必搬运宽行，工单列表的 p50 会从毫秒量级掉到微秒量级。
- 「前」态不是另跑一次删掉迁移，而是**同一个进程里先量有索引、再 `drop index` 量无索引**——两次读数共享同一份数据与同一台机器状态。用例结束时索引已恢复。
- 复核队列扫四档选择性（1/10、1/100、1/1000、1/10000），因为单点说明不了索引收益随选择性的变化。
- 分位：复核队列 200 次采样，工单列表 100 次。工单列表单次毫秒量级，30 次采样时 p95 会在 99ms 与 372ms 之间跳，采样不够的 p95 只是某个离群点。
- **四个独立轮次**的读数都记在下面。单轮读数不足以支撑结论——这正是工单列表那条被否掉的原因。

## 耗时读数（四个独立轮次）

### 反馈复核队列，两万行

| 待办行数 | 比例 | 前 p50（四轮） | 后 p50（四轮） | 判定 |
|---|---|---|---|---|
| 2000 | 1/10 | 649 / 611 / 632 / 703 | **902 / 672 / 732 / 1219** | 四轮一致变差 |
| 200 | 1/100 | 65 / 72 / 75 / 79 | 70 / 65 / 65 / 79 | 持平 |
| 20 | 1/1000 | 24 / 24 / 21 / 24 | 23 / 22 / 23 / 25 | 持平 |
| 2 | 1/10000 | 15 / 15 / 17 / 18 | 16 / 17 / 15 / 19 | 持平 |

单位微秒。**待办越少（结果集越小），索引越是白搭；待办多到 10% 时它一致地更差。**

### 工单列表，两万行

| 轮次 | 前 p50 | 后 p50 | 方向 |
|---|---|---|---|
| A | 6659 | 8353 | 后更差 |
| B | 5691 | 6031 | 后更差 |
| C | 5630 | 7596 | 后更差 |
| D | 6712 | 5533 | **后更好** |

单位微秒。**符号会翻转，等于量不出差别。** 一个量不出收益、还偶尔更差的索引不该进仓——这是本票否决它的直接理由，与「有依据就加」的习惯相反。

## EXPLAIN 前后原文

### 反馈复核队列（1/100 档，两万行里 200 行 PENDING）

前（无索引）：

```
SELECT "PUBLIC"."FEEDBACK"."CREATED_AT", | "PUBLIC"."FEEDBACK"."VERDICT", | "PUBLIC"."FEEDBACK"."REVIEW_STATUS", | "PUBLIC"."FEEDBACK"."CUSTOMER_ID", | "PUBLIC"."FEEDBACK"."TENANT_ID", | "PUBLIC"."FEEDBACK"."ID", | "PUBLIC"."FEEDBACK"."TICKET_ID", | "PUBLIC"."FEEDBACK"."CONVERSATION_ID", | "PUBLIC"."FEEDBACK"."SIGNALS", | "PUBLIC"."FEEDBACK"."REASON", | "PUBLIC"."FEEDBACK"."RULE_IDS" | FROM "PUBLIC"."FEEDBACK" | /* PUBLIC.FEEDBACK.tableScan */ | WHERE ("TENANT_ID" = 't1') | AND ("REVIEW_STATUS" = 'PENDING') | ORDER BY 1 DESC
```

后（有 `IDX_FEEDBACK_REVIEW`）：

```
SELECT "PUBLIC"."FEEDBACK"."CREATED_AT", | ...（同前，略）... | FROM "PUBLIC"."FEEDBACK" | /* PUBLIC.IDX_FEEDBACK_REVIEW: REVIEW_STATUS = 'PENDING' */ | WHERE ("TENANT_ID" = 't1') | AND ("REVIEW_STATUS" = 'PENDING') | ORDER BY 1 DESC
```

### 工单列表（两万行）

前：`... FROM "PUBLIC"."TICKETS" | /* PUBLIC.TICKETS.tableScan */ | WHERE "TENANT_ID" = 't1' | ORDER BY 1 DESC`

后：`... FROM "PUBLIC"."TICKETS" | /* PUBLIC.IDX_TICKET_CREATED: TENANT_ID = 't1' */ | WHERE "TENANT_ID" = 't1' | ORDER BY 1 DESC`

## 归因（为什么计划变好而耗时没变好）

1. **H2 是内存库，顺序扫表近乎免费。** 两万行的 `tableScan` 在 1/100 档只要 65~79us，索引路径省下的读取量在内存里本来就不值钱。
2. **两条查询都取全列，索引没有覆盖能力。** 走索引要按索引项逐行回表取列，等于把顺序访问换成随机访问；省下的过滤成本抵不过回表成本。这也是工单列表在 1/10 档一致变差的原因。
3. **耗时由结果集主导，不由表大小主导。** 复核队列四档读数说明这件事：前态 p50 从 703us 掉到 18us（差约 40 倍），而表大小一直是两万行。返回行数才是成本主项，索引改不了它。

## 为什么还是留下了复核队列那个索引

不是因为「有索引总比没有好」，而是三条具体理由：

1. 它的谓词（`review_status`）与排序键（`created_at`）**都**被索引覆盖，是这条查询形状的标准解；计划形态变化证明优化器认它。
2. 在真实队列规模（待办占少数）下它是**持平而非变差**——没有代价。
3. 本仓能测的只有内存库。索引收益随表变大而上升、随顺序扫描变贵而上升，而 `feedback` 是随使用无界增长的表。**这条外推是本仓明确标注为未测量的部分**，不是已经量到的收益。

## 口径边界

- **H2 内存库的绝对耗时不代表生产。** 这里能站住的是**计划形态**（`tableScan` → 索引，可机器复现）、**索引必须与租户谓词对齐**这条设计结论，以及上面那张四轮表；耗时读数只在「同机同数据、前后两次」这个比较里成立。
- **本仓不宣称这两个索引带来了延迟收益。** 没有挑档位、没有挑分位、没有只报一轮。
- 工单列表查询还有一个与本票无关的独立缺陷：`findAllByOrderByCreatedAtDesc()` **无上界**，一次取走某租户的全部工单。这才是它的真问题——有上界之后，`tickets(tenant_id, created_at)` 才会真正开始有价值（排序与翻页都靠它）。改查询形状是功能改动，不在本轮范围。

## 复现

```powershell
.\mvnw.cmd -B -ntp -pl shoppilot-biz-mock -am -Dtest=SlowQueryPlanTest -Dsurefire.failIfNoSpecifiedTests=false test
type shoppilot-biz-mock\target\slow-query-plan-readings.txt
```

0 token、无外部依赖（只用进程内 H2）。用例同时是守卫：删掉 `V2__index_feedback_review.sql` 后 `v2IndexesExist` 当场变红；被否决的那笔优化留在 `rejectedTicketListIndex` 里，免得下一个人再试一遍同一个想法。

## 登记的重开条件

**触发 = 给 `findAllByOrderByCreatedAtDesc` 加上界，或本仓接上磁盘型数据库（或数据量升到内存扫表不再免费的量级）。** 触发时重跑上面的命令、用同一套四轮口径复测：

- 有上界之后，重新评估 `tickets(tenant_id, created_at)`——它届时才有可量到的收益，若仍量不出就继续不加。
- 复核队列那个索引届时若仍无收益，应当删掉而不是继续保留。

在那之前，本条作为已知的负面读数挂在票 43 的账上。
