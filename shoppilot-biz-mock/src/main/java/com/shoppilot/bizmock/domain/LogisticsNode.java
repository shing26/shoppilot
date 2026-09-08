package com.shoppilot.bizmock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/** 物流轨迹节点。归属列与订单一致，跨店读取同样被自动过滤。 */
@Entity
@Table(name = "logistics", indexes = @Index(name = "idx_logistics_order", columnList = "order_id"))
public class LogisticsNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "order_id", nullable = false, length = 32)
    private String orderId;

    @Column(name = "cp_code", nullable = false, length = 32)
    private String cpCode;

    @Column(name = "cp_name", nullable = false, length = 64)
    private String cpName;

    @Column(name = "tracking_no", nullable = false, length = 64)
    private String trackingNo;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "node_code", nullable = false, length = 32)
    private String nodeCode;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected LogisticsNode() {
    }

    public LogisticsNode(String tenantId, String orderId, String cpCode, String cpName, String trackingNo,
                         int seq, String nodeCode, String description, Instant occurredAt) {
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.cpCode = cpCode;
        this.cpName = cpName;
        this.trackingNo = trackingNo;
        this.seq = seq;
        this.nodeCode = nodeCode;
        this.description = description;
        this.occurredAt = occurredAt;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getCpCode() {
        return cpCode;
    }

    public String getCpName() {
        return cpName;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public int getSeq() {
        return seq;
    }

    public String getNodeCode() {
        return nodeCode;
    }

    public String getDescription() {
        return description;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
