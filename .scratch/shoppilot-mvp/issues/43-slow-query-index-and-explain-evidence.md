# 43 — 慢查询优化：V2 索引迁移 + EXPLAIN 前后对照证据

**What to build:** 给真实缺索引的查询补索引，并把这次加索引落成 `V2` 迁移（让迁移体系真的增量演进）；证据以 H2 `EXPLAIN` 前后对照为主、放大数据量后的重复耗时为辅，口径边界写进产物。

**Blocked by:** 票 42（`V2` 需要 Flyway 已在位）。

**Status:** implemented（2026-09-21）。**落地一个索引，否决一个**——否决是本票的实质结论之一，理由见下。

靶子（现场核实的真实路径，不是造的）：

| 查询 | 调用点 | 缺什么 | 处置 |
|---|---|---|---|
| `FeedbackRepository.findByReviewStatusOrderByCreatedAtDesc("PENDING")` | `FeedbackService.java:41`（反馈复核队列，ADR 0039 路径） | `feedback(review_status, created_at)` 无索引 | **落地**（V2） |
| `TicketRepository.findAllByOrderByCreatedAtDesc()` | `BizMockService.java:237`（工单列表） | `tickets` 上无可用索引 | **否决**（实测量不出收益） |

- [x] `db/migration/V2__index_feedback_review.sql`：`create index idx_feedback_review on feedback (review_status, created_at)`
- [x] `Feedback` 实体加对应 `@Index` 注解，并在类注释写明「`validate` 不校验索引，索引的真相源是 `db/migration`」
- [x] 新增 JVM 用例 `SlowQueryPlanTest`：断言索引在 `INFORMATION_SCHEMA.INDEXES` 里，且查询的 `EXPLAIN` 命中索引名
- [x] 变异对照：删掉 `V2` 后 `v2IndexesExist` 必须红（见 Handoff 的实测读数）
- [x] `docs/slow-query-optimization-2026-09-21.md`：前后 `EXPLAIN` 原文、四档选择性 × 四轮耗时读数、归因、口径边界、复现命令
- [x] 保留红值 + 登记触发条件（工单列表那条），不编数字、不改判据
- [ ] ~~`Ticket` 实体加 `@Index`~~ —— **未做**：索引被否决，注解也一并撤掉，改为在类注释里留下否决理由与重开条件

**Verify:**

```powershell
.\mvnw.cmd -B -ntp verify
.\mvnw.cmd -B -ntp -pl shoppilot-biz-mock -am -Dtest=SlowQueryPlanTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：全量全绿；聚焦复跑 3/3 绿；`ls shoppilot-biz-mock/src/main/resources/db/migration/` 出现 `V1__` 与 `V2__` 两支。

## Handoff notes

**关键决策**

- **否决 `tickets(tenant_id, created_at)` 是本票的实质产出，不是缩水。** 它确实把计划从 `TICKETS.tableScan` 变成 `IDX_TICKET_CREATED: TENANT_ID = 't1'`，但四个独立轮次的 p50 是 8353/6031/7596/5533 对 6659/5691/5630/6712——**三轮更差、一轮更好，符号会翻转**。一个量不出收益、还偶尔更差的索引不该进仓。这正是「有依据就加」的习惯要挡住的形状。
- 复核队列那个索引留下，理由写在产物文档里，共三条：谓词与排序键都被覆盖、真实队列规模下是持平而非变差、以及索引收益随表变大而上升（**最后这条本仓明确标注为未测量的外推**，不当成已量到的收益）。
- 索引必须与租户作用域的谓词对齐：单列 `tickets(created_at)` 被 H2 直接拒绝采用，改成 `(tenant_id, created_at)` 才进计划。这是量出来的。
- 测量方法本身踩过两个坑，都留在用例注释里：**只读结果集第一列会失真**（驱动不搬运宽行，工单列表 p50 从毫秒掉到微秒），**物化整个结果集会压崩分叉 JVM**（本机实测崩过两次，`hs_err_pid21032/28792`，系统物理内存与交换空间耗尽）。现在的写法是逐行读全列、不物化。

**验证落点**

- 全量：`.\mvnw.cmd -B -ntp verify` → `3 + 21 + 249 = 273` 绿（biz-mock 从 15 增到 21：票 42 的 3 条 + 本票 3 条）。
- 聚焦：`SlowQueryPlanTest` 3/3 绿。
- 变异对照：移走 `V2__index_feedback_review.sql` 后 `v2IndexesExist` 红。
- 原始读数：`shoppilot-biz-mock/target/slow-query-plan-readings.txt`（用例写出，非 stdout——surefire 对每个方法的输出捕获不可靠，实测同一个类里一个方法的 println 会整个丢失）。

**踩到的坑（值得记住）**

- **重命名 Flyway 迁移文件后必须清模块 target。** Maven 不会删陈旧资源，`target/classes/db/migration/` 里留着旧 `V2__*` 会让 Flyway 报 `Found more than one migration with version 2`，起栈即失败。本票从 `V2__index_feedback_review_and_ticket_created.sql` 改名时撞上过一次。
- Flyway 10 在 H2 上把历史表连表带列都建成**带引号的小写名**（`"flyway_schema_history"` / `"version"` / `"success"`）。裸写表名或列名都会 `not found`，查询必须加引号。

**你需要能当场回答的三个追问**

1. "为什么两个真实缺索引的查询只补了一个？" —— 因为另一个量不出收益。计划变好不等于耗时变好：那条查询取全列且无上界，走索引要逐行回表、没有覆盖能力，四轮读数符号会翻转。**没量到收益就不发**，同时把真正的修法（给查询加上界）登记为触发条件。
2. "内存库上量耗时有什么意义？" —— 绝对数值没有意义，产物文档里明写了这条边界。有意义的是两件事：计划形态从 `tableScan` 变成索引（可机器复现）、以及「索引必须与租户谓词对齐」这条设计结论。耗时读数只在同机同数据的前后比较里成立，而且用了四个独立轮次，不靠单轮。
3. "删掉 V2 会怎样？" —— `v2IndexesExist` 当场红；另外 `rejectedTicketListIndex` 里那笔被否决的测量仍在跑，它把「为什么没加另一个索引」变成可复跑的证据，而不是一句散文。
