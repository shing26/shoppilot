# 票 194：复核闭环（复核结单 + 按状态读 + 去复核入口）

Round: 46（spec：[round46-spec-ux-p1-fixes.md](../round46-spec-ux-p1-fixes.md)）
ADR: 0081　Status: implemented（2026-11-03）　Blocked by: 无

## What to build

1. **biz-mock 数据**：`feedback` 增加 `review_ticket_id`（Flyway **V11**，H2 与 PG 双侧）。
   点踩开复核单后把单号写回该列。`FeedbackView` 增 `reviewTicketId`（只增字段）。
2. **复核顺手结单**：`WorkItemClient` 增 `resolve(ticketId, Actor, note)`；生产实现 POST
   `/api/tickets/{id}/resolve`（带内部令牌、租户与 `X-Actor*`）。`markReviewed` 成功后调用；
   失败只 warn，不阻断复核。测试替身（`InMemoryWorkItemClient`）记录 resolved 供断言。
3. **按状态读反馈**：biz-mock `GET /api/feedback/review-queue?status=`（默认 PENDING，
   只接受 PENDING/REVIEWED/NONE，非法值 400）；网关 `/ops/feedback/review-queue` 透传 `status`。
4. **工作台**：`feedbackReviewQueue(session, status)`；刷新时并行取 PENDING 与 REVIEWED；
   「已复核 · 可附稿」以服务端 REVIEWED 为准，已附过稿的按 `revisions.feedbackId` 过滤；
   工单详情对 `source=FEEDBACK_REVIEW` 的票显示「去复核这条反馈」（跳知识视图）。
5. **测试**：biz-mock 新增/扩展用例——status 过滤、复核结单（测试替身断言 resolve 被调用且
   工单号写回）、非法 status 400。

## Blocked by / Verify

无。`bash ./mvnw -B -ntp verify -Dspring-boot.repackage.skip=true`；前端两端 typecheck/build；
活体：点踩 → 复核 → 工单 RESOLVED、刷新后仍可附稿。

## 验收项

- 点踩后 `feedback.review_ticket_id` 非空；复核后该工单 `RESOLVED`，结单意见指向复核。
- 不传 `status` 仍是 PENDING；`status=REVIEWED` 返回已复核；非法值 400。
- 工作台刷新后 REVIEWED 反馈仍在可附稿列表；已附稿的不重复出现。
- 复核单详情有「去复核这条反馈」入口。
- 既有 768 条 JVM 用例不回归。

## Handoff notes

### 关键决策

- **复核单号存进反馈行**（`feedback.review_ticket_id`，V11，H2 与 PG 同一句
  `alter table feedback add column review_ticket_id varchar(40)`）。不靠扫工单 `payload`
  反查：复核时要的是「这张反馈开出的那一张单」，扫全店工单既慢又会撞上别人开过的同反馈单。
- **结单失败只 warn，不回滚复核**。真源是 `feedback.review_status`；工单只是工作项。
  常见失败是复核单已被别的坐席领取（工单服务要求 `assignee == actor`），那种情况留给运营
  在队列里人工收尾，而不是把一次成功的复核判成失败。
- **`status` 是白名单**（`PENDING` / `REVIEWED` / `NONE`，默认 `PENDING`），非法值 400。
  不写成「按任意字符串过滤」——静默返回空列表会让前端以为「没有已复核项」。
- **工作台「可附稿」= 服务端 REVIEWED − 已附过稿的**（按 `revisions.feedbackId` 过滤，
  只看非 `REJECTED` 的修订：驳回意味着这一稿作废，反馈应回到可重新附稿）。
  这替掉了 round44 票 190 只在内存里维护的副本（刷新即失联）。
- **「去复核」入口只在 `payload.feedbackId` 能解析时出现**，解析不出就不画按钮，不猜单号。

### 验证落点

- JVM：`bash ./mvnw -B -ntp verify -Dspring-boot.repackage.skip=true` BUILD SUCCESS，
  **771** 条（biz-mock 12 + gateway 92 + 其余 617 + 50），比票前 768 多 3 条：
  `FeedbackReviewQueueTest.queueCanBeReadByStatus`、
  `WorkItemProducerTest.reviewClosesTheReviewWorkItem`、
  `WorkItemProducerTest.reviewSucceedsEvenWhenTheReviewTicketCannotBeClosed`。
- 前端：两端 `npm run typecheck` / `npm run build`，产物入库。
- 活体（`.scratch/ux-probe/verify-p1.mjs` 的 `reviewLoop`）：点踩 ack `reviewQueued: true`，
  反馈 `FB1793682631581-0`，复核单 `T1793682631661-0`（`source=FEEDBACK_REVIEW`）复核后
  **RESOLVED**；刷新后仍在「已复核 · 可附稿」；工单详情出现「去复核这条反馈」，
  跳转后备注为「来自工单队列：在待复核列表里找到反馈 …，复核后再附稿」。

### 三个现场追问

1. **存量不回填。** 本票之前复核过、但复核单仍 OPEN 的历史数据保持原样（演示库可重置）。
   要回填就得写一次性迁移脚本，靠 `payload.feedbackId` 猜单号——正是本轮拒绝的做法。
2. **结单与复核没有跨进程事务。** 工单服务不可用时，反馈已是 REVIEWED、复核单仍 OPEN，
   需要人工在队列里关。`warn` 日志里只有单号与反馈 id，没有重试队列。
3. **「驳回后重新附稿」是刻意行为，但没有界面提示。** 修订驳回后，该反馈会重新出现在
   「已复核 · 可附稿」，页面上只写着「给这条附稿」。运营可能以为是重复数据；
   是否要在行上标注「上一稿已驳回」，取决于是否把驳回当作常见路径。
