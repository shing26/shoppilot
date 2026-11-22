package com.shoppilot.bizmock.domain;

import com.shoppilot.tool.identity.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.Duration;

/**
 * 账号（round25 票 80 / ADR 0056、0058）。
 *
 * <p>{@code subjectRef} 是「这个账号动作的对象」：买家指向 {@code customers.id}，
 * 坐席与管理员为空。它把身份与业务主体分开——同一个人可以既是买家又是坐席，
 * 那时要开两个账号而不是给一个账号塞两个主体，那样「他领的工单是谁的」就再也读不出来了。
 *
 * <p>口令只存 BCrypt 哈希，且**任何面向调用方的视图里都不带它**（见 {@code AccountView}）：
 * 令牌签发只需要「是谁、在哪家店、什么角色」。
 */
@Entity
@Table(name = "users", uniqueConstraints =
        @UniqueConstraint(name = "uk_users_tenant_username", columnNames = {"tenant_id", "username"}))
public class UserAccount {

    /** 账号状态。停用是「还查得到、但登不进来」，与删号不同——审计要能解释它什么时候不再有权限。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private UserRole role;

    @Column(name = "subject_ref", length = 32)
    private String subjectRef;

    @Column(name = "display_name", length = 64)
    private String displayName;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 口令版本（round56 票 224 / ADR 0086）：改密后 +1，进 access token 的 {@code pv}。
     *
     * <p>它让「改密前签发的令牌最迟在 TTL 内失效」有一个可判定的依据；默认 0 让既有账号
     * 无需回填数据。
     */
    @Column(name = "password_version", nullable = false)
    private int passwordVersion;

    /** 窗口内连续失败次数；成功登录或窗口过期后清零。 */
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    /** 锁定截止时间；为 null 表示未锁定。锁定期间正确口令也不放行。 */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    /** 最近一次失败时间，用来判「窗口是否已过」。 */
    @Column(name = "last_failed_at")
    private Instant lastFailedAt;

    protected UserAccount() {
    }

    public UserAccount(String id, String tenantId, String username, String passwordHash, UserRole role,
                       String subjectRef, String displayName, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.subjectRef = subjectRef;
        this.displayName = displayName;
        this.status = STATUS_ACTIVE;
        this.createdAt = createdAt;
    }

    public boolean active() {
        return STATUS_ACTIVE.equals(status);
    }

    public void disable() {
        this.status = STATUS_DISABLED;
    }

    public void enable() {
        this.status = STATUS_ACTIVE;
    }

    /** 改密：更新哈希并推进版本，旧令牌的 {@code pv} 随即落后。 */
    public void changePassword(String newHash) {
        this.passwordHash = newHash;
        this.passwordVersion += 1;
    }

    public void changeRole(UserRole newRole) {
        this.role = newRole;
    }

    public boolean lockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** 记一次失败；窗口已过则从 1 重新计。返回本次失败后的连续次数。 */
    public int recordFailure(Instant now, Duration window) {
        boolean windowExpired = lastFailedAt == null || lastFailedAt.plus(window).isBefore(now);
        this.failedAttempts = windowExpired ? 1 : this.failedAttempts + 1;
        this.lastFailedAt = now;
        return this.failedAttempts;
    }

    public void lockUntil(Instant until) {
        this.lockedUntil = until;
    }

    public void clearFailures() {
        this.failedAttempts = 0;
        this.lastFailedAt = null;
        this.lockedUntil = null;
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public String getSubjectRef() {
        return subjectRef;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getPasswordVersion() {
        return passwordVersion;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastFailedAt() {
        return lastFailedAt;
    }
}
