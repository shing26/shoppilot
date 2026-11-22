package com.shoppilot.gateway.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 票 225 / ADR 0087：幂等键落 Redis，跨实例（跨重启）仍有效。 */
class RedisDeliveredEventStoreTest {

    @Test
    @DisplayName("A 实例标记后，B 实例（模拟重启）能读到同一个 eventId")
    void deliveredKeySurvivesRestart() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        Set<String> keys = new HashSet<>();
        when(redis.opsForValue()).thenReturn(values);
        when(redis.hasKey(anyString())).thenAnswer(call -> keys.contains(call.getArgument(0)));
        doAnswer(call -> {
            keys.add(call.getArgument(0));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));

        RedisDeliveredEventStore beforeRestart = new RedisDeliveredEventStore(redis, Duration.ofDays(7));
        assertThat(beforeRestart.isDelivered("ev-restart")).isFalse();
        beforeRestart.markDelivered("ev-restart");

        RedisDeliveredEventStore afterRestart = new RedisDeliveredEventStore(redis, Duration.ofDays(7));
        assertThat(afterRestart.isDelivered("ev-restart")).isTrue();
        verify(values).set(eq(RedisDeliveredEventStore.PREFIX + "ev-restart"), eq("1"), eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("Redis 不可用：退回进程内去重，不让重复投递打穿")
    void redisFailureFallsBackToProcessLocal() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        RedisDeliveredEventStore store = new RedisDeliveredEventStore(redis, Duration.ofDays(7));

        store.markDelivered("ev-local");

        assertThat(store.isDelivered("ev-local")).isTrue();
    }
}
