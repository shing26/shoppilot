package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 渠道会话 SSE 事件总线（票 177 / 179）。
 *
 * <p>本地 fan-out 只负责连在本实例上的 SseEmitter；发布走 Redis pub/sub，
 * 所有实例的监听器收到后各自 fan-out。这样坐席连 A、消息落 B 也能收到。
 * Redis 发布失败或无订阅者时回退本地 fan-out，单实例仍可用。
 */
@Component
public class ChannelInboxEventBus {

    public static final String CHANNEL = "shoppilot:channel-inbox-events";

    private static final Logger log = LoggerFactory.getLogger(ChannelInboxEventBus.class);

    private record Subscription(String tenantId, SseEmitter emitter) {
    }

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;

    @Autowired(required = false)
    private WebPushNotifier pushNotifier;

    public ChannelInboxEventBus(ObjectMapper mapper, StringRedisTemplate redis) {
        this.mapper = mapper;
        this.redis = redis;
    }

    public SseEmitter subscribe(String tenantId) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscription subscription = new Subscription(tenantId, emitter);
        subscriptions.add(subscription);
        Runnable remove = () -> subscriptions.remove(subscription);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(failure -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data("{}"));
        } catch (Exception failure) {
            subscriptions.remove(subscription);
            emitter.completeWithError(failure);
        }
        return emitter;
    }

    public void publish(ChannelConversationStore.Conversation conversation) {
        if (conversation == null) {
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(conversation);
        } catch (Exception failure) {
            log.warn("渠道会话事件序列化失败: {}", failure.getMessage());
            return;
        }
        if (pushNotifier != null) {
            pushNotifier.notify(conversation);
        }
        try {
            Long receivers = redis.convertAndSend(CHANNEL, json);
            if (receivers != null && receivers > 0) {
                return;
            }
            log.warn("渠道会话事件无 Redis 订阅者，回退本地 fan-out");
        } catch (Exception unavailable) {
            log.warn("渠道会话事件发布失败，回退本地 fan-out: {}", unavailable.getMessage());
        }
        fanOut(conversation);
    }

    /** Redis listener 调用：其他实例/本实例收到的消息，只做本地 fan-out，不再发布。 */
    public void onRedisMessage(String json) {
        try {
            fanOut(mapper.readValue(json, ChannelConversationStore.Conversation.class));
        } catch (Exception malformed) {
            log.warn("渠道会话事件解析失败: {}", malformed.getMessage());
        }
    }

    private void fanOut(ChannelConversationStore.Conversation conversation) {
        String json;
        try {
            json = mapper.writeValueAsString(conversation);
        } catch (Exception failure) {
            log.warn("渠道会话事件序列化失败: {}", failure.getMessage());
            return;
        }
        for (Subscription subscription : subscriptions) {
            if (!subscription.tenantId().equals(conversation.tenantId())) {
                continue;
            }
            try {
                subscription.emitter().send(SseEmitter.event().name("conversation").data(json));
            } catch (Exception disconnected) {
                subscriptions.remove(subscription);
                try {
                    subscription.emitter().complete();
                } catch (Exception ignored) {
                    // 已经断开
                }
            }
        }
    }
}
