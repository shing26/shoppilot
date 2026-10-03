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
 * 已被消费掉的审计事件（round23 票 71 / ADR 0056）。
 *
 * <p>{@code event_id} 的唯一约束就是幂等的**全部**实现。投递语义是至少一次，去重必须落在唯一索引上：
 * 应用层的「先查再插」在并发下必然漏，而漏掉的审计比重复的审计难查得多。
 */
@Entity
@Table(name = "audit_event", uniqueConstraints =
        @UniqueConstraint(name = "uk_audit_event_id", columnNames = {"event_id"}))
public class AuditEventRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Column(name = "object_type", nullable = false, length = 24)
    private String objectType;

    @Column(name = "object_id", nullable = false, length = 64)
    private String objectId;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "actor", nullable = false, length = 32)
    private String actor;

    /** actor 是否来自已验签的令牌（round25 票 82）。默认 false：往严的一边倒。 */
    @Column(name = "actor_authenticated", nullable = false)
    private boolean actorAuthenticated;

    @Column(name = "detail", length = 255)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "consumed_at", nullable = false)
    private Instant consumedAt;

    protected AuditEventRow() {
    }

    public AuditEventRow(String eventId, int schemaVersion, String action, String objectType, String objectId,
                         String tenantId, String actor, String detail, Instant occurredAt, Instant consumedAt) {
        this(eventId, schemaVersion, action, objectType, objectId, tenantId, actor, detail, occurredAt, consumedAt,
                false);
    }

    public AuditEventRow(String eventId, int schemaVersion, String action, String objectType, String objectId,
                         String tenantId, String actor, String detail, Instant occurredAt, Instant consumedAt,
                         boolean actorAuthenticated) {
        this.eventId = eventId;
        this.schemaVersion = schemaVersion;
        this.action = action;
        this.objectType = objectType;
        this.objectId = objectId;
        this.tenantId = tenantId;
        this.actor = actor;
        this.detail = detail;
        this.occurredAt = occurredAt;
        this.consumedAt = consumedAt;
        this.actorAuthenticated = actorAuthenticated;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getAction() {
        return action;
    }

    public String getObjectType() {
        return objectType;
    }

    public String getObjectId() {
        return objectId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getActor() {
        return actor;
    }

    public boolean isActorAuthenticated() {
        return actorAuthenticated;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }
}