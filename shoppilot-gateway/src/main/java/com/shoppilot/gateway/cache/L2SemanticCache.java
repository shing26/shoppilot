package com.shoppilot.gateway.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.knowledge.EmbeddingClient;
import com.shoppilot.gateway.knowledge.QdrantRestClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * L2 向量语义缓存（ADR 0003、ADR 0006）。
 *
 * <p>正文只存 Redis，Qdrant 只存向量与定位用的 payload。
 * 一处存储一份正文，避免两个系统各持一份答案而漂移。
 */
@Component
public class L2SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(L2SemanticCache.class);

    private final QdrantRestClient qdrant;
    private final EmbeddingClient embedding;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final GatewayProperties.Cache cacheConfig;
    private final GatewayProperties.Retrieval retrievalConfig;
    private final Counter hitCounter;
    private final Counter missCounter;

    public L2SemanticCache(QdrantRestClient qdrant, EmbeddingClient embedding, StringRedisTemplate redis,
                           ObjectMapper mapper, GatewayProperties properties, MeterRegistry registry) {
        this.qdrant = qdrant;
        this.embedding = embedding;
        this.redis = redis;
        this.mapper = mapper;
        this.cacheConfig = properties.cache();
        this.retrievalConfig = properties.retrieval();
        this.hitCounter = Counter.builder("shoppilot_cache_l2_total").tag("result", "hit").register(registry);
        this.missCounter = Counter.builder("shoppilot_cache_l2_total").tag("result", "miss").register(registry);
    }

    public void ensureCollection() {
        qdrant.ensureCollection(retrievalConfig.cacheCollection(), embedding.dimension(),
                List.of("tenant_id", "scope", "intent", "answer_key"), List.of("kb_epoch"));
    }

    public Optional<CacheEntry> search(float[] queryVector, String tenantId, String scope, String intent, long kbEpoch) {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("must", List.of(
                Map.of("key", "tenant_id", "match", Map.of("value", tenantId)),
                Map.of("key", "scope", "match", Map.of("value", scope)),
                Map.of("key", "intent", "match", Map.of("value", intent)),
                Map.of("key", "kb_epoch", "match", Map.of("value", kbEpoch))));
        try {
            List<QdrantRestClient.Hit> hits = qdrant.search(retrievalConfig.cacheCollection(), queryVector,
                    filter, 1, cacheConfig.semanticThreshold());
            if (hits.isEmpty()) {
                missCounter.increment();
                return Optional.empty();
            }
            Object answerKey = hits.get(0).payload().get("answer_key");
            if (answerKey == null) {
                missCounter.increment();
                return Optional.empty();
            }
            String body = redis.opsForValue().get(String.valueOf(answerKey));
            if (body == null || L1Cache.NEGATIVE_MARKER.equals(body)) {
                // 向量在、正文没了：删点而不是返回半条答案
                qdrant.deleteByFilter(retrievalConfig.cacheCollection(),
                        Map.of("must", List.of(Map.of("key", "answer_key", "match",
                                Map.of("value", String.valueOf(answerKey))))));
                missCounter.increment();
                return Optional.empty();
            }
            hitCounter.increment();
            return Optional.of(mapper.readValue(body, CacheEntry.class));
        } catch (Exception failure) {
            log.warn("L2 检索失败，按未命中处理: {}", failure.getMessage());
            missCounter.increment();
            return Optional.empty();
        }
    }

    public void store(float[] vector, String answerKey, CacheEntry entry) {
        try {
            String pointId = UUID.nameUUIDFromBytes((answerKey + "|" + entry.answer()).getBytes()).toString();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("answer_key", answerKey);
            payload.put("tenant_id", entry.tenantId());
            payload.put("scope", entry.scope());
            payload.put("intent", entry.intent());
            payload.put("kb_epoch", entry.kbEpoch());
            qdrant.upsert(retrievalConfig.cacheCollection(),
                    List.of(new QdrantRestClient.Point(pointId, vector, payload)));
        } catch (Exception failure) {
            log.warn("L2 写入失败: {}", failure.getMessage());
        }
    }

    /** 纪元推进后清掉旧纪元的向量点，不靠 TTL 自然过期（ticket 09）。 */
    public long purgeBeforeEpoch(long currentEpoch) {
        try {
            return qdrant.deleteByFilter(retrievalConfig.cacheCollection(), Map.of("must_not",
                    List.of(Map.of("key", "kb_epoch", "range", Map.of("gte", currentEpoch)))));
        } catch (Exception failure) {
            log.warn("清理旧纪元缓存失败: {}", failure.getMessage());
            return 0;
        }
    }
}
