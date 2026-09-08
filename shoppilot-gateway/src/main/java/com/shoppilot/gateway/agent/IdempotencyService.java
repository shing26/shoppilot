package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.cache.QueryNormalizer;
import com.shoppilot.tool.ToolName;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 写操作幂等与业务锁（ticket 12、ADR 0008）。
 *
 * <p>幂等键是 {@code (tenantId, customerId, action, token)}：少了 customerId，两个买家偶然
 * 撞出同一个 token 就能互相吞掉退款；少了 action，改地址会抑制掉退款。
 *
 * <p>Redis 是第一道，biz-mock 的退款单唯一约束是最后一道。Redis 停机时这里选择
 * "照常执行、交给数据库拦"，而不是拒绝一切写操作——可用性优先，正确性由约束兜底。
 */
@Component
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String PENDING = "__PENDING__";
    private static final Duration RESULT_TTL = Duration.ofHours(6);
    private static final Duration LOCK_WAIT = Duration.ofMillis(300);
    private static final Duration LOCK_LEASE = Duration.ofSeconds(10);
    private static final AutoCloseable NO_LOCK = () -> {
    };

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final Counter duplicateCounter;
    private final Counter lockBusyCounter;
    private final Counter redisBypassCounter;

    public IdempotencyService(StringRedisTemplate redis, RedissonClient redisson, MeterRegistry registry) {
        this.redis = redis;
        this.redisson = redisson;
        this.duplicateCounter = Counter.builder("shoppilot_duplicate_submit_total")
                .description("被幂等层拦下的重复写请求数").register(registry);
        this.lockBusyCounter = Counter.builder("shoppilot_write_lock_busy_total")
                .description("同一订单并发写被业务锁串行的次数").register(registry);
        this.redisBypassCounter = Counter.builder("shoppilot_idempotency_redis_bypass_total")
                .description("Redis 不可用、直接交给 DB 约束兜底的次数").register(registry);
    }

    public static boolean isWrite(ToolName tool) {
        return tool == ToolName.MODIFY_DELIVERY_ADDRESS || tool == ToolName.APPLY_REFUND;
    }

    /**
     * @param duplicate 首次结果已存在，直接回放 {@code cachedJson}，不得再次执行
     * @param lockBusy  同一订单正在被别的请求改写，本次不执行
     * @param lock      已持有的业务锁，调用方必须在 finally 里 {@link Guard#release()}
     */
    public record Guard(boolean duplicate, boolean lockBusy, String cachedJson, String token, AutoCloseable lock) {

        /** 命名避开 record 自带的 {@code duplicate()}/{@code lockBusy()} 访问器，否则无参工厂会与之冲突。 */
        static Guard replay(String cachedJson, String token) {
            return new Guard(true, false, cachedJson, token, NO_LOCK);
        }

        static Guard busy() {
            return new Guard(false, true, null, null, NO_LOCK);
        }

        static Guard proceed(String token, AutoCloseable lock) {
            return new Guard(false, false, null, token, lock);
        }

        void release() {
            try {
                lock.close();
            } catch (Exception ignored) {
                // 锁带租约，释放失败最坏是提前占用一个租约窗口
            }
        }
    }

    public Guard begin(String tenantId, String customerId, ToolName tool, Map<String, Object> arguments,
                       String clientToken) {
        String token = resolveToken(tenantId, customerId, tool, arguments, clientToken);
        String key = key(tenantId, customerId, tool, token);
        try {
            Boolean first = redis.opsForValue().setIfAbsent(key, PENDING, RESULT_TTL);
            if (!Boolean.TRUE.equals(first)) {
                String existing = redis.opsForValue().get(key);
                if (existing != null && !PENDING.equals(existing)) {
                    duplicateCounter.increment();
                    return Guard.replay(existing, token);
                }
                // 首次还挂在 PENDING 上：让并发请求排队等结果，等不到再走业务锁
                String settled = waitForResult(key);
                if (settled != null) {
                    duplicateCounter.increment();
                    return Guard.replay(settled, token);
                }
            }
        } catch (RuntimeException redisUnavailable) {
            redisBypassCounter.increment();
            log.warn("幂等存储不可用，本次写操作直接执行，重复由业务侧唯一约束兜底: {}",
                    redisUnavailable.getMessage());
        }
        AutoCloseable lock = acquireOrderLock(tenantId, arguments);
        if (lock == null) {
            return Guard.busy();
        }
        return Guard.proceed(token, lock);
    }

    public void complete(String tenantId, String customerId, ToolName tool, Guard guard, String resultJson) {
        if (guard.token() == null) {
            return;
        }
        try {
            redis.opsForValue().set(key(tenantId, customerId, tool, guard.token()), resultJson, RESULT_TTL);
        } catch (RuntimeException redisUnavailable) {
            log.warn("幂等结果未落库，后续重复提交由业务侧唯一约束兜底: {}", redisUnavailable.getMessage());
        }
    }

    /** 执行失败要清掉占位，否则用户重试会被当成"已有结果"回放一个空响应。 */
    public void abandon(String tenantId, String customerId, ToolName tool, Guard guard) {
        if (guard.token() == null) {
            return;
        }
        try {
            redis.delete(key(tenantId, customerId, tool, guard.token()));
        } catch (RuntimeException redisUnavailable) {
            log.debug("清理幂等占位失败: {}", redisUnavailable.getMessage());
        }
    }

    private String waitForResult(String key) {
        long deadline = System.nanoTime() + LOCK_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
            String value = redis.opsForValue().get(key);
            if (value != null && !PENDING.equals(value)) {
                return value;
            }
        }
        return null;
    }

    /** 返回 null 表示没拿到锁；返回 NO_LOCK 表示这一单不需要锁（无订单号的写操作）。 */
    private AutoCloseable acquireOrderLock(String tenantId, Map<String, Object> arguments) {
        Object orderNo = arguments.get("orderNo");
        if (orderNo == null) {
            return NO_LOCK;
        }
        RLock lock = redisson.getLock("shoppilot:lock:" + tenantId + ":" + orderNo);
        try {
            if (!lock.tryLock(LOCK_WAIT.toMillis(), LOCK_LEASE.toMillis(), TimeUnit.MILLISECONDS)) {
                lockBusyCounter.increment();
                return null;
            }
            return () -> {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            };
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 客户端没带 token 时按会话主体与参数派生一个确定性 token。
     *
     * <p>任务书要求客户端提供，但对话式入口里"用户把同一句话再说一遍"就是重试；
     * 派生 token 让这种重试同样只产生一条退款单。客户端显式提供时以客户端为准。
     */
    private String resolveToken(String tenantId, String customerId, ToolName tool, Map<String, Object> arguments,
                                String clientToken) {
        if (clientToken != null && !clientToken.isBlank()) {
            return clientToken.trim();
        }
        return "derived:" + QueryNormalizer.md5(tenantId + "|" + customerId + "|" + tool.apiName()
                + "|" + arguments.getOrDefault("orderNo", "") + "|" + arguments.getOrDefault("reason", ""));
    }

    private static String key(String tenantId, String customerId, ToolName tool, String token) {
        return "shoppilot:idem:" + QueryNormalizer.md5(
                tenantId + "|" + customerId + "|" + tool.apiName() + "|" + token);
    }
}
