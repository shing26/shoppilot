package com.shoppilot.bizmock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

/** 优惠券：支撑"满减怎么用"这类咨询落到具体券上。 */
@Entity
@Table(name = "coupons", indexes = @Index(name = "idx_coupon_customer", columnList = "customer_id"))
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "customer_id", nullable = false, length = 32)
    private String customerId;

    @Column(name = "rule_id", nullable = false, length = 64)
    private String ruleId;

    @Column(name = "threshold_fen", nullable = false)
    private long thresholdFen;

    @Column(name = "discount_fen", nullable = false)
    private long discountFen;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    protected Coupon() {
    }

    public Coupon(String tenantId, String customerId, String ruleId, long thresholdFen, long discountFen, String status) {
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.ruleId = ruleId;
        this.thresholdFen = thresholdFen;
        this.discountFen = discountFen;
        this.status = status;
    }
}
