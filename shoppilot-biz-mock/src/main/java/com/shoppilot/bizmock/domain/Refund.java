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
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public long getAmountFen() {
        return amountFen;
    }

    public String getIdempotencyToken() {
        return idempotencyToken;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
