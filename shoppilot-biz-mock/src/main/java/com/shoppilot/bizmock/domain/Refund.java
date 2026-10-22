package com.shoppilot.bizmock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * 退款单。唯一约束 {@code (order_id, idempotency_token)} 是幂等的最后一道防线：
 * Redis 不可用时也必须拦住重复退款（ticket 12）。
 */
@Entity
@Table(name = "refunds", uniqueConstraints = @UniqueConstraint(name = "uk_refund_idempotency", columnNames = {"order_id", "idempotency_token"}))
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "order_id", nullable = false, length = 32)
    private String orderId;

    @Column(name = "customer_id", nullable = false, length = 32)
    private String customerId;

    @Column(name = "amount_fen", nullable = false)
    private long amountFen;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "idempotency_token", nullable = false, length = 64)
    private String idempotencyToken;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 审批工单号（round23 票 69 / ADR 0055）。受理即建工单，回指放在这一列而不是只塞进
     * 工单的 payload：放行是不可逆的资金动作，它的责任链不能建立在解析 JSON 上。
     */
    @Column(name = "ticket_id", length = 40)
    private String ticketId;

    /**
     * 在办退款的订单号（2026-10-22 评审 P0）。active 状态等于 {@code orderId}，
     * 驳回置空；数据库唯一索引 {@code uk_refund_active_order} 因此保证
     * 「一单最多一笔在办退款」。可空唯一列是 partial index 的 H2/PG 可移植替代。
     */
    @Column(name = "active_order_id", length = 32)
    private String activeOrderId;

    protected Refund() {
    }

    public Refund(String tenantId, String orderId, String customerId, long amountFen, String reason,
                  String idempotencyToken, String status, Instant createdAt) {
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.customerId = customerId;
        this.amountFen = amountFen;
        this.reason = reason;
        this.idempotencyToken = idempotencyToken;
        this.status = status;
        this.activeOrderId = active(status) ? orderId : null;
        this.createdAt = createdAt;
    }

    private static boolean active(String status) {
        return "PENDING_REVIEW".equals(status) || "PROCESSING".equals(status);
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getOrderId() {
        return orderId;
    }

    public long getAmountFen() {
        return amountFen;
    }

    /** 审核者要看的「为什么退」（票 61 的面板此前看不到它，等于让人凭金额拍板）。 */
    public String getReason() {
        return reason;
    }

    public String getIdempotencyToken() {
        return idempotencyToken;
    }

    public String getStatus() {
        return status;
    }

    /**
     * 只由审核动作推进（ADR 0047）：受理后 {@code PENDING_REVIEW} → {@code PROCESSING}/{@code REJECTED}。
     * 状态迁移同时维护在办唯一键：驳回释放 order，放行保持占用。
     */
    public void setStatus(String status) {
        this.status = status;
        this.activeOrderId = active(status) ? orderId : null;
    }

    public String getActiveOrderId() {
        return activeOrderId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getTicketId() {
        return ticketId;
    }

    public void setTicketId(String ticketId) {
        this.ticketId = ticketId;
    }
}
