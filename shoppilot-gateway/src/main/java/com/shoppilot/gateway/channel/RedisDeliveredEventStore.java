package com.shoppilot.gateway.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 幂等账本：键 {@code shoppilot:outbound:delivered:<eventId>}，TTL 默认 7 天。
 *
 * <p>进程内 LRU 始终保留为第一层：Redis 抖动时至少同一进程内不重复投递。
 */
@Component
public class RedisDeliveredEventStore implements DeliveredEventStore {

    private static final Logger log = LoggerFactory.getLogger(RedisDeliveredEventStore.class);
    static final String PREFIX = "shoppilot:outbound:delivered:";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final DeliveredEventStore local = DeliveredEventStore.inMemory(Duration.ZERO);

    public RedisDeliveredEventStore(StringRedisTemplate redis,
                                    @Value("${shoppilot.outbound.dedupe-ttl:7d}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public boolean isDelivered(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        if (local.isDelivered(eventId)) {
            return true;
        }
        try {
            Boolean exists = redis.hasKey(PREFIX + eventId);
            if (Boolean.TRUE.equals(exists)) {
                local.markDelivered(eventId);
                return true;
            }
        } catch (RuntimeException unavailable) {
            log.warn("出站幂等键查询失败，退回进程内去重: {}", unavailable.getMessage());
        }
        return false;
    }

    @Override
    public void markDelivered(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        local.markDelivered(eventId);
        try {
            ValueOperations<String, String> values = redis.opsForValue();
            if (values != null) {
                values.set(PREFIX + eventId, "1", ttl);
            }
        } catch (RuntimeException unavailable) {
            log.warn("出站幂等键写入失败，仅进程内生效: {}", unavailable.getMessage());
        }
    }
}
