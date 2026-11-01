package com.shoppilot.bizmock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * 知识修订（round43 票 185 / ADR 0078；round19 登记第 8 项「知识反向沉淀」的落库端）。
 *
 * <p>修订稿由**人工**撰写（复核完成的反馈才允许附稿）、再过一道显式发布门才进入语料——
 * 系统不生成、不润色正文（ADR 0039 红线：「让上次犯错的链路自己修自己」被显式否决）。
 * 状态单向：DRAFT → PUBLISHED | REJECTED。
 *
 * <p>列名避坑见 V10 迁移文件头：{@code domain} / {@code scope} 是 H2 2.x 关键字，
 * 落库叫 {@code knowledge_domain} / {@code revision_scope}，字段保持领域叫法。
 */
@Entity
@Table(name = "knowledge_revisions", indexes = @Index(name = "idx_knowledge_revision_status", columnList = "status"))
public class KnowledgeRevision {

    @Id
    @Column(name = "id", length = 40)
    private String id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "feedback_id", nullable = false, length = 40)
    private String feedbackId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** 有界文本（服务层限 20000 字），不走 @Lob：ingest 拉取口的 native 投影要直读本列。 */
    @Column(name = "body", nullable = false, length = 20000)
    private String body;

    @Column(name = "intent", nullable = false, length = 32)
    private String intent;

    @Column(name = "knowledge_domain", nullable = false, length = 16)
    private String domain;

    @Column(name = "revision_scope", nullable = false, length = 16)
    private String scope;

    /** DRAFT / PUBLISHED / REJECTED，单向流转，由 {@code KnowledgeRevisionService} 守护。 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    /** false = 操作人来自自报头。往严的一边倒：遗漏变成可查的噪声，不是不可查的假账。 */
    @Column(name = "actor_authenticated", nullable = false)
    private boolean actorAuthenticated;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    public KnowledgeRevision() {
    }

    public KnowledgeRevision(String id, String tenantId, String feedbackId, String title, String body,
                             String intent, String domain, String scope, String status,
                             String createdBy, boolean actorAuthenticated, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.feedbackId = feedbackId;
        this.title = title;
        this.body = body;
        this.intent = intent;
        this.domain = domain;
        this.scope = scope;
        this.status = status;
        this.createdBy = createdBy;
        this.actorAuthenticated = actorAuthenticated;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getFeedbackId() {
        return feedbackId;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getIntent() {
        return intent;
    }

    public String getDomain() {
        return domain;
    }

    public String getScope() {
        return scope;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public void setRejectedAt(Instant rejectedAt) {
        this.rejectedAt = rejectedAt;
    }
}
