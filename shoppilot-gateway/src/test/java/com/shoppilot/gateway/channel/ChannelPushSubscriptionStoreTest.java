package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 票 181：Web Push 订阅按租户写入 Redis hash，能读回与删除。 */
@SuppressWarnings("unchecked")
class ChannelPushSubscriptionStoreTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final HashOperations<String, Object, Object> hash = mock(HashOperations.class);
    private final ChannelPushSubscriptionStore store = new ChannelPushSubscriptionStore(redis, new ObjectMapper());

    @Test
    @DisplayName("save 写 hash 并设置 TTL")
    void saveWritesHashAndTtl() {
        when(redis.opsForHash()).thenReturn(hash);

        store.save("T001", new ChannelPushSubscriptionStore.Subscription(
                "https://push.example/ep1", "p256dh", "auth"));

        verify(hash).put(anyString(), any(), anyString());
        verify(redis).expire(anyString(), any());
    }

    @Test
    @DisplayName("list 读回订阅，坏 JSON 跳过")
    void listReadsSubscriptions() {
        when(redis.opsForHash()).thenReturn(hash);
        String good = "{\"endpoint\":\"https://push.example/ep1\",\"p256dh\":\"p\",\"auth\":\"a\"}";
        when(hash.entries("shoppilot:channel-push:T001")).thenReturn(Map.of("ep1", good, "bad", "{oops"));

        List<ChannelPushSubscriptionStore.Subscription> result = store.list("T001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).endpoint()).isEqualTo("https://push.example/ep1");
    }

    @Test
    @DisplayName("remove 删除指定 endpoint")
    void removeDeletesEndpoint() {
        when(redis.opsForHash()).thenReturn(hash);

        store.remove("T001", "https://push.example/ep1");

        verify(hash).delete("shoppilot:channel-push:T001", "https://push.example/ep1");
    }
}
