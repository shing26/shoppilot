package com.shoppilot.gateway.config;

import com.shoppilot.gateway.channel.ChannelInboxEventBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * 多实例 SSE 的 Redis 订阅（票 179）：每个网关副本都监听同一个频道，
 * 收到其他副本发布的 conversation 后 fan-out 给本机客户端。
 */
@Configuration
public class ChannelInboxRedisConfig {

    @Bean
    public RedisMessageListenerContainer channelInboxListenerContainer(
            RedisConnectionFactory connectionFactory, ChannelInboxEventBus eventBus) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> eventBus.onRedisMessage(
                        new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(ChannelInboxEventBus.CHANNEL));
        return container;
    }
}
