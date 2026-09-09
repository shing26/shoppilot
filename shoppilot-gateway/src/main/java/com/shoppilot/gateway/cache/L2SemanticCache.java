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
import java.util.concurrent.atomic.AtomicLong;

/**
 * L2 向量语义缓存（ADR 0003、ADR 0006）。
 *
 * <p>正文只存 Redis，Qdrant 只存向量与定位用的 payload。
 * 一处存储一份正文，避免两个系统各持一份答案而漂移。
 */
@Component
public class L2SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(L2SemanticCache.class);
    /** 缓存表上要建 keyword 索引的字段；`kb_epoch` 是整数索引，单列在下面。 */
    private static final List<String> CACHE_INDEX_KEYS = List.of("tenant_id", "scope", "intent", "answer_key");
    /** 读路径补建的最小间隔，避免 flush 撞上并发检索时一堆线程同时去建表。 */
    private static final long RECREATE_MIN_INTERVAL_MS = 1_000L;

    private final QdrantRestClient qdrant;
    private final EmbeddingClient embedding;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final GatewayProperties.Cache cacheConfig;
    private final GatewayProperties.Retrieval retrievalConfig;
    private final Counter hitCounter;
    private final Counter missCounter;
    /** 读路径发现"表不在"的次数：非零说明有人在外部动过表，或 flush 撞上了并发检索。 */
    private final Counter missingTableCounter;
    private final AtomicLong lastRecreateAttempt = new AtomicLong(0);

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
        this.missingTableCounter = Counter.builder("shoppilot_cache_l2_missing_total")
                .description("L2 检索遇到「缓存表不存在」的次数（按 miss 处理并触发自愈补建）")
                .register(registry);
    }

    public void ensureCollection() {
        qdrant.ensureCollection(retrievalConfig.cacheCollection(), embedding.dimension(), CACHE_INDEX_KEYS,
                List.of("kb_epoch"));
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
            missCounter.increment();
            if (QdrantRestClient.isMissingCollection(failure)) {
                // 表不在 = 空缓存，不是 Qdrant 坏了。安静地按 miss 处理，并补建一次，
                // 免得表被外部删掉之后 L2 永久静默零命中、只在日志里留一行看起来无害的 WARN。
                missingTableCounter.increment();
                log.debug("L2 缓存表缺失，按未命中处理并补建: {}", failure.getMessage());
                recreateQuietly();
                return Optional.empty();
            }
            log.warn("L2 检索失败，按未命中处理: {}", failure.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 读到"表不存在"时的自愈。整张表空的时候重建是无损的（正文本来就在 Redis，向量丢了只是回到冷启动），
     * 所以这里宁可多一次 PUT：不自愈的话，一次外部误删会让 L2 永久静默零拦截，而唯一的线索是日志里
     * 一行已经改成 debug 级别的记录。带节流，同一时间窗内只有一个线程真去建。
     */
    private void recreateQuietly() {
        long now = System.currentTimeMillis();
        long previous = lastRecreateAttempt.get();
        if (now - previous < RECREATE_MIN_INTERVAL_MS && previous != 0) {
            return;
        }
        if (!lastRecreateAttempt.compareAndSet(previous, now)) {
            return;
        }
        try {
            ensureCollection();
        } catch (RuntimeException failure) {
            log.warn("L2 缓存表补建失败，下一次检索再试: {}", failure.getMessage());
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

    /** 清空答案缓存向量表并重建，供演示复位使用。 */
    public void flush() {
        // 删表重建。两步之间那段"表不存在"的窗口关不掉（Qdrant 1.12.4 的 recreate 不生效），
        // 撞进窗口的检索由 search 按空缓存分类并自愈，理由与实测数字见 QdrantRestClient#deleteCollection
        qdrant.deleteCollection(retrievalConfig.cacheCollection());
        ensureCollection();
    }
}
