package com.shoppilot.bizmock.service;

import com.shoppilot.bizmock.audit.AuditService;
import com.shoppilot.bizmock.domain.KnowledgeRevision;
import com.shoppilot.bizmock.repo.FeedbackRepository;
import com.shoppilot.bizmock.repo.KnowledgeRevisionRepository;
import com.shoppilot.bizmock.repo.KnowledgeRevisionRepository.PublishedRevisionRow;
import com.shoppilot.bizmock.tenant.TenantContextHolder;
import com.shoppilot.tool.audit.Actor;
import com.shoppilot.tool.audit.AuditActions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * 知识修订的生命周期（round43 票 185 / ADR 0078）。
 *
 * <p>两道人工门都在这里：附稿前置「反馈必须已复核完成」（ADR 0039「人工复核后才进知识库修订」
 * 的机器落点），发布/驳回是第二道显式动作。服务只做格式与状态守护，**不生成、不润色正文**。
 * 修订进入检索语料的最后一跳在网关 ingest（票 186）：拉取 PUBLISHED 行，与文件语料一起全量重建、
 * 推进纪元——发布本身不碰检索索引。
 */
@Service
public class KnowledgeRevisionService {

    /** 政策意图白名单：动作意图的答案依赖具体买家数据，不存在"沉淀一条政策"可言。 */
    private static final Set<String> POLICY_INTENTS = Set.of(
            "POLICY_RETURN", "POLICY_SHIPPING", "POLICY_PROMO", "POLICY_FRESH", "POLICY_HOTEL");

    private static final Set<String> SCOPES = Set.of("SHOP", "PLATFORM");

    private static final AtomicLong REVISION_SEQ = new AtomicLong();

    private final KnowledgeRevisionRepository revisionRepository;
    private final FeedbackRepository feedbackRepository;
    private final AuditService auditService;

    public KnowledgeRevisionService(KnowledgeRevisionRepository revisionRepository,
                                    FeedbackRepository feedbackRepository, AuditService auditService) {
        this.revisionRepository = revisionRepository;
        this.feedbackRepository = feedbackRepository;
        this.auditService = auditService;
    }

