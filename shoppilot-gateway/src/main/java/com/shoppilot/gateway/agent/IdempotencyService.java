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
import java.util.Optional;
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
     * @param clientToken 客户端**显式**给的 token；派生 token 时为 null。只有显式 token 才允许写请求级索引
     * @param requestFingerprint 本轮 query 的指纹（归一化后哈希）。它让"客户端复用了旧 token 但换了要求"
     *                           不会命中回放——那种情况是客户端契约违约，该放行给正常链路
     * @param lock      已持有的业务锁，调用方必须在 finally 里 {@link Guard#release()}
     */
    public record Guard(boolean duplicate, boolean lockBusy, String cachedJson, String token, String clientToken,
                        String requestFingerprint, AutoCloseable lock) {

        /** 命名避开 record 自带的 {@code duplicate()}/{@code lockBusy()} 访问器，否则无参工厂会与之冲突。 */
        static Guard replay(String cachedJson, String token, String clientToken, String requestFingerprint) {
            return new Guard(true, false, cachedJson, token, clientToken, requestFingerprint, NO_LOCK);
        }

        static Guard busy() {
            return new Guard(false, true, null, null, null, null, NO_LOCK);
        }

        static Guard proceed(String token, String clientToken, String requestFingerprint, AutoCloseable lock) {
            return new Guard(false, false, null, token, clientToken, requestFingerprint, lock);
        }

        /**
         * 这一次要不要写请求级回放索引。三条同时成立才行：token 由客户端显式提供（派生 token 在模型之前
         * 算不出来，也无法被客户端复用）、有请求指纹（缺它就无法区分"同一次请求"与"复用旧 token"）、
         * 且不是被业务拒绝的那一次（拒绝走 {@link #abandon}，索引与结果一起清掉）。
         */
        boolean requestIndexable() {
            return clientToken != null && !clientToken.isBlank()
                    && requestFingerprint != null && !requestFingerprint.isBlank();
        }

        void release() {
            try {
                lock.close();
            } catch (Exception ignored) {
                // 锁带租约，释放失败最坏是提前占用一个租约窗口
            }
        }
    }

    /** 请求级回放命中时的结果：哪个工具、以及它当时返回的原文。 */
    public record Replay(ToolName tool, String resultJson) {
    }

    /** 不带请求指纹的入口：行为与 ADR 0008 时期完全一致（不写、也不查请求级索引）。 */
    public Guard begin(String tenantId, String customerId, ToolName tool, Map<String, Object> arguments,
                       String clientToken) {
        return begin(tenantId, customerId, tool, arguments, clientToken, null);
    }

    public Guard begin(String tenantId, String customerId, ToolName tool, Map<String, Object> arguments,
                       String clientToken, String requestFingerprint) {
        String token = resolveToken(tenantId, customerId, tool, arguments, clientToken);
        String key = key(tenantId, customerId, tool, token);
        try {
            Boolean first = redis.opsForValue().setIfAbsent(key, PENDING, RESULT_TTL);
            if (!Boolean.TRUE.equals(first)) {
                String existing = redis.opsForValue().get(key);
                if (existing != null && !PENDING.equals(existing)) {
                    duplicateCounter.increment();
                    return Guard.replay(existing, token, clientToken, requestFingerprint);
                }
                // 首次还挂在 PENDING 上：让并发请求排队等结果，等不到再走业务锁
                String settled = waitForResult(key);
                if (settled != null) {
                    duplicateCounter.increment();
                    return Guard.replay(settled, token, clientToken, requestFingerprint);
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
        return Guard.proceed(token, clientToken, requestFingerprint, lock);
    }

    public void complete(String tenantId, String customerId, ToolName tool, Guard guard, String resultJson) {
        if (guard.token() == null) {
            return;
        }
        try {
            redis.opsForValue().set(key(tenantId, customerId, tool, guard.token()), resultJson, RESULT_TTL);
            if (guard.requestIndexable()) {
                // 索引只存指针（工具名 + 指纹 + 幂等键），不复制结果本身：结果的唯一真相仍是上面那个键。
                // 复制一份就等于同一事实开两个出口，两边迟早不一致。
                redis.opsForValue().set(requestKey(tenantId, customerId, guard.clientToken()),
                        tool.apiName() + "|" + guard.requestFingerprint() + "|" + guard.token(), RESULT_TTL);
            }
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
            if (guard.requestIndexable()) {
                // 索引与结果同生共死：留下一个指向已删结果的指针，下次重试会拿到空回放
                redis.delete(requestKey(tenantId, customerId, guard.clientToken()));
            }
        } catch (RuntimeException redisUnavailable) {
            log.debug("清理幂等占位失败: {}", redisUnavailable.getMessage());
        }
    }

    /**
     * 请求级回放查询：客户端带着同一个 token 重试**同一次请求**时，回放首次结果，不问模型。
     *
     * <p>为什么要在模型之前：重复检测原先只在 {@code ToolDispatcher.executeWrite} 里，也就是"模型这一轮
     * 又发了一次同样的工具调用"才触发。模型不发（本地 3B 在已含上一轮成功答复的会话里就不发）时，请求会
     * 直接走到 {@code done} 而答案是空的——**既没有回放、也没有降级话术**（round20 spec 登记节第 5 项，
     * 探针 3/3 复现）。而 {@code idempotencyToken} 的存在意义恰恰是"客户端重试不该重复执行、应回放"，
     * 把这条承诺挂在模型行为上是设计缺陷，不是模型缺陷。
     *
     * <p>指纹不等就**放行给正常链路**（返回 empty）：token 的语义是"同一逻辑请求"，客户端复用了旧 token
     * 却换了要求属于契约违约，此时按新请求处理才是对的；"重复执行"仍由 post-model 那道幂等兜住。
     *
     * <p>Redis 不可用时返回 empty（照常走模型），与 {@link #begin} 的"可用性优先"口径一致。
     */
    public Optional<Replay> lookupByClientToken(String tenantId, String customerId, String clientToken,
                                                String requestFingerprint) {
        if (clientToken == null || clientToken.isBlank()
                || requestFingerprint == null || requestFingerprint.isBlank()) {
            return Optional.empty();
        }
        try {
            String pointer = redis.opsForValue().get(requestKey(tenantId, customerId, clientToken.trim()));
            if (pointer == null || pointer.isBlank()) {
                return Optional.empty();
            }
            String[] parts = pointer.split("\\|", 3);
            if (parts.length != 3 || !requestFingerprint.equals(parts[1])) {
                return Optional.empty();
            }
            ToolName tool = ToolName.fromApiName(parts[0]);
            if (tool == null || parts[2].isBlank()) {
                return Optional.empty();
            }
            String result = redis.opsForValue().get(key(tenantId, customerId, tool, parts[2]));
            if (result == null || result.isBlank() || PENDING.equals(result)) {
                // 结果被 TTL 收走或被 abandon 清掉了：此时没有可信的"上一次"，放行走正常链路
                return Optional.empty();
            }
            duplicateCounter.increment();
            return Optional.of(new Replay(tool, result));
        } catch (RuntimeException redisUnavailable) {
            log.warn("请求级回放查询失败，本次按正常链路处理: {}", redisUnavailable.getMessage());
            return Optional.empty();
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
     *
     * <p>参数必须**整个**进哈希。早先只放 orderNo 与 reason，于是同一笔订单改成两个不同地址会被
     * 算成同一次提交：第二次直接回放第一次的结果，用户听到"已修改"而地址根本没动。门禁 eval 冒烟
     * 实测到这一形态（ACT-ADR-03 把 ACT-ADR-01 的地址复述了一遍，三条 ADDRESS 用例全是
     * {@code IDEMPOTENT_REPLAY}）。留空的可选项不参与哈希，"city 传空串"与"city 不传"是同一次提交。
     */
    private String resolveToken(String tenantId, String customerId, ToolName tool, Map<String, Object> arguments,
                                String clientToken) {
        if (clientToken != null && !clientToken.isBlank()) {
            return clientToken.trim();
        }
        return "derived:" + QueryNormalizer.md5(tenantId + "|" + customerId + "|" + tool.apiName()
                + "|" + canonicalArguments(arguments));
    }

    /** 按 key 排序拼参数，忽略 Map 迭代顺序与空白值；同一组参数永远派生出同一个 token。 */
    static String canonicalArguments(Map<String, Object> arguments) {
        StringBuilder joined = new StringBuilder();
        arguments.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Object value = entry.getValue();
                    String text = value == null ? "" : String.valueOf(value).trim();
                    if (!text.isEmpty()) {
                        joined.append(entry.getKey()).append('=').append(text).append('&');
                    }
                });
        return joined.toString();
    }

    private static String key(String tenantId, String customerId, ToolName tool, String token) {
        return "shoppilot:idem:" + QueryNormalizer.md5(
                tenantId + "|" + customerId + "|" + tool.apiName() + "|" + token);
    }

    /**
     * 请求级索引的键。它按「谁是同一个买家 + 客户端给的哪一个 token」定位，**不含工具名**——
     * 回放要回答的问题是"这个 token 代表的那次请求办成了什么"，而不是"某个工具重复了吗"。
     */
    private static String requestKey(String tenantId, String customerId, String clientToken) {
        return "shoppilot:idem:req:" + QueryNormalizer.md5(
                tenantId + "|" + customerId + "|" + clientToken);
    }
}
