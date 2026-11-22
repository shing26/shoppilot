package com.shoppilot.bizmock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * 刷新令牌的一行（round56 票 224 / ADR 0086）。
 *
 * <p><b>只存 SHA-256 哈希，不存明文</b>：库被读走也换不出可用凭证。明文只在下发那一次
 * 出现在网关的 HttpOnly Cookie 里，业务侧与日志都不留。
 *
 * <p>{@code familyId} 把一次登录派生出的整条轮换链绑在一起：旧令牌被再次使用时，
 * 撤销的是整族，而不是只拒这一张——否则攻击者拿着同族里另一张仍然有效。
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @Column(name = "id", length = 40)
    private String id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "family_id", nullable = false, length = 40)
    private String familyId;

    @Column(name = "account_id", nullable = false, length = 32)
    private String accountId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by", length = 40)
    private String replacedBy;

    protected RefreshToken() {
    }

    public RefreshToken(String id, String tenantId, String familyId, String accountId, String tokenHash,
                        Instant issuedAt, Instant expiresAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.familyId = familyId;
        this.accountId = accountId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public boolean usable(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    public void markUsed(Instant now, String nextId) {
        this.usedAt = now;
        this.replacedBy = nextId;
    }

    public void revoke(Instant now) {
        if (this.revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getFamilyId() {
        return familyId;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
