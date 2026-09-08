package com.shoppilot.gateway.llm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * dev 模式的 token 日预算熔断（ADR 0012）。
 *
 * <p>Redis 不可用时放行：这是成本护栏，不是安全边界，不该因为护栏故障拒绝服务。
 */
@Component
public class TokenBudget {

    private static final Logger log = LoggerFactory.getLogger(TokenBudget.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final StringRedisTemplate redis;
    private final Counter exceededCounter;

    public TokenBudget(StringRedisTemplate redis, MeterRegistry registry) {
        this.redis = redis;
        this.exceededCounter = Counter.builder("shoppilot_llm_budget_exceeded_total")
                .description("token 日预算熔断触发次数")
                .register(registry);
    }

    public long usedToday() {
        try {
            String value = redis.opsForValue().get(key(LocalDate.now()));
            return value == null ? 0 : Long.parseLong(value);
        } catch (RuntimeException redisUnavailable) {
            log.warn("读取 token 预算失败，按放行处理: {}", redisUnavailable.getMessage());
            return 0;
        }
    }

    public void record(long tokens) {
        if (tokens <= 0) {
            return;
        }
        try {
            String key = key(LocalDate.now());
            Long total = redis.opsForValue().increment(key, tokens);
            if (total != null && total == tokens) {
                redis.expire(key, Duration.ofHours(36));
            }
        } catch (RuntimeException redisUnavailable) {
            log.warn("累计 token 用量失败: {}", redisUnavailable.getMessage());
        }
    }

    public void checkOrThrow(long budget) {
        if (budget <= 0) {
            return;
        }
        long used = usedToday();
        if (used >= budget) {
            exceededCounter.increment();
            throw LlmException.budgetExceeded(used, budget);
        }
    }

    private static String key(LocalDate day) {
        return "shoppilot:llm:tokens:" + DAY.format(day);
    }
}
