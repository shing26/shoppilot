package com.shoppilot.gateway.web;

import com.shoppilot.gateway.agent.AgentResult;
import com.shoppilot.gateway.agent.AgentStateMachine;
import com.shoppilot.gateway.agent.EventSink;
import com.shoppilot.gateway.channel.ChannelConversationStore;
import com.shoppilot.gateway.channel.WidgetKeyRegistry;
import com.shoppilot.gateway.ratelimit.RateLimitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 票 184：Widget key 换身份，消息进收件箱，复用 Agent。 */
class WidgetControllerTest {

    private final WidgetKeyRegistry keys = mock(WidgetKeyRegistry.class);
    private final AgentStateMachine agent = mock(AgentStateMachine.class);
    private final ChannelConversationStore store = mock(ChannelConversationStore.class);
    private final ChatAdmission admission = mock(ChatAdmission.class);
    private final WidgetController controller = new WidgetController(keys, agent, store, admission);

    @Test
    @DisplayName("错误 widget key 返回 401")
    void rejectsUnknownKey() {
        when(keys.resolve("bad")).thenReturn(null);

        ResponseEntity<?> response = controller.chat("bad",
                new WidgetController.WidgetRequest("v1", "你好"), new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("合法 key 调 Agent 并写回收件箱")
    void runsAgentAndRecordsInbox() {
        when(keys.resolve("demo")).thenReturn(new WidgetKeyRegistry.WidgetIdentity("T001", "C001"));
        when(admission.check(any(), eq("你好")))
                .thenReturn(new ChatAdmission.Guard(new RateLimitService.Decision(true, 0, null), null));
        AgentResult result = mock(AgentResult.class);
        when(result.answer()).thenReturn("您好");
        when(agent.run("你好", null, EventSink.NOOP)).thenReturn(result);

        ResponseEntity<?> response = controller.chat("demo",
                new WidgetController.WidgetRequest("visitor_1", "你好"), new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((WidgetController.WidgetReply) response.getBody()).answer()).isEqualTo("您好");
        verify(store).recordInbound("T001", "C001", "web:visitor_1", "web", "你好");
        verify(store).recordBotReply("T001", "web:visitor_1", "您好");
    }

    @Test
    @DisplayName("AGENT 状态不自动回复，只提示人工已接入")
    void agentModeSkipsAutoReply() {
        when(keys.resolve("demo")).thenReturn(new WidgetKeyRegistry.WidgetIdentity("T001", "C001"));
        when(admission.check(any(), eq("在吗")))
                .thenReturn(new ChatAdmission.Guard(new RateLimitService.Decision(true, 0, null), null));
        when(store.isAgent("T001", "web:v2")).thenReturn(true);

        ResponseEntity<?> response = controller.chat("demo",
                new WidgetController.WidgetRequest("v2", "在吗"), new MockHttpServletRequest());

        assertThat(((WidgetController.WidgetReply) response.getBody()).status()).isEqualTo("AGENT");
        verify(agent, org.mockito.Mockito.never()).run(any(), any(), any());
    }
}
