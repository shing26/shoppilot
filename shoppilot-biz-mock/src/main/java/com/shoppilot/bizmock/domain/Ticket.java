package com.shoppilot.bizmock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * 人工工单（ADR 0009）。转人工必须有可查证落点，否则是假功能。
 *
 * <p><b>为什么这张表没有索引</b>（round18 票 43 实测后否决）：工单列表按时间倒序取件
 * （{@code BizMockService} 的 {@code findAllByOrderByCreatedAtDesc}），本票试过
 * {@code tickets(tenant_id, created_at)}，计划确实从扫表变成走索引，但那条查询取全列且无上界
 * （一次取走某租户全部工单），走索引要逐行回表、没有覆盖能力，三次连跑 p50 一致比扫表差
 * 10~45%。读数与归因见 {@code docs/slow-query-optimization-2026-09-21.md}。
 * 真正的修法是给那条查询加上界（功能改动，不在本轮范围）；加上界之后这张索引才值得重新量。
 */
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

    /**
     * 工单来源（round23 票 69 / ADR 0055）。迁移回填 {@code DEGRADE}，非空。
     *
     * <p>取值见 {@link TicketSource}。
     */
    @Column(name = "source", nullable = false, length = 24)
    private String source;

    /** 分派到的队列；null = 尚未分派（分派是票 70 的规则表干的活）。 */
    @Column(name = "queue", length = 32)
    private String queue;

    /** 领取这张工单的坐席；null = 无人领取（座登模型见 ADR 0055）。 */
    @Column(name = "assignee", length = 32)
    private String assignee;

    /** SLA 截止时间；只用于计时与超时升级标记，不承诺解决时限。 */
    @Column(name = "sla_deadline")
    private Instant slaDeadline;

    /**
     * 超时打戳时刻（round23 票 70）。非空即代表「已被观测到超时」。
     * 它**不改工单状态**——超时不是关闭，也不是失败（ADR 0055）。
     */
    @Column(name = "escalated_at")
    private Instant escalatedAt;

    /**
     * 来源有上游记录时（反馈复核、退款审批）指回上游 id 的 JSON；自包含来源留空。
     * 形状与拼装见 {@code service/WorkItemPayload}。
     */
    @Lob
    @Column(name = "payload", length = 2000)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Ticket() {
    }

    public Ticket(String id, String tenantId, String customerId, String reason, String userQuery,
                  String transcript, String status, Instant createdAt, String priority) {
        this(id, tenantId, customerId, reason, userQuery, transcript, status, createdAt, priority,
                TicketSource.DEGRADE, null, null, null, null);
    }

    /** 统一工单构造：四种来源共用（ADR 0055）。 */
    public Ticket(String id, String tenantId, String customerId, String reason, String userQuery,
                  String transcript, String status, Instant createdAt, String priority, TicketSource source,
                  String queue, String assignee, Instant slaDeadline, String payload) {
        this.id = id;
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.reason = reason;
        this.userQuery = userQuery;
        this.transcript = transcript;
        this.status = status;
        this.createdAt = createdAt;
        this.priority = priority;
        this.source = source.name();
        this.queue = queue;
        this.assignee = assignee;
        this.slaDeadline = slaDeadline;
        this.payload = payload;
    }

    /**
     * 工单号后缀序列。
     *
     * <p>原来只用 "T + 毫秒 + 内容哈希"：大促压测里同一店铺同一句话在同一毫秒内落几十张单，
     * 内容一样、时间戳一样，工单号就撞在一张表的主键上，返回 500，降级链路直接断在终点
     * （2026-09-08 mix80 压测暴露，见 {@code TicketIdTest}）。加一个进程内单调序列，
     * 让 (毫秒, 序列) 这一对唯一，不依赖时钟精度。
     */
    private static final java.util.concurrent.atomic.AtomicLong TICKET_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    public static String nextId(Instant now) {
        return "T" + now.toEpochMilli() + "-" + Long.toUnsignedString(TICKET_SEQ.getAndIncrement(), 36);
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

    public String getSource() {
        return source;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public Instant getSlaDeadline() {
        return slaDeadline;
    }

    public void setSlaDeadline(Instant slaDeadline) {
        this.slaDeadline = slaDeadline;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public void setEscalatedAt(Instant escalatedAt) {
        this.escalatedAt = escalatedAt;
    }

    public String getPayload() {
        return payload;
    }
}
