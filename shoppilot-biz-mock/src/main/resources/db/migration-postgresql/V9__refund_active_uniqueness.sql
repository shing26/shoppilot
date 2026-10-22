-- 2026-10-22 评审 P0：一单只允许一笔在办退款（PostgreSQL 侧）
--
-- 与 H2 侧 V9 逐字同义；两侧版本钟由 PostgresMigrationParityTest 钉住。
-- 可空唯一列做“部分唯一索引”的可移植替代，理由见 H2 文件头。
alter table refunds add column active_order_id varchar(32);

update refunds
   set active_order_id = order_id
 where status in ('PENDING_REVIEW', 'PROCESSING');

create unique index uk_refund_active_order on refunds (active_order_id);
