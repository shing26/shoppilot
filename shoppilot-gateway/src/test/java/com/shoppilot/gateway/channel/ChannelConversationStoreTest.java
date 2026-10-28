package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 票 175：渠道会话入站/出站/接管/释放/租户隔离。 */
class ChannelConversationStoreTest {

    private final Map<String, String> values = new HashMap<>();
    private final Set<String> index = new HashSet<>();
    private ChannelConversationStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);
        when(valueOps.get(anyString())).thenAnswer(inv -> values.get(inv.getArgument(0)));
        doAnswer(inv -> {
            values.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(setOps.members(anyString())).thenAnswer(inv -> new HashSet<>(index));
        when(setOps.add(anyString(), anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(1);
            return index.add(id) ? 1L : 0L;
        });
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        store = new ChannelConversationStore(redis, new ObjectMapper());
    }

    @Test
    @DisplayName("入站落会话；接管后状态 AGENT 且新消息不再改变状态")
    void recordsInboundAndTakeover() {
        store.recordInbound("T001", "C001", "feishu:chat:oc1", "feishu", "订单到哪了");

        List<ChannelConversationStore.Conversation> list = store.list("T001");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).status()).isEqualTo("BOT");
        assertThat(list.get(0).lastMessage()).isEqualTo("订单到哪了");

        store.takeover("T001", "feishu:chat:oc1", "agent");
        assertThat(store.isAgent("T001", "feishu:chat:oc1")).isTrue();

        store.recordInbound("T001", "C001", "feishu:chat:oc1", "feishu", "我要人工");
        ChannelConversationStore.Conversation after = store.find("T001", "feishu:chat:oc1");
        assertThat(after.status()).isEqualTo("AGENT");
        assertThat(after.assignee()).isEqualTo("agent");
        assertThat(after.messages()).hasSize(2);
    }

    @Test
    @DisplayName("机器人回复与坐席回复都追加，释放回到 BOT")
    void recordsRepliesAndRelease() {
        store.recordInbound("T001", "C001", "feishu:chat:oc1", "feishu", "你好");
        store.recordBotReply("T001", "feishu:chat:oc1", "您好");

        assertThat(store.find("T001", "feishu:chat:oc1").messages()).hasSize(2);

        store.takeover("T001", "feishu:chat:oc1", "agent");
        store.recordAgentReply("T001", "feishu:chat:oc1", "agent", "我来处理");
        ChannelConversationStore.Conversation after = store.find("T001", "feishu:chat:oc1");
        assertThat(after.messages()).hasSize(3);
        assertThat(after.messages().get(2).role()).isEqualTo("agent");

        store.release("T001", "feishu:chat:oc1");
        assertThat(store.find("T001", "feishu:chat:oc1").status()).isEqualTo("BOT");
        assertThat(store.find("T001", "feishu:chat:oc1").assignee()).isNull();
    }

    @Test
    @DisplayName("租户隔离：T002 看不到 T001 的会话")
    void tenantIsolation() {
        store.recordInbound("T001", "C001", "feishu:chat:oc1", "feishu", "你好");

        assertThat(store.list("T001")).hasSize(1);
        assertThat(store.list("T002")).isEmpty();
        assertThat(store.find("T002", "feishu:chat:oc1")).isNull();
    }

    @Test
    @DisplayName("长会话只保留最近 30 条")
    void boundsMessages() {
        for (int i = 0; i < 35; i++) {
            store.recordInbound("T001", "C001", "feishu:chat:oc1", "feishu", "消息" + i);
        }

        assertThat(store.find("T001", "feishu:chat:oc1").messages()).hasSize(30);
    }
}
