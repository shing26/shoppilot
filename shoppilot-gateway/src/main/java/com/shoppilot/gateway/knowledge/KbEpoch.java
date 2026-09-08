package com.shoppilot.gateway.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 知识库纪元（ADR 0003）：政策一改，旧纪元答案整体作废，不等 TTL 自然过期。
 *
 * <p>本地缓存 5 秒再回源 Redis：纪元是全局单值，每请求一次 GET 在 1200 QPS 下
 * 是白送的往返；5 秒的陈旧窗口对 6 小时 TTL 的缓存而言可以忽略。
 */
@Component
public class KbEpoch {

    private static final Logger log = LoggerFactory.getLogger(KbEpoch.class);
    public static final String REDIS_KEY = "shoppilot:kb:epoch";

    private final StringRedisTemplate redis;
    private volatile long cached = 1L;

    public KbEpoch(StringRedisTemplate redis) {
        this.redis = redis;
        refresh();
    }

    public long current() {
        return cached;
    }

    public long bump() {
        Long next = redis.opsForValue().increment(REDIS_KEY);
        long value = next == null ? cached + 1 : next;
        this.cached = value;
        log.info("知识库纪元推进至 {}", value);
        return value;
    }

    @Scheduled(fixedDelay = 5000)
    public void refresh() {
        try {
            String value = redis.opsForValue().get(REDIS_KEY);
            if (value == null) {
                redis.opsForValue().setIfAbsent(REDIS_KEY, "1");
                cached = 1L;
            } else {
                cached = Long.parseLong(value);
            }
        } catch (RuntimeException redisUnavailable) {
            log.warn("读取知识库纪元失败，沿用本地值 {}: {}", cached, redisUnavailable.getMessage());
        }
    }
}
