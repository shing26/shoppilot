package com.shoppilot.gateway.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.shoppilot.gateway.knowledge.Domain;
import com.shoppilot.tool.Intent;

import java.time.Instant;
import java.util.List;

/**
 * 缓存条目。读取时必须校验 intent 与 tenant 一致（ADR 0003）：
 * 把"怎么退款"与"为什么不给退款"的语义漂移问题，转化为跨意图隔离问题。
 *
 * <p>round33 / ADR 0067 加两格：
 * <ul>
 *   <li>{@code domain} —— 平台桶不再全局共享。同一句"押金什么时候退"在电商与酒店下是两条不同的
 *       缓存空间，命中校验必须比 domain，否则跨行业串答案会从缓存侧原样复发。</li>
 *   <li>{@code sourceHeadings} —— 与 {@code sourceRuleIds} 同序的条款标题。买家端要显示
 *       {@code ruleId + heading}，而命中路径不跑检索、拿不到正文；标题必须随条目一起落库。</li>
 * </ul>
 *
 * <p>2026-10-17 再补 {@code sourceDocs}：round33 落了标题却漏了出处，命中路径的
 * {@code citationDetails.doc} 恒为空串——与 {@code sourceHeadings} 同一条理由，同序同源。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CacheEntry(
        String answer,
        /** 写入时的归一化问法，供 {@link PolarityGuard} 在语义命中后做极性复核。 */
        String query,
        String intent,
        String tenantId,
        String scope,
        Domain domain,
        long kbEpoch,
        List<String> sourceRuleIds,
        List<String> sourceHeadings,
        List<String> sourceDocs,
        String modelId,
        Instant createdAt) {

    public static CacheEntry of(String answer, Intent intent, String tenantId, String scope, Domain domain,
                                long kbEpoch, List<String> sourceRuleIds, List<String> sourceHeadings,
                                List<String> sourceDocs, String modelId, String query) {
        return new CacheEntry(answer, query, intent.name(), tenantId, scope, domain, kbEpoch, sourceRuleIds,
                sourceHeadings == null ? List.of() : List.copyOf(sourceHeadings),
                sourceDocs == null ? List.of() : List.copyOf(sourceDocs), modelId, Instant.now());
    }

    /**
     * 命中校验：意图、桶归属、行业、知识库纪元四者全等才算命中。
     *
     * <p>{@code domain == null} 只可能是旧版本写下的条目（round33 之前没有这一格），按未命中处理——
     * 部署换代时让旧平台桶自然失效，好过让它跨行业继续被复用。
     */
    public boolean matches(Intent expectedIntent, String expectedTenant, Domain expectedDomain, long expectedEpoch) {
        return intent != null && intent.equals(expectedIntent.name())
                && tenantId != null && tenantId.equals(expectedTenant)
                && domain != null && domain == expectedDomain
                && kbEpoch == expectedEpoch;
    }
}
