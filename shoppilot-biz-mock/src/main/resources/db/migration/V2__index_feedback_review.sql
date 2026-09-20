-- 反馈复核队列的索引（round18 票 43 / ADR 0041）
--
-- 靶子是现场核实的真实调用路径：FeedbackService.java:41 的
-- findByReviewStatusOrderByCreatedAtDesc("PENDING") —— 谓词 review_status + 排序 created_at，
-- 两者此前都无索引。
--
-- 这是 V1 基线之后的第一笔真实增量，它的存在让「版本化迁移体系」是可演进的，而不是一次性基线。
--
-- 本票原本还打算给工单列表加 tickets(tenant_id, created_at)，**实测后否决、未落地**：
-- 那条查询取全列且无上界（一次取走某租户全部工单），走索引要逐行回表、没有覆盖能力，
-- 三次连跑 p50 一致比扫表差 10~45%。完整读数与归因见
-- docs/slow-query-optimization-2026-09-21.md，守卫与测量入口是 SlowQueryPlanTest。
--
-- 索引的真相源是本目录；实体上的 @Index 注解在 ddl-auto: validate 下不再被校验，只作文档。

create index idx_feedback_review on feedback (review_status, created_at);
