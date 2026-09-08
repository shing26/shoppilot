package com.shoppilot.gateway.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话状态外置到 Redis：虚拟线程本身无状态，状态外置才能水平扩（ticket 11）。
 *
 * <p>Redis 不可用时降级为"无上下文单轮"，不影响可用性。
 */
@Component
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Turn(String role, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Session(String conversationId, List<Turn> turns, String pendingTool,
                          Map<String, Object> pendingArgs, int slotAskCount) {

        public static Session empty(String conversationId) {
            return new Session(conversationId, new ArrayList<>(), null, new LinkedHashMap<>(), 0);
        }

        public boolean hasPending() {
            return pendingTool != null && !pendingTool.isBlank();
        }
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final GatewayProperties.Agent config;

    public SessionStore(StringRedisTemplate redis, ObjectMapper mapper, GatewayProperties properties) {
        this.redis = redis;
        this.mapper = mapper;
        this.config = properties.agent();
    }

    public Session load(String tenantId, String conversationId) {
        try {
            String body = redis.opsForValue().get(key(tenantId, conversationId));
            if (body == null) {
                return Session.empty(conversationId);
            }
            return mapper.readValue(body, Session.class);
        } catch (Exception failure) {
            log.warn("读取会话失败，按新会话处理: {}", failure.getMessage());
            return Session.empty(conversationId);
        }
    }

    public void save(String tenantId, Session session) {
        try {
            redis.opsForValue().set(key(tenantId, session.conversationId()), mapper.writeValueAsString(session),
                    config.sessionTtl());
        } catch (Exception failure) {
            log.warn("保存会话失败: {}", failure.getMessage());
        }
    }

    /** 只保留最近 N 轮，防止长会话把 prompt 撑爆。 */
    public Session appendTurn(Session session, String userText, String assistantText) {
        List<Turn> turns = new ArrayList<>(session.turns() == null ? List.of() : session.turns());
        turns.add(new Turn("user", userText));
        if (assistantText != null) {
            turns.add(new Turn("assistant", assistantText));
        }
        Deque<Turn> window = new ArrayDeque<>();
        for (Turn turn : turns) {
            window.addLast(turn);
            while (window.size() > config.historyTurns() * 2) {
                window.removeFirst();
            }
        }
        return new Session(session.conversationId(), new ArrayList<>(window), session.pendingTool(),
                session.pendingArgs(), session.slotAskCount());
    }

    private static String key(String tenantId, String conversationId) {
        return "shoppilot:session:" + tenantId + ":" + conversationId;
    }
}
