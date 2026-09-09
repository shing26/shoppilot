package com.shoppilot.gateway.llm;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * dev 模式的 token 日预算（ADR 0012）：它是成本护栏，不是安全边界。
 *
 * <p>所以两条性质都要钉住：用尽之后必须拒（否则账单没有上限），
 * 而 Redis 自己挂掉时必须放行（否则护栏故障会演变成整个客服网关不可用）。
 */
class TokenBudgetTest {

    private static final String TODAY_KEY =
            "shoppilot:llm:tokens:" + DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now());

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SimpleMeterRegistry registry;
    private TokenBudget budget;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        registry = new SimpleMeterRegistry();
        budget = new TokenBudget(redis, registry);
    }

    @Test
    @DisplayName("预算内放行，用量按当天 key 累加")
    void passesWhileUnderBudgetAndAccumulates() {
        when(values.get(TODAY_KEY)).thenReturn("150000");
        assertThatCode(() -> budget.checkOrThrow(200_000L)).doesNotThrowAnyException();

        when(values.increment(eq(TODAY_KEY), eq(1200L))).thenReturn(151200L);
        budget.record(1200L);

        verify(values).increment(TODAY_KEY, 1200L);
        // 不是当天第一笔，就不该再续一次 36 小时 TTL
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("用尽即拒：抛 BUDGET_EXCEEDED，状态机据此落可查工单")
    void throwsWhenBudgetExhausted() {
        when(values.get(TODAY_KEY)).thenReturn("200000");

        assertThatThrownBy(() -> budget.checkOrThrow(200_000L))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).kind())
                .isEqualTo(LlmException.Kind.BUDGET_EXCEEDED);
        assertThat(registry.get("shoppilot_llm_budget_exceeded_total").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("预算配成 0 表示不设限，连 Redis 都不碰")
    void zeroBudgetMeansNoLimit() {
        assertThatCode(() -> budget.checkOrThrow(0L)).doesNotThrowAnyException();

        verifyNoInteractions(values);
    }

    @Test
    @DisplayName("Redis 挂了按放行处理：护栏故障不该让网关拒绝服务")
    void failsOpenWhenRedisIsUnavailable() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("Unable to connect to Redis"));

        assertThat(budget.usedToday()).isZero();
        assertThatCode(() -> budget.checkOrThrow(200_000L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("当天第一笔才设 TTL，跨天自动换 key")
    void setsTtlOnlyOnFirstWriteOfTheDay() {
        when(values.increment(TODAY_KEY, 500L)).thenReturn(500L);
        budget.record(500L);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(redis).expire(eq(TODAY_KEY), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofHours(36));

        // 读与写必须落在同一个"当天"key 上，否则跨天永远熔断不了
        when(values.get(TODAY_KEY)).thenReturn("500");
        assertThat(budget.usedToday()).isEqualTo(500L);
        verify(values).get(TODAY_KEY);
    }

    @Test
    @DisplayName("零与负数不记账：没打模型就不该污染预算")
    void ignoresNonPositiveUsage() {
        budget.record(0L);
        budget.record(-5L);

        verifyNoInteractions(values);
    }
}
