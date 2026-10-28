package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 渠道会话收件箱（票 175）：把飞书会话落成可被坐席看到/接管/回复的记录。
 *
 * <p>Redis 单节点存储，TTL 7 天；这不是审计归档，审计仍走工单与审计流。
 * Redis 不可用时返回空列表，不阻塞聊天主链路。
 */
@Component
public class ChannelConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ChannelConversationStore.class);
    private static final Duration TTL = Duration.ofDays(7);
    private static final int MAX_MESSAGES = 30;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String text, String at) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Conversation(String conversationId, String channel, String tenantId, String customerId,
                               String status, String assignee, int unread, List<Message> messages,
                               String lastMessage, String lastAt, String handoffTicketId) {

        public Conversation {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }

        public static Conversation empty(String conversationId, String channel, String tenantId, String customerId) {
            return new Conversation(conversationId, channel, tenantId, customerId, "BOT", null, 0,
                    List.of(), "", Instant.now().toString(), null);
        }
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    @Autowired(required = false)
    private ChannelInboxEventBus eventBus;

    public ChannelConversationStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public List<Conversation> list(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();
        }
        try {
            Set<String> ids = redis.opsForSet().members(indexKey(tenantId));
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            List<Conversation> conversations = new ArrayList<>();
            for (String id : ids) {
                Conversation conversation = load(tenantId, id);
                if (conversation != null) {
                    conversations.add(conversation);
                }
            }
            conversations.sort(Comparator.comparing(Conversation::lastAt,
                    Comparator.nullsLast(Comparator.naturalOrder())).reversed());
            return conversations;
        } catch (Exception failure) {
            log.warn("读取渠道会话列表失败，降级为空: {}", failure.getMessage());
            return List.of();
        }
    }

    public Conversation find(String tenantId, String conversationId) {
        return load(tenantId, conversationId);
    }

    public Conversation markRead(String tenantId, String conversationId) {
        Conversation current = load(tenantId, conversationId);
        if (current == null || current.unread() == 0) {
            return current;
        }
        Conversation next = new Conversation(current.conversationId(), current.channel(), current.tenantId(),
                current.customerId(), current.status(), current.assignee(), 0, current.messages(),
                current.lastMessage(), current.lastAt(), current.handoffTicketId());
        save(next);
        return next;
    }

    public Conversation recordInbound(String tenantId, String customerId, String conversationId,
                                      String channel, String text) {
        Conversation current = load(tenantId, conversationId);
        if (current == null) {
            current = Conversation.empty(conversationId, channel, tenantId, customerId);
        }
        return append(current, "user", text, null);
    }

    public Conversation recordBotReply(String tenantId, String conversationId, String text) {
        Conversation current = load(tenantId, conversationId);
        if (current == null) {
            return null;
        }
        return append(current, "assistant", text, null);
    }

    public Conversation recordAgentReply(String tenantId, String conversationId, String assignee, String text) {
        Conversation current = load(tenantId, conversationId);
        if (current == null) {
            return null;
        }
        return append(current, "agent", text, assignee);
    }

    public Conversation takeover(String tenantId, String conversationId, String assignee) {
        Conversation current = load(tenantId, conversationId);
        if (current == null) {
            return null;
        }
        Conversation next = new Conversation(current.conversationId(), current.channel(), current.tenantId(),
                current.customerId(), "AGENT", assignee, 0, current.messages(), current.lastMessage(),
                current.lastAt(), current.handoffTicketId());
        save(next);
        return next;
    }

    public Conversation release(String tenantId, String conversationId) {
        Conversation current = load(tenantId, conversationId);
        if (current == null) {
            return null;
        }
        Conversation next = new Conversation(current.conversationId(), current.channel(), current.tenantId(),
                current.customerId(), "BOT", null, 0, current.messages(), current.lastMessage(),
                current.lastAt(), current.handoffTicketId());
        save(next);
        return next;
    }

    public boolean isAgent(String tenantId, String conversationId) {
        Conversation current = load(tenantId, conversationId);
        return current != null && "AGENT".equals(current.status());
    }

    private Conversation append(Conversation current, String role, String text, String assignee) {
        String at = Instant.now().toString();
        List<Message> messages = new ArrayList<>(current.messages());
        messages.add(new Message(role, text, at));
        if (messages.size() > MAX_MESSAGES) {
            messages = new ArrayList<>(messages.subList(messages.size() - MAX_MESSAGES, messages.size()));
        }
        String preview = text == null ? "" : text.replace('\n', ' ');
        if (preview.length() > 240) {
            preview = preview.substring(0, 240) + "…";
        }
        String status = "agent".equals(role) ? "AGENT" : current.status();
        String owner = "agent".equals(role) && assignee != null ? assignee : current.assignee();
        int unread = "user".equals(role) ? current.unread() + 1 : ("agent".equals(role) ? 0 : current.unread());
        Conversation next = new Conversation(current.conversationId(), current.channel(), current.tenantId(),
                current.customerId(), status, owner, unread, messages, preview, at, current.handoffTicketId());
        save(next);
        return next;
    }

    private Conversation load(String tenantId, String conversationId) {
        if (tenantId == null || tenantId.isBlank() || conversationId == null || conversationId.isBlank()) {
            return null;
        }
        try {
            String body = redis.opsForValue().get(key(tenantId, conversationId));
            return body == null ? null : mapper.readValue(body, Conversation.class);
        } catch (Exception failure) {
            log.warn("读取渠道会话失败: {}", failure.getMessage());
            return null;
        }
    }

    private void save(Conversation conversation) {
        try {
            redis.opsForValue().set(key(conversation.tenantId(), conversation.conversationId()),
                    mapper.writeValueAsString(conversation), TTL);
            redis.opsForSet().add(indexKey(conversation.tenantId()), conversation.conversationId());
            redis.expire(indexKey(conversation.tenantId()), TTL);
            if (eventBus != null) {
                eventBus.publish(conversation);
            }
        } catch (Exception failure) {
            log.warn("保存渠道会话失败: {}", failure.getMessage());
        }
    }

    private static String key(String tenantId, String conversationId) {
        return "shoppilot:channel-inbox:" + tenantId + ":" + conversationId;
    }

    private static String indexKey(String tenantId) {
        return "shoppilot:channel-inbox-index:" + tenantId;
    }
}
