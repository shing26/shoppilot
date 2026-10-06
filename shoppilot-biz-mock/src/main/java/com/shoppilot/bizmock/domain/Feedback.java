package com.shoppilot.bizmock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * 满意度反馈（ADR 0039）。显式点踩/点赞落行；隐式信号只走计数器不落行（口径分开记）。
 * DOWN 行自动关联当次会话的工单与检索引用块，PENDING 即 ingest 待复核队列——
 * 人工复核后才进知识库修订，本表不做任何自动改写。
 *
 * <p>索引依据：复核队列按「状态 + 时间倒序」取件（{@code FeedbackService} 的
 * {@code findByReviewStatusOrderByCreatedAtDesc}），谓词与排序都落在 idx_feedback_review 上。
 * ddl-auto 已是 validate，**索引的真相源是 db/migration**，本注解只作文档、不被校验。
 */
@Entity
@Table(name = "feedback", indexes = @Index(name = "idx_feedback_review", columnList = "review_status,created_at"))
public class Feedback {

    @Id
    @Column(name = "id", length = 40)
    private String id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "customer_id", nullable = false, length = 32)
    private String customerId;

    @Column(name = "conversation_id", nullable = false, length = 64)
    private String conversationId;

    /** UP / DOWN（ADR 0039 的 verdict）。 */
    @Column(name = "verdict", nullable = false, length = 8)
    private String verdict;

    @Column(name = "reason", length = 512)
    private String reason;

    /** 会话内观测到的隐式信号（如 negative），与显式 verdict 分开记、随行展示。 */
    @Column(name = "signals", length = 128)
    private String signals;

    /** 关联的检索引用块，逗号分隔；点踩归因到知识条目靠它。 */
    // 维持 @Lob（B1 / ADR 0061 的取舍）：三种映射各瘸一条腿——@Lob 在 H2 validate 绿、PG 落 oid
    // 且绑定与列型一致；LONGVARCHAR(-1) 过不了 H2 validate（found clob / expecting text）；
    // LONG32VARCHAR(4001) 过 H2 但 PG 驱动对 setNull(4001) 抛 Unknown Types value。
    // 「PG 落 text」连同完整实验矩阵登记在 ADR 0061，不在 B1 强行换。
    @Lob
    @Column(name = "rule_ids", length = 512)
    private String ruleIds;

    @Column(name = "ticket_id", length = 40)
    private String ticketId;

    /** NONE（UP）/ PENDING（DOWN 进复核队列）/ REVIEWED（人工复核完成）。 */
    @Column(name = "review_status", nullable = false, length = 12)
    private String reviewStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Feedback() {
    }

    public Feedback(String id, String tenantId, String customerId, String conversationId, String verdict,
                    String reason, String signals, String ruleIds, String ticketId, String reviewStatus,
                    Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.conversationId = conversationId;
        this.verdict = verdict;
        this.reason = reason;
        this.signals = signals;
        this.ruleIds = ruleIds;
        this.ticketId = ticketId;
        this.reviewStatus = reviewStatus;
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

    public String getConversationId() {
        return conversationId;
    }

    public String getVerdict() {
        return verdict;
    }

    public String getReason() {
        return reason;
    }

    public String getSignals() {
        return signals;
    }

    public String getRuleIds() {
        return ruleIds;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(String reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
