package com.shoppilot.gateway.cache;

import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.knowledge.EmbeddingClient;
import com.shoppilot.gateway.knowledge.RuleChunk;
import com.shoppilot.tool.Intent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 缓存准入之后的读取与写回编排（ADR 0003、ADR 0004）。
 *
 * <p>两条 key 分别对应"平台级共享答案"与"本店专属答案"：读取时先查平台桶再查店铺桶，
 * 写回时按本次引用的规则块 scope 决定落在哪个桶。这样平台政策不会被按店铺复制 N 份，
 * 也不会出现各店答案不一致。
 *
 * <p>顺序很重要：先 L1 精确哈希（零向量化），miss 才向量化，再 L2 语义。
 * 反过来会让每个 cacheable 请求都付一次 CPU-bound 的 embedding，TP99 30ms 直接作废。
 */
@Component
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    public enum Layer {
        NONE, L1, L2,
        /** 穿透合并复用：既不是缓存命中也不是模型直答，单独一层免得污染两级口径。 */
        FLIGHT
    }

    public static final String SCOPE_PLATFORM = "PLATFORM";
    public static final String SCOPE_SHOP = "SHOP";

    /**
     * @param queryVector 走到 L2 才有值，写回时复用，避免二次向量化
     */
    public record Lookup(Layer layer, Optional<CacheEntry> entry, float[] queryVector, String normalizedQuery,
                         boolean negative) {

        public boolean hit() {
            return layer != Layer.NONE && entry.isPresent();
        }

        public static Lookup disabled() {
            return new Lookup(Layer.NONE, Optional.empty(), null, "", false);
        }

        public static Lookup negative(String normalizedQuery) {
            return new Lookup(Layer.NONE, Optional.empty(), null, normalizedQuery, true);
        }
    }

    private final L1Cache l1;
    private final L2SemanticCache l2;
    private final EmbeddingClient embedding;
    private final GatewayProperties.Cache config;
    private final Counter admittedCounter;
    private final Counter totalCounter;
    private final Counter l1HitCounter;
    private final Counter l2HitCounter;
    private final Counter negativeHitCounter;
    private final Counter polarityBlockedCounter;
    private final Counter embedUnavailableCounter;

    public CacheService(L1Cache l1, L2SemanticCache l2, EmbeddingClient embedding, GatewayProperties properties,
                        MeterRegistry registry) {
        this.l1 = l1;
        this.l2 = l2;
        this.embedding = embedding;
        this.config = properties.cache();
        this.totalCounter = Counter.builder("shoppilot_requests_total")
                .description("进入网关的有效咨询请求数，总拦截率的分母").register(registry);
        this.admittedCounter = Counter.builder("shoppilot_cache_admitted_total")
                .description("准入缓存的请求数，准入内命中率的分母").register(registry);
        this.l1HitCounter = Counter.builder("shoppilot_cache_hit_total").tag("layer", "L1").register(registry);
        this.l2HitCounter = Counter.builder("shoppilot_cache_hit_total").tag("layer", "L2").register(registry);
        this.negativeHitCounter = Counter.builder("shoppilot_cache_negative_hit_total").register(registry);
        this.polarityBlockedCounter = Counter.builder("shoppilot_cache_l2_polarity_blocked_total")
                .description("L2 余弦过阈值但极性不一致，被守卫拒绝复用的次数").register(registry);
        // 拿不到查询向量时 L1 与 L2 的写回会一起落空（prepareWrite 要求 queryVector）。
        // 这条分支原来只有一个静默 catch，出事时看不出来；打点加 warn 补上归因。
        this.embedUnavailableCounter = Counter.builder("shoppilot_cache_embed_unavailable_total")
                .description("缓存路径拿不到查询向量、本次 L1/L2 写回落空的次数").register(registry);
    }

    public void recordRequest() {
        totalCounter.increment();
    }

    public Lookup lookup(String tenantId, Intent intent, String rawQuery, long kbEpoch, float[] precomputedVector) {
        String normalized = QueryNormalizer.normalize(rawQuery);
        if (!config.enabled()) {
            return Lookup.disabled();
        }
        admittedCounter.increment();
        List<Bucket> buckets = buckets(tenantId, intent, kbEpoch, normalized);
        for (Bucket bucket : buckets) {
            if (l1.negativePresent(bucket.key())) {
                negativeHitCounter.increment();
                return Lookup.negative(normalized);
            }
            Optional<CacheEntry> entry = l1.get(bucket.key());
            if (entry.isPresent() && entry.get().matches(intent, bucket.tenantId(), kbEpoch)) {
                l1HitCounter.increment();
                return new Lookup(Layer.L1, entry, precomputedVector, normalized, false);
            }
        }
        // 向量化失败只意味着 L2 用不上，绝不能让整个请求 500：缓存不是正确性依赖
        float[] vector;
        try {
            vector = precomputedVector != null ? precomputedVector : embedding.embed(rawQuery);
        } catch (RuntimeException embeddingUnavailable) {
            embedUnavailableCounter.increment();
            log.warn("缓存向量化失败，本次请求 L1/L2 写回落空: {}", embeddingUnavailable.getMessage());
            return new Lookup(Layer.NONE, Optional.empty(), null, normalized, false);
        }
        if (vector == null) {
            embedUnavailableCounter.increment();
            log.warn("缓存向量化返回空向量，本次请求 L1/L2 写回落空");
            return new Lookup(Layer.NONE, Optional.empty(), null, normalized, false);
        }
        for (Bucket bucket : buckets) {
            Optional<CacheEntry> entry = l2.search(vector, bucket.tenantId(), bucket.scope(), intent.name(), kbEpoch);
            if (entry.isEmpty() || !entry.get().matches(intent, bucket.tenantId(), kbEpoch)) {
                continue;
            }
            String reason = PolarityGuard.blocked(normalized, entry.get().query());
            if (reason != null) {
                // 同意图桶内的反义问法：0.95 实测拦不住（见 docs/threshold-calibration.md），由守卫兜住
                polarityBlockedCounter.increment();
                log.info("L2 语义命中被极性守卫拒绝: {} (cached={} incoming={})", reason,
                        entry.get().query(), normalized);
                continue;
            }
            l2HitCounter.increment();
            return new Lookup(Layer.L2, entry, vector, normalized, false);
        }
        return new Lookup(Layer.NONE, Optional.empty(), vector, normalized, false);
    }

    /**
     * 写回落哪个桶由本次引用的规则块决定：全部是平台级条款才允许跨店共享。
     * 只要掺了一条本店规则，就写进本店桶，避免把店铺细则说成平台统一口径。
     */
    public Optional<CacheEntry> prepareWrite(String tenantId, Intent intent, long kbEpoch, Lookup lookup,
                                             String answer, List<String> citedRuleScopes,
                                             List<String> sourceRuleIds, String modelId) {
        if (lookup.queryVector() == null || lookup.normalizedQuery() == null || lookup.normalizedQuery().isEmpty()) {
            return Optional.empty();
        }
        boolean allPlatform = citedRuleScopes != null && !citedRuleScopes.isEmpty()
                && citedRuleScopes.stream().allMatch(SCOPE_PLATFORM::equalsIgnoreCase);
        String bucketTenant = allPlatform ? RuleChunk.PLATFORM_TENANT : tenantId;
        String scope = allPlatform ? SCOPE_PLATFORM : SCOPE_SHOP;
        return Optional.of(CacheEntry.of(answer, intent, bucketTenant, scope, kbEpoch, sourceRuleIds, modelId,
                lookup.normalizedQuery()));
    }

    /** 条目已在准入判定阶段算好，这里只做两写：L1 正文 + L2 向量定位。 */
    public void writeBack(CacheEntry entry, Lookup lookup) {
        String key = l1.key(entry.tenantId(), entry.intent(), entry.kbEpoch(), lookup.normalizedQuery());
        l1.put(key, entry);
        l2.store(lookup.queryVector(), key, entry);
    }

    public void writeNegative(String tenantId, Intent intent, long kbEpoch, Lookup lookup) {
        l1.putNegative(l1.key(tenantId, intent.name(), kbEpoch, lookup.normalizedQuery()));
    }

    /**
     * 演示/验收用：直接给某个问法打负缓存标记，让 {@code INTENT_UNRESOLVED} 可稳定复现。
     *
     * <p>90 条语料上稠密召回几乎总能返回候选，"检索为空 -> 打负标记"这条分支在真实流量里
     * 极难自然命中；一条打不开的内部开关，好过一段永远走不到的死代码。
     * 只在运维代理开启时可达，见 {@code OpsController}。
     */
    public void markNegative(String tenantId, Intent intent, long kbEpoch, String rawQuery) {
        String normalized = QueryNormalizer.normalize(rawQuery);
        // 两个桶都打：查询顺序是先平台桶后本店桶，只标本店桶会被平台桶里的既有答案抢先命中
        l1.putNegative(l1.key(RuleChunk.PLATFORM_TENANT, intent.name(), kbEpoch, normalized));
        l1.putNegative(l1.key(tenantId, intent.name(), kbEpoch, normalized));
    }

    /**
     * 复位答案缓存：清 L1 正文与负标记、重建 L2 向量表。
     *
     * <p>刻意不动知识库纪元。纪元同时是检索过滤器，推一次纪元等于把 90 条政策条款
     * 从检索范围里整体摘掉，那不是"清缓存"，那是"把知识库关了"。
     */
    public Map<String, Object> flush() {
        long l1Keys = l1.flush();
        l2.flush();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("l1KeysDeleted", l1Keys);
        view.put("kbEpoch", "unchanged");
        return view;
    }

    private List<Bucket> buckets(String tenantId, Intent intent, long kbEpoch, String normalized) {
        return List.of(
                new Bucket(RuleChunk.PLATFORM_TENANT, SCOPE_PLATFORM, l1.key(RuleChunk.PLATFORM_TENANT, intent.name(), kbEpoch, normalized)),
                new Bucket(tenantId, SCOPE_SHOP, l1.key(tenantId, intent.name(), kbEpoch, normalized)));
    }

    private record Bucket(String tenantId, String scope, String key) {
    }
}
