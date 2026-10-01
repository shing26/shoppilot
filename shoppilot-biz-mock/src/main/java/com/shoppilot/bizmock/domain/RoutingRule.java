package com.shoppilot.bizmock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 分流规则（round23 票 70 / ADR 0055）：意图/情绪/租户 → 队列。**数据不是代码**——
 * 改队列或改 SLA 是一次数据变更，不是一次发版。
 *
 * <p>匹配键是「租户 × 工单来源 × 降级原因」，三列都可以是通配 {@code '*'}。
 * 之所以不用字面写的「意图」：落单那一刻 intent 已经是 ESCALATE，
 * 详见 {@code V4__routing_rules_and_sla.sql} 里的偏离说明。
 *
 * <p>规则表**不按租户切数据**：它只有几行，读全表在内存里挑最具体的那条。
 * 为此建租户索引是拿一种不存在的查询（一次读几行）去换另一种（每租户扫全表）。
 */
@Entity
@Table(name = "routing_rules")
public class RoutingRule {

    public static final String ANY = "*";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "source", nullable = false, length = 24)
    private String source;

    @Column(name = "reason", nullable = false, length = 40)
    private String reason;

    @Column(name = "queue", nullable = false, length = 32)
    private String queue;

    @Column(name = "priority", nullable = false, length = 10)
    private String priority;

    @Column(name = "sla_minutes", nullable = false)
    private int slaMinutes;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 谁改的。规则变更不写审计事件那一层随票 71 的事件骨干接上，这两列先留痕。 */
    @Column(name = "updated_by", length = 32)
    private String updatedBy;

    protected RoutingRule() {
    }

    public RoutingRule(String tenantId, String source, String reason, String queue, TicketPriority priority,
                       int slaMinutes, boolean enabled, Instant updatedAt, String updatedBy) {
        this.tenantId = tenantId;
        this.source = source;
        this.reason = reason;
        this.queue = queue;
        this.priority = priority.literal();
        this.slaMinutes = slaMinutes;
        this.enabled = enabled;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    /**
     * 是否命中这一行：通配符命中，其余逐列精确比。
     *
     * <p>租户这一列是**硬条件**而不是打分项——A 店的规则绝不能因为来源和原因碰巧对上
     * 就去劫持 B 店的工单。所以「不匹配」和「更不具体」是两种不同的失格方式。
     */
    public boolean matches(String tenantId, TicketSource source, String reason) {
        return hitsTenant(tenantId) && hitsSource(source) && hitsReason(reason);
    }

    private boolean hitsTenant(String tenantId) {
        return ANY.equals(this.tenantId) || this.tenantId.equals(tenantId);
    }

    private boolean hitsSource(TicketSource source) {
        return ANY.equals(this.source) || source.name().equals(this.source);
    }

    private boolean hitsReason(String reason) {
        return ANY.equals(this.reason) || (reason != null && this.reason.equals(reason));
    }

    /**
     * 具体度评分：**只在已命中的前提下**比较谁更具体。
     *
     * <p>租户精确命中权重最高（同一队列里不同店的 SLA 不一样），其次是来源，再次是原因。
     * 同分时按 id 升序，保证同一份规则表每次都选出同一行——分派不可复现的话，
     * 「为什么这张单去了那个队列」就永远答不上来。
     */
    public int specificityFor(String tenantId, TicketSource source, String reason) {
        return (ANY.equals(this.tenantId) ? 0 : 4)
                + (ANY.equals(this.source) ? 0 : 2)
                + (ANY.equals(this.reason) ? 0 : 1);
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getSource() {
        return source;
    }

    public String getReason() {
        return reason;
    }

    public String getQueue() {
        return queue;
    }

    public TicketPriority getPriority() {
        return TicketPriority.of(priority);
    }

    public int getSlaMinutes() {
        return slaMinutes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}