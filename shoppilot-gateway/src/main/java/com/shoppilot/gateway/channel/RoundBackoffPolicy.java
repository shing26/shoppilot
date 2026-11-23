package com.shoppilot.gateway.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 跨轮重投的退避曲线（round57 票 227 / ADR 0087 §4）。
 *
 * <p>它和 {@link RetryPolicy} 是两条不同的时间轴：
 * <ul>
 *   <li>{@code RetryPolicy} 管「同一轮里对下游的 3 次尝试」；</li>
 *   <li>{@code RoundBackoffPolicy} 管「回执未落定、事件留在 PEL 后，隔多久再领一次」。</li>
 * </ul>
 * 把两者分开，既不改 ADR 0087 已冻结的单轮曲线，也不会让跨轮重试每 5 秒重打一次。
 *
 * <p>默认 5s 起步、×2、上限 5 分钟、±20% 抖动。抖动不是装饰：多个网关实例同时重试
 * 同一个下游时，固定间隔会把恢复窗口打成雪崩。
 */
@Component
public class RoundBackoffPolicy {

    private final Duration base;
    private final double factor;
    private final Duration max;
    private final double jitter;

    public RoundBackoffPolicy(
            @Value("${shoppilot.outbound.round-backoff-base:5s}") Duration base,
            @Value("${shoppilot.outbound.round-backoff-factor:2.0}") double factor,
            @Value("${shoppilot.outbound.round-backoff-max:5m}") Duration max,
            @Value("${shoppilot.outbound.round-backoff-jitter:0.2}") double jitter) {
        this.base = base.isNegative() ? Duration.ZERO : base;
        this.factor = factor < 1 ? 1 : factor;
        this.max = max.isNegative() ? Duration.ZERO : max;
        this.jitter = Math.max(0, Math.min(1, jitter));
    }

    /** 第 n 轮失败后到下一轮重投的等待（n 从 1 开始）。 */
    public Duration delay(int failedRounds) {
        double millis = base.toMillis() * Math.pow(factor, Math.max(0, failedRounds - 1));
        long capped = (long) Math.min(millis, max.toMillis());
        if (jitter <= 0 || capped <= 0) {
            return Duration.ofMillis(capped);
        }
        long spread = (long) (capped * jitter);
        long low = Math.max(0, capped - spread);
        long high = capped + spread;
        long jittered = ThreadLocalRandom.current().nextLong(low, high + 1);
        // 抖动之后再封顶：契约写的是「受 max 封顶」，默认 5m 不能因为 ±20% 抖到 6m。
        return Duration.ofMillis(Math.min(jittered, max.toMillis()));
    }

    /** 测试用确定性曲线：1ms、无抖动。 */
    public static RoundBackoffPolicy fast() {
        return new RoundBackoffPolicy(Duration.ofMillis(1), 2, Duration.ofMillis(10), 0);
    }

    /** 活体验收/测试用：显式给定曲线。 */
    public static RoundBackoffPolicy of(Duration base, double factor, Duration max, double jitter) {
        return new RoundBackoffPolicy(base, factor, max, jitter);
    }
}
