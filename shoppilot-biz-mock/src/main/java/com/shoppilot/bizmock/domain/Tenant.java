package com.shoppilot.bizmock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 租户 = 店铺（ADR 0004）。平台不作为租户，因此本表不带归属列。 */
@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** 店铺大促包量配额，供网关租户级限流读取。 */
    @Column(name = "rate_limit_qps", nullable = false)
    private int rateLimitQps;

    protected Tenant() {
    }

    public Tenant(String id, String name, int rateLimitQps) {
        this.id = id;
        this.name = name;
        this.rateLimitQps = rateLimitQps;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getRateLimitQps() {
        return rateLimitQps;
    }
}
