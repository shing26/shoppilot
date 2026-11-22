package com.shoppilot.gateway.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 出站重试的退避曲线（round56 票 225 / ADR 0087）。
 *
 * <p>默认 3 次、500ms 起步、×2、上限 5s、±20% 抖动。抖动不是装饰：
 * 多个网关实例同时重试同一个下游时，固定间隔会把恢复窗口打成雪崩。
 */
@Component
public class RetryPolicy {

    private final int attempts;
    private final Duration base;
    private final double factor;
    private final Duration max;
    private final double jitter;

    public RetryPolicy(@Value("${shoppilot.outbound.attempts:3}") int attempts,
                       @Value("${shoppilot.outbound.retry-base:500ms}") Duration base,
                       @Value("${shoppilot.outbound.retry-factor:2.0}") double factor,
                       @Value("${shoppilot.outbound.retry-max:5s}") Duration max,
                       @Value("${shoppilot.outbound.retry-jitter:0.2}") double jitter) {
        this.attempts = Math.max(1, attempts);
        this.base = base.isNegative() ? Duration.ZERO : base;
        this.factor = factor < 1 ? 1 : factor;
        this.max = max.isNegative() ? Duration.ZERO : max;
        this.jitter = Math.max(0, Math.min(1, jitter));
    }

    public int attempts() {
        return attempts;
    }

    /** 第 n 次失败后到下一次尝试的等待（n 从 1 开始）。 */
    public Duration delay(int failedAttempt) {
        double millis = base.toMillis() * Math.pow(factor, Math.max(0, failedAttempt - 1));
        long capped = (long) Math.min(millis, max.toMillis());
        if (jitter <= 0 || capped <= 0) {
            return Duration.ofMillis(capped);
        }
        long spread = (long) (capped * jitter);
        long low = Math.max(0, capped - spread);
        long high = capped + spread;
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(low, high + 1));
    }

    /** 测试用确定性曲线：1ms、无抖动。 */
    public static RetryPolicy fast() {
        return new RetryPolicy(3, Duration.ofMillis(1), 2, Duration.ofMillis(10), 0);
    }

    /** 活体验收用：让重试间隔在几秒内可观测。 */
    public static RetryPolicy of(int attempts, Duration base, double factor, Duration max, double jitter) {
        return new RetryPolicy(attempts, base, factor, max, jitter);
    }
}
