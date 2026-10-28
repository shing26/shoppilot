package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Web Push 订阅（票 181）：按租户存 endpoint + p256dh/auth，7 天过期。 */
@Component
public class ChannelPushSubscriptionStore {

    private static final Logger log = LoggerFactory.getLogger(ChannelPushSubscriptionStore.class);
    private static final Duration TTL = Duration.ofDays(7);

    public record Subscription(String endpoint, String p256dh, String auth) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public ChannelPushSubscriptionStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public void save(String tenantId, Subscription subscription) {
        try {
            String key = key(tenantId);
            redis.opsForHash().put(key, subscription.endpoint(), mapper.writeValueAsString(subscription));
            redis.expire(key, TTL);
        } catch (Exception failure) {
            log.warn("保存 Web Push 订阅失败: {}", failure.getMessage());
        }
    }

    public void remove(String tenantId, String endpoint) {
        try {
            redis.opsForHash().delete(key(tenantId), endpoint);
        } catch (Exception failure) {
            log.warn("删除 Web Push 订阅失败: {}", failure.getMessage());
        }
    }

    public List<Subscription> list(String tenantId) {
        List<Subscription> result = new ArrayList<>();
        try {
            Map<Object, Object> entries = redis.opsForHash().entries(key(tenantId));
            for (Object value : entries.values()) {
                try {
                    result.add(mapper.readValue(String.valueOf(value), Subscription.class));
                } catch (Exception malformed) {
                    log.warn("Web Push 订阅解析失败: {}", malformed.getMessage());
                }
            }
        } catch (Exception failure) {
            log.warn("读取 Web Push 订阅失败: {}", failure.getMessage());
        }
        return result;
    }

    private String key(String tenantId) {
        return "shoppilot:channel-push:" + tenantId;
    }
}
