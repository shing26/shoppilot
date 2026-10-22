-- 2026-10-22 评审 P0：一单只允许一笔在办退款（数据库层不变量）
--
-- 此前 applyRefund 在事务外读订单判 refundable()，而退款唯一键只有
-- (order_id, idempotency_token)：不同 token 的并发请求都能通过状态检查，
-- 各插一笔退款。网关的 Redisson 订单锁是唯一防线；绕过网关、锁租约到期或
-- Redis 故障都会打开双退窗口。
--
-- 这里用一个可空唯一列做“部分唯一索引”的可移植替代：
--   active 状态 -> active_order_id = order_id
--   REJECTED   -> active_order_id = NULL（H2/Postgres 都允许多个 NULL）
-- 两个方言语义一致，避免 H2 不支持 partial index 的问题。
alter table refunds add column active_order_id varchar(32);

update refunds
   set active_order_id = order_id
 where status in ('PENDING_REVIEW', 'PROCESSING');

create unique index uk_refund_active_order on refunds (active_order_id);
