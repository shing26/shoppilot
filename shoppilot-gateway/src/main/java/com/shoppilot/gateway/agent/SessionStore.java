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
 *
 * <p>归属单位是「店铺 + 买家」两者（ADR 0025）：键形状 {@code shoppilot:session:{tenant}:{customer}:{conv}}。
 * 只按店铺归属时，同店铺里任何买家把 {@code X-Conversation-Id} 填成别人的值，就能载出对方的对话轮次喂进
 * prompt，并把新轮次写回对方会话——词汇表把这件事叫**串号**。会话 id 由客户端自带是真实接入方的常态形状，
 * 所以收口点在键里加买家段，而不是改接入契约。
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

    /**
     * 载出「这位买家在这家店铺里」的会话。
     *
     * <p>买家归属缺失时返回空会话而不报错：验签通过但 token 里没有 {@code cid} 声明的凭证，
     * 归属无从判定，此时唯一安全的做法是退化成无上下文单轮（与 Redis 不可用同一条降级路），
     * 而不是给"匿名"开一个共享桶——共享桶正是本类要关的那扇门。
     */
    public Session load(String tenantId, String customerId, String conversationId) {
        if (customerId == null || customerId.isBlank()) {
            return Session.empty(conversationId);
        }
        try {
            String body = redis.opsForValue().get(key(tenantId, customerId, conversationId));
            if (body == null) {
                return Session.empty(conversationId);
            }
            return mapper.readValue(body, Session.class);
        } catch (Exception failure) {
            log.warn("读取会话失败，按新会话处理: {}", failure.getMessage());
            return Session.empty(conversationId);
        }
    }

    /** 保存会话；买家归属缺失时不落盘（理由同 {@link #load}），旧键不迁移、靠 TTL 自然走。 */
    public void save(String tenantId, String customerId, Session session) {
        if (customerId == null || customerId.isBlank()) {
            log.warn("缺少买家归属，本次会话状态不落盘：conv={}", session.conversationId());
            return;
        }
        try {
            redis.opsForValue().set(key(tenantId, customerId, session.conversationId()),
                    mapper.writeValueAsString(session), config.sessionTtl());
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

    private static String key(String tenantId, String customerId, String conversationId) {
        // 不做 trim：订单行侧的守卫比的是 token 里那个原样的 cid，"C001" 与 "C001 " 在那边是两个买家。
        // 这里一旦归一化，就等于把两道防线对"谁是同一个人"的判断拆开——会话键跟着下游那把尺子走。
        return "shoppilot:session:" + tenantId + ":" + customerId + ":" + conversationId;
    }
}