    /**
     * 附稿：人工撰写的一版条款修订，挂在已复核完成的反馈下。
     *
     * <p>跨租户的 feedback 在判别器多租户下根本查不到 → 与全仓一致的 404 语义（不确认存在）。
     * 状态前置不满足回 409：请求本身没错，是这行反馈还没走到能附稿的位置。
     */
    @Transactional
    public RevisionView submit(String feedbackId, Actor author, String title, String body,
                               String intent, String domain, String scope) {
        var feedback = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new IllegalArgumentException("feedback not found: " + feedbackId));
        // 显式归属检查：findById 不自动拼租户（ARCHITECTURE.md 已知边界），新查询必须过归属。
        if (!feedback.getTenantId().equals(TenantContextHolder.tenantId())) {
            throw new IllegalArgumentException("feedback not found: " + feedbackId);
        }
        if (!"REVIEWED".equals(feedback.getReviewStatus())) {
            throw new IllegalStateException("feedback " + feedbackId + " 尚未复核完成，不能附修订稿（ADR 0039）");
        }
        Instant now = Instant.now();
        String id = "KR" + now.toEpochMilli() + "-" + Long.toUnsignedString(REVISION_SEQ.getAndIncrement(), 36);
        KnowledgeRevision revision = new KnowledgeRevision(id, TenantContextHolder.tenantId(), feedbackId,
                title, body, intent, domain, scope, "DRAFT",
                author.name(), author.authenticated(), now);
        KnowledgeRevision saved = revisionRepository.save(revision);
        auditService.publish(AuditActions.KNOWLEDGE_REVISION_FILED, "KNOWLEDGE_REVISION", id, author,
                "复核完成的反馈附修订稿；修订进入语料还差一道发布门与一次 ingest");
        return toView(saved);
    }

    /** 发布：DRAFT → PUBLISHED，单向。发布后修订在下次 ingest 全量重建时进入检索语料。 */
    @Transactional
    public RevisionView publish(String id, Actor reviewer) {
        RevisionView view = transition(id, revision -> {
            revision.setStatus("PUBLISHED");
            revision.setPublishedAt(Instant.now());
            return revision;
        }, "发布");
        auditService.publish(AuditActions.KNOWLEDGE_REVISION_PUBLISHED, "KNOWLEDGE_REVISION", id, reviewer,
                "修订发布；生效时间 = 下次 ingest 全量重建推进纪元");
        return view;
    }

    /** 驳回：DRAFT → REJECTED，单向。驳回是终态，改稿 = 另附一稿。 */
    @Transactional
    public RevisionView reject(String id, Actor reviewer) {
        RevisionView view = transition(id, revision -> {
            revision.setStatus("REJECTED");
            revision.setRejectedAt(Instant.now());
            return revision;
        }, "驳回");
        auditService.publish(AuditActions.KNOWLEDGE_REVISION_REJECTED, "KNOWLEDGE_REVISION", id, reviewer,
                "修订驳回；驳回是终态，改稿请另附一稿");
        return view;
    }

    /** ingest 拉取口：全部租户的已发布修订（native 投影，绕过点见仓储注释）。 */
    @Transactional(readOnly = true)
    public List<RevisionView> listPublishedAcrossTenants() {
        return revisionRepository.findAllByStatusAcrossTenants("PUBLISHED").stream()
                .map(KnowledgeRevisionService::toView).toList();
    }

    /** 本店修订列表（发布面前置）：租户过滤自动生效，status 缺省 = 全部。 */
    @Transactional(readOnly = true)
    public List<RevisionView> listForCurrentTenant(String status) {
        var rows = status == null || status.isBlank()
                ? revisionRepository.findAll()
                : revisionRepository.findByStatusOrderByCreatedAtDesc(status);
        return rows.stream().map(KnowledgeRevisionService::toView).toList();
    }

    /** 校验附稿请求的形状。形状错误（400）与状态错误（409）/不存在（404）分开答。 */
    public static void validateDraft(String title, String body, String intent, String domain, String scope) {
        if (title == null || title.isBlank() || title.length() > 200) {
            throw new IllegalArgumentException("title 必填且不超过 200 字");
        }
        if (body == null || body.isBlank() || body.length() > 20_000) {
            throw new IllegalArgumentException("body 必填且不超过 20000 字");
        }
        // Set.of 的 contains(null) 是 NPE 不是 false：缺字段的残稿必须答 400 而不是炸 500。
        if (intent == null || !POLICY_INTENTS.contains(intent)) {
            throw new IllegalArgumentException("intent 必须是政策意图之一：" + POLICY_INTENTS);
        }
        if (domain == null || domain.isBlank() || domain.length() > 16) {
            throw new IllegalArgumentException("domain 必填");
        }
        if (scope == null || !SCOPES.contains(scope)) {
            throw new IllegalArgumentException("scope 只收 SHOP | PLATFORM");
        }
    }

    private RevisionView transition(String id, UnaryOperator<KnowledgeRevision> change, String action) {
        KnowledgeRevision revision = ownedRevision(id);
        if (!"DRAFT".equals(revision.getStatus())) {
            throw new IllegalStateException("knowledge revision " + id + " 不在 DRAFT 状态，不能" + action);
        }
        return toView(revisionRepository.save(change.apply(revision)));
    }

    /**
     * 显式归属检查（ADR 0005：任何查询都必须过归属；{@code findById} 不自动拼租户是
     * ARCHITECTURE.md 登记过的已知边界，新代码不许踩）。跨租户与不存在同答 404，不确认存在。
     */
    private KnowledgeRevision ownedRevision(String id) {
        KnowledgeRevision revision = revisionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("knowledge revision not found: " + id));
        if (!revision.getTenantId().equals(TenantContextHolder.tenantId())) {
            throw new IllegalArgumentException("knowledge revision not found: " + id);
        }
        return revision;
    }

    private static RevisionView toView(KnowledgeRevision revision) {
        return new RevisionView(revision.getId(), revision.getTenantId(), revision.getFeedbackId(),
                revision.getTitle(), revision.getBody(), revision.getIntent(), revision.getDomain(),
                revision.getScope(), revision.getStatus(), revision.getCreatedAt(),
                revision.getPublishedAt(), revision.getRejectedAt());
    }

    private static RevisionView toView(PublishedRevisionRow row) {
        return new RevisionView(row.getId(), row.getTenantId(), row.getFeedbackId(), row.getTitle(),
                row.getBody(), row.getIntent(), row.getDomain(), row.getScope(), "PUBLISHED",
                row.getCreatedAt().toInstant(), null, null);
    }

    public record RevisionView(String id, String tenantId, String feedbackId, String title, String body,
                               String intent, String domain, String scope, String status, Instant createdAt,
                               Instant publishedAt, Instant rejectedAt) {
    }
}
