package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 票 179：会话事件发布到 Redis 频道，远端消息可回灌本地。 */
class ChannelInboxEventBusTest {

    @Test
    @DisplayName("publish 发到 Redis 频道，payload 是 conversation JSON")
    void publishSendsToRedisChannel() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.convertAndSend(anyString(), anyString())).thenReturn(1L);
        ChannelInboxEventBus bus = new ChannelInboxEventBus(new ObjectMapper(), redis);
        ChannelConversationStore.Conversation conversation = new ChannelConversationStore.Conversation(
                "feishu:chat:oc1", "feishu", "T001", "C001", "BOT", null, 1,
                List.of(new ChannelConversationStore.Message("user", "你好", "2026-10-28T00:00:00Z")),
                "你好", "2026-10-28T00:00:00Z", null);

        bus.publish(conversation);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(org.mockito.ArgumentMatchers.eq(ChannelInboxEventBus.CHANNEL), payload.capture());
        assertThat(payload.getValue()).contains("feishu:chat:oc1").contains("T001");
    }

    @Test
    @DisplayName("坏 JSON 的远端事件只记日志，不抛异常")
    void malformedRemoteEventDoesNotThrow() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ChannelInboxEventBus bus = new ChannelInboxEventBus(new ObjectMapper(), redis);

        bus.onRedisMessage("{not-json");
    }
}
