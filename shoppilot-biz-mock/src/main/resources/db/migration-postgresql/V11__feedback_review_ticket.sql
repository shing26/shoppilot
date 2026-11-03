-- 版本占位（PostgreSQL 侧，round46 票 194 / ADR 0081）。
--
-- H2 侧 V11 的 feedback.review_ticket_id 在 PG 侧按真实 DDL 建出（不是空迁移）：
-- 可空 varchar(40)，与实体 @Column(length = 40) 对齐。
alter table feedback add column review_ticket_id varchar(40);
