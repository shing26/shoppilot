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

/** 地址变更留痕，改地址是写操作，必须可回溯。 */
@Entity
@Table(name = "order_addresses", indexes = @Index(name = "idx_addr_order", columnList = "order_id"))
public class AddressHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "order_id", nullable = false, length = 32)
    private String orderId;

    @Column(name = "receiver_name", nullable = false, length = 64)
    private String receiverName;

    @Column(name = "receiver_phone", nullable = false, length = 20)
    private String receiverPhone;

    @Column(name = "province", nullable = false, length = 32)
    private String province;

    @Column(name = "city", nullable = false, length = 32)
    private String city;

    @Column(name = "district", nullable = false, length = 32)
    private String district;

    @Column(name = "detail_address", nullable = false, length = 255)
    private String detailAddress;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected AddressHistory() {
    }

    public AddressHistory(String tenantId, String orderId, String receiverName, String receiverPhone,
                          String province, String city, String district, String detailAddress,
                          int version, Instant changedAt) {
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.receiverName = receiverName;
        this.receiverPhone = receiverPhone;
        this.province = province;
        this.city = city;
        this.district = district;
        this.detailAddress = detailAddress;
        this.version = version;
        this.changedAt = changedAt;
    }
}
