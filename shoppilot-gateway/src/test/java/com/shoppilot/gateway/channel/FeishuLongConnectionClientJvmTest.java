package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.ws.Client;
import com.shoppilot.gateway.agent.AgentResult;
import com.shoppilot.gateway.agent.AgentStateMachine;
import com.shoppilot.gateway.agent.EventSink;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * round31 票 113：飞书长连接客户端的 JVM 测试。
 *
 * <p>守的是 handleEvent() 的编排契约：事件归一 → 身份注入 → 状态机调用 → 回复投递。
 * 不守 WebSocket 握手本身（那是票 115 的活体）。
 */
class FeishuLongConnectionClientJvmTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FeishuAdapter adapter;
    private AgentStateMachine agentStateMachine;
    private FeishuReplySender replySender;
    private GatewayProperties properties;
    private FeishuLongConnectionClient client;

    @BeforeEach
    void setUp() {
        adapter = new FeishuAdapter();
        agentStateMachine = mock(AgentStateMachine.class);
        replySender = mock(FeishuReplySender.class);
        properties = mock(GatewayProperties.class);
        client = new FeishuLongConnectionClient(adapter, agentStateMachine, replySender, properties, "127.0.0.1");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        ChannelContext.clear();
    }

    @Test
    @DisplayName("handleEvent 编排：归一 → 身份注入 → 状态机调用 → 回复投递")
    void handleEventOrchestratesNormally() {
        Map<String, Object> payload = Map.of("event", Map.of(
                "message", Map.of(
                        "message_id", "om_test_001",
                        "chat_id", "oc_test_chat",
                        "chat_type", "p2p",
                        "message_type", "text",
                        "content", "{\"text\":\"你好\"}"
                )
        ));

        AgentResult result = new AgentResult("你好，有什么可以帮你？", null, "L1",
                CacheService.Layer.NONE, List.of(), List.of(), null, null, false, 0, 0,
                false, false, "v1", List.of(), AgentResult.ContextComposition.NONE);
        when(agentStateMachine.run(anyString(), anyString(), any(EventSink.class))).thenReturn(result);

        client.handleEvent(payload);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentStateMachine).run(queryCaptor.capture(), tokenCaptor.capture(), any(EventSink.class));

        assertThat(queryCaptor.getValue()).isEqualTo("你好");
        assertThat(tokenCaptor.getValue()).isEqualTo("feishu:msg:om_test_001");

        verify(replySender).send("oc_test_chat", "你好，有什么可以帮你？");
    }

    @Test
    @DisplayName("handleEvent 注入飞书身份：tenantId=feishu, conversationId 派生正确")
    void handleEventInjectsFeishuIdentity() {
        Map<String, Object> payload = Map.of("event", Map.of(
                "message", Map.of(
                        "message_id", "om_id_001",
                        "chat_id", "oc_identity_test",
                        "chat_type", "p2p",
                        "message_type", "text",
                        "content", "{\"text\":\"测试\"}"
                )
        ));

        AgentResult result = new AgentResult("回复", null, "L1",
                CacheService.Layer.NONE, List.of(), List.of(), null, null, false, 0, 0,
                false, false, "v1", List.of(), AgentResult.ContextComposition.NONE);
        // 在 run() 执行时捕获 TenantContext 状态（handleEvent 完成后 finally 会清理）
        TenantContext.Identity[] capturedIdentity = new TenantContext.Identity[1];
        doAnswer(invocation -> {
            capturedIdentity[0] = TenantContext.current();
            return result;
        }).when(agentStateMachine).run(anyString(), anyString(), any(EventSink.class));

        client.handleEvent(payload);

        assertThat(capturedIdentity[0]).isNotNull();
        assertThat(capturedIdentity[0].tenantId()).isEqualTo("feishu");
        assertThat(capturedIdentity[0].conversationId()).isEqualTo("feishu:chat:oc_identity_test");
    }

    @Test
    @DisplayName("handleEvent 空答案不投递回复")
    void handleEventSkipsEmptyAnswer() {
        Map<String, Object> payload = Map.of("event", Map.of(
                "message", Map.of(
                        "message_id", "om_empty_001",
                        "chat_id", "oc_empty_test",
                        "chat_type", "p2p",
                        "message_type", "text",
                        "content", "{\"text\":\"你好\"}"
                )
        ));

        AgentResult result = new AgentResult("", null, "L1",
                CacheService.Layer.NONE, List.of(), List.of(), null, null, false, 0, 0,
                false, false, "v1", List.of(), AgentResult.ContextComposition.NONE);
        when(agentStateMachine.run(anyString(), anyString(), any(EventSink.class))).thenReturn(result);

        client.handleEvent(payload);

        verify(replySender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("handleEvent 坏形状当场拒：非 text 消息类型")
    void handleEventRejectsNonTextMessage() {
        Map<String, Object> payload = Map.of("event", Map.of(
                "message", Map.of(
                        "message_id", "om_img_001",
                        "chat_id", "oc_img_test",
                        "chat_type", "p2p",
                        "message_type", "image",
                        "content", "{\"image_key\":\"img_001\"}"
                )
        ));

        assertThatThrownBy(() -> client.handleEvent(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持 text");

        verify(agentStateMachine, never()).run(anyString(), anyString(), any(EventSink.class));
    }

    @Test
    @DisplayName("handleEvent 坏形状当场拒：非飞书事件格式")
    void handleEventRejectsNonFeishuFormat() {
        Map<String, Object> payload = Map.of("query", "这不是飞书事件");

        assertThatThrownBy(() -> client.handleEvent(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("飞书");

        verify(agentStateMachine, never()).run(anyString(), anyString(), any(EventSink.class));
    }

    @Test
    @DisplayName("handleEvent 异常时清理 ThreadLocal")
    void handleEventCleansThreadLocalOnException() {
        Map<String, Object> payload = Map.of("event", Map.of(
                "message", Map.of(
                        "message_id", "om_err_001",
                        "chat_id", "oc_err_test",
                        "chat_type", "p2p",
                        "message_type", "text",
                        "content", "{\"text\":\"触发异常\"}"
                )
        ));

        when(agentStateMachine.run(anyString(), anyString(), any(EventSink.class)))
                .thenThrow(new RuntimeException("模拟状态机异常"));

        assertThatThrownBy(() -> client.handleEvent(payload))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("模拟状态机异常");

        assertThat(TenantContext.present()).isFalse();
        assertThat(ChannelContext.current()).isEqualTo(Channel.WEB);
    }

    @Test
    @DisplayName("stop 清理 wsClient 引用")
    void stopClearsWsClientReference() {
        // 通过反射设置 wsClient 字段，然后验证 stop() 清理它
        try {
            java.lang.reflect.Field field = FeishuLongConnectionClient.class.getDeclaredField("wsClient");
            field.setAccessible(true);
            Client mockClient = mock(Client.class);
            field.set(client, mockClient);

            client.stop();

            assertThat(field.get(client)).isNull();
        } catch (Exception e) {
            throw new RuntimeException("反射操作失败", e);
        }
    }

    @Test
    @DisplayName("变异对照：clientToken 派生依赖 message_id，缺失时归一抛异常")
    void mutationClientTokenDerivationRequiresMessageId() {
        Map<String, Object> payload = Map.of("event", Map.of(
                "message", Map.of(
                        "chat_id", "oc_no_msg_id",
                        "chat_type", "p2p",
                        "message_type", "text",
                        "content", "{\"text\":\"没有 message_id\"}"
                )
        ));

        assertThatThrownBy(() -> client.handleEvent(payload))
                .isInstanceOf(IllegalArgumentException.class);

        verify(agentStateMachine, never()).run(anyString(), anyString(), any(EventSink.class));
    }

    @Test
    @DisplayName("变异对照：conversationId 派生依赖 chat_id，缺失时归一抛异常")
    void mutationConversationIdDerivationRequiresChatId() {
        Map<String, Object> payload = Map.of("event", Map.of(
                "message", Map.of(
                        "message_id", "om_no_chat",
                        "chat_type", "p2p",
                        "message_type", "text",
                        "content", "{\"text\":\"没有 chat_id\"}"
                )
        ));

        assertThatThrownBy(() -> client.handleEvent(payload))
                .isInstanceOf(IllegalArgumentException.class);

        verify(agentStateMachine, never()).run(anyString(), anyString(), any(EventSink.class));
    }

    @Test
    @DisplayName("EventSink.NOOP 传入状态机：飞书通道无 SSE 推送")
    void handleEventPassesNoopEventSink() {
        Map<String, Object> payload = Map.of("event", Map.of(
                "message", Map.of(
                        "message_id", "om_noop_001",
                        "chat_id", "oc_noop_test",
                        "chat_type", "p2p",
                        "message_type", "text",
                        "content", "{\"text\":\"测试 NOOP\"}"
                )
        ));

        AgentResult result = new AgentResult("回复", null, "L1",
                CacheService.Layer.NONE, List.of(), List.of(), null, null, false, 0, 0,
                false, false, "v1", List.of(), AgentResult.ContextComposition.NONE);
        when(agentStateMachine.run(anyString(), anyString(), any(EventSink.class))).thenReturn(result);

        client.handleEvent(payload);

        ArgumentCaptor<EventSink> sinkCaptor = ArgumentCaptor.forClass(EventSink.class);
        verify(agentStateMachine).run(anyString(), anyString(), sinkCaptor.capture());
        assertThat(sinkCaptor.getValue()).isSameAs(EventSink.NOOP);
    }

    // ========== 票 114：PostureGuard 凭据家法 ==========

    @Test
    @DisplayName("非回环 + 启用 + 空 APP_ID → 拒启")
    void startRejectsNonLoopbackWithBlankAppId() {
        GatewayProperties.Feishu feishu = new GatewayProperties.Feishu(true, "", "some-secret");
        when(properties.feishu()).thenReturn(feishu);

        FeishuLongConnectionClient nonLoopbackClient = new FeishuLongConnectionClient(
                adapter, agentStateMachine, replySender, properties, "0.0.0.0");

        assertThatThrownBy(nonLoopbackClient::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHOPPILOT_IM_FEISHU_APP_ID")
                .hasMessageContaining("非回环");
    }

    @Test
    @DisplayName("非回环 + 启用 + 空 APP_SECRET → 拒启")
    void startRejectsNonLoopbackWithBlankAppSecret() {
        GatewayProperties.Feishu feishu = new GatewayProperties.Feishu(true, "some-app-id", "");
        when(properties.feishu()).thenReturn(feishu);

        FeishuLongConnectionClient nonLoopbackClient = new FeishuLongConnectionClient(
                adapter, agentStateMachine, replySender, properties, "0.0.0.0");

        assertThatThrownBy(nonLoopbackClient::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHOPPILOT_IM_FEISHU_APP_SECRET")
                .hasMessageContaining("非回环");
    }

    @Test
    @DisplayName("非回环 + 启用 + 两个凭据都空 → 拒启，消息含两个凭据名")
    void startRejectsNonLoopbackWithBothBlank() {
        GatewayProperties.Feishu feishu = new GatewayProperties.Feishu(true, "", "");
        when(properties.feishu()).thenReturn(feishu);

        FeishuLongConnectionClient nonLoopbackClient = new FeishuLongConnectionClient(
                adapter, agentStateMachine, replySender, properties, "0.0.0.0");

        assertThatThrownBy(nonLoopbackClient::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHOPPILOT_IM_FEISHU_APP_ID")
                .hasMessageContaining("SHOPPILOT_IM_FEISHU_APP_SECRET");
    }

    @Test
    @DisplayName("回环 + 启用 + 空凭据 → WARN + 不注册长连接（不抛异常）")
    void startWarnsOnLoopbackWithBlankCredentials() {
        GatewayProperties.Feishu feishu = new GatewayProperties.Feishu(true, "", "");
        when(properties.feishu()).thenReturn(feishu);

        FeishuLongConnectionClient loopbackClient = new FeishuLongConnectionClient(
                adapter, agentStateMachine, replySender, properties, "127.0.0.1");

        // 不抛异常，正常返回
        loopbackClient.start();

        // wsClient 仍为 null（未注册长连接）
        try {
            java.lang.reflect.Field field = FeishuLongConnectionClient.class.getDeclaredField("wsClient");
            field.setAccessible(true);
            assertThat(field.get(loopbackClient)).isNull();
        } catch (Exception e) {
            throw new RuntimeException("反射操作失败", e);
        }
    }

    @Test
    @DisplayName("回环 + 启用 + 凭据非空 → 正常启动（不拒启）")
    void startAllowsLoopbackWithValidCredentials() {
        GatewayProperties.Feishu feishu = new GatewayProperties.Feishu(true, "test-app-id", "test-secret");
        when(properties.feishu()).thenReturn(feishu);

        FeishuLongConnectionClient loopbackClient = new FeishuLongConnectionClient(
                adapter, agentStateMachine, replySender, properties, "127.0.0.1");

        // 不抛异常（SDK 启动可能失败，但不会抛 IllegalStateException）
        loopbackClient.start();
    }

    @Test
    @DisplayName("非回环 + 未启用 → 跳过（不检查凭据）")
    void startSkipsWhenDisabled() {
        GatewayProperties.Feishu feishu = new GatewayProperties.Feishu(false, "", "");
        when(properties.feishu()).thenReturn(feishu);

        FeishuLongConnectionClient nonLoopbackClient = new FeishuLongConnectionClient(
                adapter, agentStateMachine, replySender, properties, "0.0.0.0");

        // 不抛异常，直接返回
        nonLoopbackClient.start();
    }

    @Test
    @DisplayName("变异对照：非回环 + 空凭据 + 拒启条件改成非空才拒 → 空凭据漏进来")
    void mutationNonLoopbackEmptyCredentialsShouldNotLeak() {
        // 这个测试验证：如果 start() 里的拒启条件被改成「凭据非空才拒」，
        // 那么空凭据就会漏进来（不会抛异常），测试就会红。
        // 当前实现：空凭据 + 非回环 → 必抛 IllegalStateException
        GatewayProperties.Feishu feishu = new GatewayProperties.Feishu(true, "", "");
        when(properties.feishu()).thenReturn(feishu);

        FeishuLongConnectionClient nonLoopbackClient = new FeishuLongConnectionClient(
                adapter, agentStateMachine, replySender, properties, "0.0.0.0");

        // 如果拒启条件被破坏（改成 isNotBlank），这里不会抛异常，assertThatThrownBy 会红
        assertThatThrownBy(nonLoopbackClient::start)
                .isInstanceOf(IllegalStateException.class);
    }
}
