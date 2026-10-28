package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 渠道会话 SSE 事件总线（票 177）：按租户把 conversation 变更推给工作台。
 *
 * <p>只推“发生了什么”，不推全量列表；客户端收到后替换单条会话。
 * 连接断开就移除，不阻塞后续发布。
 */
@Component
public class ChannelInboxEventBus {

    private static final Logger log = LoggerFactory.getLogger(ChannelInboxEventBus.class);

    private record Subscription(String tenantId, SseEmitter emitter) {
    }

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper;

    public ChannelInboxEventBus(ObjectMapper mapper) {
        this.mapper = mapper;
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
