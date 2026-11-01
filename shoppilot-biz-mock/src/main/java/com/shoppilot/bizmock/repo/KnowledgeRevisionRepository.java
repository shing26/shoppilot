package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.KnowledgeRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface KnowledgeRevisionRepository extends JpaRepository<KnowledgeRevision, String> {

    /**
     * ingest 拉取口（round43 票 185 / ADR 0078）：跨租户读全部已发布修订。
     *
     * <p>显式 native 查询是**有意的绕过点**：biz-mock 的租户隔离是 Hibernate 6 判别器多租户，
     * JPQL 一律被拼上租户谓词，而入库语料本来就横跨全部租户（检索可见性 = 本租户 + 同行业平台）。
     * 这里的边界靠三件事守：内部令牌（{@code InternalAuthFilter}）、只读、只吐 PUBLISHED——
     * 人工发布门之前的稿子在这里不可见。走投影而不是实体，避免任何被租户语义二次过滤的路径。
     * 时间戳用 {@link OffsetDateTime}：原生查询不给转换器留介入点，两侧方言的
     * {@code timestamp with time zone} 都落这个类型（Instant 会 500，实测）。
     */
    @Query(value = """
            select id, tenant_id as "tenantId", feedback_id as "feedbackId", title, body,
                   intent, knowledge_domain as "domain", revision_scope as "scope",
                   created_at as "createdAt"
            from knowledge_revisions
            where status = :status
            order by created_at
            """, nativeQuery = true)
    List<PublishedRevisionRow> findAllByStatusAcrossTenants(@Param("status") String status);

    /** 本店修订列表（租户谓词由判别器多租户自动拼上）；发布面与草稿列表用。 */
    List<KnowledgeRevision> findByStatusOrderByCreatedAtDesc(String status);

    /** ingest 拉取口的投影：字段就是入库切分需要的全部。 */
    interface PublishedRevisionRow {
        String getId();

        String getTenantId();

        String getFeedbackId();

        String getTitle();

        String getBody();

        String getIntent();

        String getDomain();

        String getScope();

        OffsetDateTime getCreatedAt();
    }
}
