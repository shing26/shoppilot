package com.shoppilot.bizmock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/** 人工工单（ADR 0009）。转人工必须有可查证落点，否则是假功能。 */
@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @Column(name = "id", length = 40)
    private String id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "customer_id", nullable = false, length = 32)
    private String customerId;

    @Column(name = "reason", nullable = false, length = 40)
    private String reason;

    @Column(name = "user_query", nullable = false, length = 512)
    private String userQuery;

    @Lob
    @Column(name = "transcript", nullable = false, length = 8000)
    private String transcript;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 队列排序标记：情绪升级单为 high（ADR 0034），其余降级为 null（原口径排队）。 */
    @Column(name = "priority", length = 10)
    private String priority;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Ticket() {
    }

    public Ticket(String id, String tenantId, String customerId, String reason, String userQuery,
                  String transcript, String status, Instant createdAt, String priority) {
        this.id = id;
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.reason = reason;
        this.userQuery = userQuery;
        this.transcript = transcript;
        this.status = status;
        this.createdAt = createdAt;
        this.priority = priority;
    }

    public String getPriority() {
        return priority;
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getReason() {
        return reason;
    }

    public String getUserQuery() {
        return userQuery;
    }

    public String getTranscript() {
        return transcript;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
