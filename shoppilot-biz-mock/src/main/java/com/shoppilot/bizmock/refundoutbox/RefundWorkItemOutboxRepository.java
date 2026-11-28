package com.shoppilot.bizmock.refundoutbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 退款审批工单 outbox（round63 票 234 / U3）。
 *
 * <p>用 JdbcTemplate 而不是 JPA：补偿器要在**没有请求租户上下文**的调度线程里跨租户
 * 扫描待办，而 {@code @TenantId} 会把这类查询自动收窄到 PLATFORM。这里所有 SQL 都
 * 显式带 {@code tenant_id}，行里的租户只用于设置补偿调用所需的上下文。
 */
@Repository
public class RefundWorkItemOutboxRepository {

    private final JdbcTemplate jdbc;

    public RefundWorkItemOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 在退款受理事务内调用；调用方负责保证与 refunds / orders 同一事务。 */
    public void enqueue(String tenantId, long refundId, String orderId, String customerId, Instant now) {
        jdbc.update("""
                        insert into refund_work_item_outbox
                            (tenant_id, refund_id, order_id, customer_id, status, attempts,
                             next_attempt_at, created_at, updated_at)
                        values (?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                        """,
                tenantId, refundId, orderId, customerId, now, now, now);
    }

    public Optional<Row> findDueByRefundId(long refundId, String tenantId, Instant now) {
        return jdbc.query("""
                        select id, tenant_id, refund_id, order_id, customer_id, attempts
                          from refund_work_item_outbox
                         where refund_id = ? and tenant_id = ? and status = 'PENDING' and next_attempt_at <= ?
                        """,
                ROW_MAPPER, refundId, tenantId, now).stream().findFirst();
    }

    public List<Row> findDue(Instant now, int limit) {
        return jdbc.query("""
                        select id, tenant_id, refund_id, order_id, customer_id, attempts
                          from refund_work_item_outbox
                         where status = 'PENDING' and next_attempt_at <= ?
                         order by next_attempt_at, id
                         limit ?
                        """,
                ROW_MAPPER, now, limit);
    }

    /** 单行认领；两个补偿线程同时扫到同一行时只有一个 update 返回 1。 */
    public boolean claim(long id, Instant now) {
        return jdbc.update("""
                        update refund_work_item_outbox
                           set status = 'SENDING', updated_at = ?
                         where id = ? and status = 'PENDING' and next_attempt_at <= ?
                        """,
                now, id, now) == 1;
    }

    /** 进程在 SENDING 中崩溃后回收，交给下一轮重试；可能造成一次重复开单。 */
    public int reclaimStale(Instant staleBefore, Instant now) {
        return jdbc.update("""
                        update refund_work_item_outbox
                           set status = 'PENDING',
                               updated_at = ?,
                               last_error = 'SENDING lease expired; retry may duplicate a work item'
                         where status = 'SENDING' and updated_at < ?
                        """,
                now, staleBefore);
    }

    public void markRetry(long id, String tenantId, int attempts, Instant nextAttemptAt,
                          String lastError, Instant now) {
        jdbc.update("""
                        update refund_work_item_outbox
                           set status = 'PENDING', attempts = ?, next_attempt_at = ?,
                               last_error = ?, updated_at = ?
                         where id = ? and tenant_id = ? and status = 'SENDING'
                        """,
                attempts, nextAttemptAt, truncate(lastError), now, id, tenantId);
    }

    public void markSent(long id, String tenantId, String ticketId, Instant now) {
        jdbc.update("""
                        update refund_work_item_outbox
                           set status = 'SENT', ticket_id = ?, last_error = null, updated_at = ?
                         where id = ? and tenant_id = ? and status = 'SENDING'
                        """,
                ticketId, now, id, tenantId);
    }

    public void attachTicketToRefund(long refundId, String tenantId, String ticketId) {
        jdbc.update("update refunds set ticket_id = ? where id = ? and tenant_id = ?",
                ticketId, refundId, tenantId);
    }

    public void cancelByRefundId(long refundId, String tenantId, Instant now) {
        jdbc.update("""
                        update refund_work_item_outbox
                           set status = 'CANCELLED', updated_at = ?
                         where refund_id = ? and tenant_id = ? and status = 'PENDING'
                        """,
                now, refundId, tenantId);
    }

    /**
     * 补偿器取消**自己已认领**的行。
     *
     * <p>{@link #cancelByRefundId} 只看 {@code PENDING}：审核发生在补偿器认领之后时，
     * 它不能替另一个线程决定在途调用的命运。但补偿器自己读到退款已不是
     * {@code PENDING_REVIEW} 时，必须能把手里这行从 {@code SENDING} 收掉——
     * 否则租约回收会把它改回 {@code PENDING}，下一轮再认领、再取消 0 行，形成永不终止的循环。
     */
    public boolean cancelClaimed(long id, String tenantId, Instant now) {
        return jdbc.update("""
                        update refund_work_item_outbox
                           set status = 'CANCELLED', updated_at = ?
                         where id = ? and tenant_id = ? and status = 'SENDING'
                        """,
                now, id, tenantId) == 1;
    }

    public Optional<String> refundStatus(long refundId, String tenantId) {
        return jdbc.query("select status from refunds where id = ? and tenant_id = ?",
                (rs, rowNum) -> rs.getString("status"), refundId, tenantId).stream().findFirst();
    }

    public long pendingCount() {
        Long count = jdbc.queryForObject(
                "select count(*) from refund_work_item_outbox where status in ('PENDING', 'SENDING')",
                Long.class);
        return count == null ? 0L : count;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static final org.springframework.jdbc.core.RowMapper<Row> ROW_MAPPER =
            (rs, rowNum) -> new Row(
                    rs.getLong("id"),
                    rs.getString("tenant_id"),
                    rs.getLong("refund_id"),
                    rs.getString("order_id"),
                    rs.getString("customer_id"),
                    rs.getInt("attempts"));

    public record Row(long id, String tenantId, long refundId, String orderId,
                      String customerId, int attempts) {
    }
}
