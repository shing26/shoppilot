package com.shoppilot.gateway.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.shoppilot.tool.Intent;

import java.time.Instant;
import java.util.List;

/**
 * 缓存条目。读取时必须校验 intent 与 tenant 一致（ADR 0003）：
 * 把"怎么退款"与"为什么不给退款"的语义漂移问题，转化为跨意图隔离问题。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CacheEntry(
        String answer,
        /** 写入时的归一化问法，供 {@link PolarityGuard} 在语义命中后做极性复核。 */
        String query,
        String intent,
        String tenantId,
        String scope,
        long kbEpoch,
        List<String> sourceRuleIds,
        String modelId,
        Instant createdAt) {

    public static CacheEntry of(String answer, Intent intent, String tenantId, String scope, long kbEpoch,
                                List<String> sourceRuleIds, String modelId, String query) {
        return new CacheEntry(answer, query, intent.name(), tenantId, scope, kbEpoch, sourceRuleIds, modelId,
                Instant.now());
    }

    public boolean matches(Intent expectedIntent, String expectedTenant, long expectedEpoch) {
        return intent != null && intent.equals(expectedIntent.name())
                && tenantId != null && tenantId.equals(expectedTenant)
                && kbEpoch == expectedEpoch;
    }
}
