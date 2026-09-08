package com.shoppilot.gateway.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 防击穿：同一 key 只放一个请求穿透到模型（ADR 0006）。
 *
 * <p>两层合并：进程内 {@code ConcurrentHashMap<String, CompletableFuture>} 挡住同实例的并发，
 * Redis {@code SETNX} 短锁挡住跨实例的并发。单实例部署时第二层用不上，但两层都在
 * 才讲得出分布式合并。
 *
 * <p>等待者收完整答案后一次性推送，不做 token 流广播——那是被明确否决的方案（ADR 0006）。
 */
@Component
public class SingleFlight {

    private static final Logger log = LoggerFactory.getLogger(SingleFlight.class);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);
    /** 穿透结果在 Redis 上的短暂暂存，供别的实例轮询复用；L1 写回另有自己的 TTL。 */
    private static final Duration BODY_TTL = Duration.ofSeconds(30);

    private final ConcurrentHashMap<String, CompletableFuture<Optional<CacheEntry>>> inFlight = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration waitTimeout;
    private final Counter mergedCounter;
    private final Counter lockLostCounter;

    public SingleFlight(StringRedisTemplate redis, ObjectMapper mapper, GatewayProperties properties, MeterRegistry registry) {
        this.redis = redis;
        this.mapper = mapper;
        this.waitTimeout = properties.cache().singleflightWaitTimeout();
        this.mergedCounter = Counter.builder("shoppilot_singleflight_merged_total")
                .description("等待者复用穿透结果的次数").register(registry);
        this.lockLostCounter = Counter.builder("shoppilot_singleflight_timeout_total")
                .description("等待超时后自行穿透的次数").register(registry);
    }

    /**
     * @param leader true 表示调用方需要自己去加载；false 表示 shared 里已是可复用的结果
     */
    public record Gate(boolean leader, Optional<CacheEntry> shared) {

        static Gate lead() {
            return new Gate(true, Optional.empty());
        }

        static Gate reuse(Optional<CacheEntry> entry) {
            return new Gate(false, entry);
        }
    }

    public Gate join(String cacheKey) {
        CompletableFuture<Optional<CacheEntry>> mine = new CompletableFuture<>();
        CompletableFuture<Optional<CacheEntry>> existing = inFlight.putIfAbsent(cacheKey, mine);
        if (existing != null) {
            return await(existing, cacheKey);
        }
        // 进程内没有人在跑，再看别的实例
        if (!acquireCrossInstanceLock(cacheKey)) {
            Optional<CacheEntry> appeared = pollForAnswer(cacheKey);
            if (appeared.isPresent()) {
                inFlight.remove(cacheKey);
                mergedCounter.increment();
                return Gate.reuse(appeared);
            }
            inFlight.remove(cacheKey);
            lockLostCounter.increment();
            // 等不到就自己穿透，宁可多打一次模型也不能挂住用户
            return Gate.lead();
        }
        return Gate.lead();
    }

    public void publish(String cacheKey, Optional<CacheEntry> result) {
        CompletableFuture<Optional<CacheEntry>> future = inFlight.remove(cacheKey);
        if (future != null) {
            future.complete(result);
        }
        result.ifPresent(entry -> stashBody(cacheKey, entry));
        releaseCrossInstanceLock(cacheKey);
    }

    private void stashBody(String cacheKey, CacheEntry entry) {
        try {
            redis.opsForValue().set(cacheKey, mapper.writeValueAsString(entry), BODY_TTL);
        } catch (Exception failure) {
            log.debug("穿透结果暂存失败，等待者会自行穿透: {}", failure.getMessage());
        }
    }

    public void abandon(String cacheKey) {
        CompletableFuture<Optional<CacheEntry>> future = inFlight.remove(cacheKey);
        if (future != null) {
            future.complete(Optional.empty());
        }
        releaseCrossInstanceLock(cacheKey);
    }

    private Gate await(CompletableFuture<Optional<CacheEntry>> existing, String cacheKey) {
        try {
            Optional<CacheEntry> result = existing.get(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
            mergedCounter.increment();
            return Gate.reuse(result);
        } catch (TimeoutException timedOut) {
            lockLostCounter.increment();
            return Gate.lead();
        } catch (Exception failure) {
            log.debug("等待穿透结果异常 cacheKey={}: {}", cacheKey, failure.getMessage());
            return Gate.lead();
        }
    }

    private boolean acquireCrossInstanceLock(String cacheKey) {
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(lockKey(cacheKey), "1", LOCK_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException redisUnavailable) {
            // Redis 挂了也要能服务：退化为仅进程内合并
            return true;
        }
    }

    private void releaseCrossInstanceLock(String cacheKey) {
        try {
            redis.delete(lockKey(cacheKey));
        } catch (RuntimeException redisUnavailable) {
            log.debug("释放穿透锁失败: {}", redisUnavailable.getMessage());
        }
    }

    private Optional<CacheEntry> pollForAnswer(String cacheKey) {
        long deadline = System.nanoTime() + waitTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            CacheEntry entry = readBody(cacheKey);
            if (entry != null) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    private CacheEntry readBody(String cacheKey) {
        try {
            String body = redis.opsForValue().get(cacheKey);
            if (body == null || L1Cache.NEGATIVE_MARKER.equals(body)) {
                return null;
            }
            return mapper.readValue(body, CacheEntry.class);
        } catch (Exception failure) {
            return null;
        }
    }

    private static String lockKey(String cacheKey) {
        return cacheKey + ":flight";
    }
}
