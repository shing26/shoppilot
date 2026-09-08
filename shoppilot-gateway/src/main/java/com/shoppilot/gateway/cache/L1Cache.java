package com.shoppilot.gateway.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * L1 精确哈希缓存（ADR 0003、ADR 0011）。
 *
 * <p>key 含 tenantId + scope + intent + kbEpoch + 归一化文本：
 * 只带 userId 堵不住政策类答案的个性化误判，也不堵意图漂移。
 */
@Component
public class L1Cache {

    private static final Logger log = LoggerFactory.getLogger(L1Cache.class);
    static final String NEGATIVE_MARKER = "__NEG__";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final GatewayProperties.Cache config;

    /** 命中/未命中计数在 CacheService 统一记，这里两级 key 都会读，重复计数会污染拦截率分母。 */
    public L1Cache(StringRedisTemplate redis, ObjectMapper mapper, GatewayProperties properties) {
        this.redis = redis;
        this.mapper = mapper;
        this.config = properties.cache();
    }

    public String key(String tenantId, String intent, long kbEpoch, String normalizedQuery) {
        return "shoppilot:c:l1:" + QueryNormalizer.md5(
                tenantId + "|" + intent + "|" + kbEpoch + "|" + normalizedQuery);
    }

    public Optional<CacheEntry> get(String key) {
        try {
            String body = redis.opsForValue().get(key);
            if (body == null) {
                return Optional.empty();
            }
            if (NEGATIVE_MARKER.equals(body)) {
                return Optional.empty();
            }
            CacheEntry entry = mapper.readValue(body, CacheEntry.class);
            return Optional.of(entry);
        } catch (Exception failure) {
            // 缓存不是正确性依赖：读失败就当未命中，继续走完整链路
            log.warn("L1 读取失败，按未命中处理: {}", failure.getMessage());
            return Optional.empty();
        }
    }

    public void put(String key, CacheEntry entry) {
        put(key, entry, config.l1Ttl());
    }

    public void put(String key, CacheEntry entry, Duration ttl) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(entry), ttl);
        } catch (Exception failure) {
            log.warn("L1 写入失败，不影响本次响应: {}", failure.getMessage());
        }
    }

    /** 负缓存：冷门问题反复打穿时，60 秒内直接走兜底而不是重跑整条链路。 */
    public void putNegative(String key) {
        try {
            redis.opsForValue().set(key, NEGATIVE_MARKER, config.negativeTtl());
        } catch (Exception failure) {
            log.debug("负缓存写入失败: {}", failure.getMessage());
        }
    }

    public boolean negativePresent(String key) {
        try {
            return NEGATIVE_MARKER.equals(redis.opsForValue().get(key));
        } catch (Exception failure) {
            return false;
        }
    }

    public boolean enabled() {
        return config.enabled();
    }
}
