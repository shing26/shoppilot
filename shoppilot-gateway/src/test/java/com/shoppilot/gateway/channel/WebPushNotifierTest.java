package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 票 181：VAPID 公钥可暴露；非未读/非最后买家消息不触发推送。 */
class WebPushNotifierTest {

    private final ChannelPushSubscriptionStore store = mock(ChannelPushSubscriptionStore.class);
    private final WebPushNotifier notifier = new WebPushNotifier(
            store, new ObjectMapper(), new SimpleMeterRegistry(), "", "", "mailto:ops@shoppilot.local");

    @Test
    @DisplayName("生成的 VAPID 公钥非空")
    void exposesVapidPublicKey() {
        assertThat(notifier.publicKey()).isNotBlank();
    }

    @Test
    @DisplayName("unread=0 不查订阅")
    void ignoresReadConversation() {
        notifier.notify(conversation(0, "assistant"));

        verify(store, never()).list("T001");
    }

    @Test
    @DisplayName("unread>0 且最后是买家消息才查订阅")
    void readsSubscriptionsForUnreadCustomerMessage() {
        when(store.list("T001")).thenReturn(List.of());

        notifier.notify(conversation(1, "user"));

        verify(store).list("T001");
    }

    private ChannelConversationStore.Conversation conversation(int unread, String lastRole) {
        return new ChannelConversationStore.Conversation(
                "feishu:chat:oc1", "feishu", "T001", "C001", "BOT", null, unread,
                List.of(new ChannelConversationStore.Message(lastRole, "你好", "2026-10-29T00:00:00Z")),
                "你好", "2026-10-29T00:00:00Z", null);
    }
}
