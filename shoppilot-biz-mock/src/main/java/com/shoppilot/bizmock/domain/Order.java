package com.shoppilot.bizmock.domain;

import com.shoppilot.tool.view.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 订单。{@code tenantId} 标注为 {@link TenantId}，Hibernate 会在所有查询上自动拼接归属条件
 * （ADR 0005 防线二）；因此跨租户读取天然返回空集，语义上等同"未在本店找到该订单"。
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    /** 归属校验的第二条件（ADR 0004）：同一店铺内也要区分买家。 */
    @Column(name = "customer_id", nullable = false, length = 32)
    private String customerId;

    @Column(name = "amount_fen", nullable = false)
    private long amountFen;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "category_code", nullable = false, length = 32)
    private String categoryCode;

    @Column(name = "category_name", nullable = false, length = 64)
    private String categoryName;

    /**
     * 服务标以逗号串存储而非 @ElementCollection：5 万订单量下独立表会让 seed 与查询
     * 多出一倍往返，而这些标记只用于整体读写。
     */
    @Column(name = "service_flags", nullable = false, length = 128)
    private String serviceFlags = "";

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

    @Column(name = "address_version", nullable = false)
    private int addressVersion = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected Order() {
    }

    public Order(String id, String tenantId, String customerId, long amountFen, OrderStatus status,
                 String categoryCode, String categoryName, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.amountFen = amountFen;
        this.status = status;
        this.categoryCode = categoryCode;
        this.categoryName = categoryName;
        this.createdAt = createdAt;
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

    public long getAmountFen() {
        return amountFen;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public List<String> serviceFlagList() {
        if (serviceFlags == null || serviceFlags.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(Arrays.asList(serviceFlags.split(",")));
    }

    public void setServiceFlags(List<String> flags) {
        this.serviceFlags = flags == null ? "" : String.join(",", flags);
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public String getReceiverPhone() {
        return receiverPhone;
    }

    public void setReceiverPhone(String receiverPhone) {
        this.receiverPhone = receiverPhone;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getDistrict() {
        return district;
    }

    public void setDistrict(String district) {
        this.district = district;
    }

    public String getDetailAddress() {
        return detailAddress;
    }

    public void setDetailAddress(String detailAddress) {
        this.detailAddress = detailAddress;
    }

    public int getAddressVersion() {
        return addressVersion;
    }

    public int bumpAddressVersion() {
        return ++addressVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Instant paidAt) {
        this.paidAt = paidAt;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    public void setShippedAt(Instant shippedAt) {
        this.shippedAt = shippedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }
}
