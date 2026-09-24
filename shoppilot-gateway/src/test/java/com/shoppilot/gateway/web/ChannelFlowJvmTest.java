package com.shoppilot.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.AgentResult;
import com.shoppilot.gateway.agent.AgentStateMachine;
import com.shoppilot.gateway.agent.FallbackReason;
import com.shoppilot.gateway.agent.FallbackService;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.channel.EmailAdapter;
import com.shoppilot.gateway.channel.EmailReceiptWriter;
import com.shoppilot.gateway.channel.WebhookAdapter;
import com.shoppilot.gateway.feedback.FeedbackService;
import com.shoppilot.gateway.identity.RequestTrace;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.ratelimit.RateLimitService;
import com.shoppilot.tool.Intent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 三渠道入站契约（ADR 0035 / 票 38）：webhook 族整段 JSON、email 落回执工单、
 * 渠道计数与无效渠道 400。渠道只是标签——身份与会话机制与 web 渠道完全共用。
 */
class ChannelFlowJvmTest {

    private static final String QUERY = "七天无理由退货怎么操作";

    private SimpleMeterRegistry registry;
    private AgentStateMachine agent;
    private EmailReceiptWriter receiptWriter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        RequestTrace.start();
        TenantContext.Identity identity = new TenantContext.Identity("T001", "C155", "conv-channel-1");
        RequestTrace.bind(identity);
        TenantContext.set(identity);
        agent = mock(AgentStateMachine.class);
        receiptWriter = mock(EmailReceiptWriter.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        RequestTrace.clear();
    }

    private MockMvc mvc() {
        RateLimitService rateLimit = mock(RateLimitService.class);
        when(rateLimit.tryAcquire(anyString(), anyString(), anyString())).thenReturn(RateLimitService.Decision.pass());
        ChatAdmission admission = new ChatAdmission(mock(CacheService.class), rateLimit, mock(FallbackService.class),
                registry);
        ChannelController controller = new ChannelController(agent, admission, mock(FeedbackService.class),
                receiptWriter, new WebhookAdapter(), new EmailAdapter());
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static AgentResult answer(String reply) {
        return new AgentResult(reply, Intent.POLICY_RETURN, "T1", CacheService.Layer.NONE, List.of("R-1"), List.of(),
                null, null, false, 3, 5, false, false, "v1.0.0", List.of(),
                AgentResult.ContextComposition.NONE);
    }

    @Test
    @DisplayName("app 渠道走 webhook：整段 JSON 回包，channel 标签与计数落位")
    void webhookAppReturnsWholeJson() throws Exception {
        when(agent.run(any(), any(), any())).thenReturn(answer("签收后七天内可以申请退货"));

        mvc().perform(post("/api/v1/support/webhook/app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUERY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("app"))
                .andExpect(jsonPath("$.streaming").value(false))
                .andExpect(jsonPath("$.answer").value("签收后七天内可以申请退货"))
                .andExpect(jsonPath("$.promptVersion").value("v1.0.0"));

        assertTrue(registry.get("shoppilot_channel_requests_total").tag("channel", "app").counter().count() == 1.0d);
    }

    @Test
    @DisplayName("无效渠道与 web/email 走 webhook 路径 → 400，不进入编排")
    void invalidChannelRejected() throws Exception {
        mvc().perform(post("/api/v1/support/webhook/web")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"" + QUERY + "\"}"))
                .andExpect(status().isBadRequest());
        mvc().perform(post("/api/v1/support/webhook/email")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"" + QUERY + "\"}"))
                .andExpect(status().isBadRequest());
        verify(agent, never()).run(any(), any(), any());
    }

    @Test
    @DisplayName("email 渠道：主题并入诉求原文，结果落回执工单作为交付形态")
    void emailWritesReceiptTicket() throws Exception {
        when(agent.run(any(), any(), any())).thenReturn(answer("退款已受理"));
        when(receiptWriter.writeReceipt(anyString(), anyString(), any())).thenReturn(Optional.of("T-EMAIL-1"));

        mvc().perform(post("/api/v1/support/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"buyer@example.com\",\"subject\":\"退款\",\"body\":\"订单SO20260901006有质量问题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("email"))
                .andExpect(jsonPath("$.receiptTicketId").value("T-EMAIL-1"));

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(receiptWriter).writeReceipt(query.capture(), anyString(), any());
        assertTrue(query.getValue().contains("【主题】退款"), "主题必须并入诉求原文：" + query.getValue());
        assertTrue(query.getValue().contains("订单SO20260901006"));
        assertTrue(registry.get("shoppilot_channel_requests_total").tag("channel", "email").counter().count() == 1.0d);
    }

    @Test
    @DisplayName("email 走降级时已有可查工单：不再叠回执单，回执号就是降级工单号")
    void emailFallbackReusesExistingTicket() throws Exception {
        AgentResult fallback = new AgentResult("稍后人工跟进", Intent.ESCALATE, "FALLBACK", CacheService.Layer.NONE,
                List.of(), List.of(), FallbackReason.TOOL_UNAVAILABLE, "T-FB-9", false, 0, 0, false, true, "v1.0.0",
                List.of(), AgentResult.ContextComposition.NONE);
        when(agent.run(any(), any(), any())).thenReturn(fallback);

        mvc().perform(post("/api/v1/support/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"buyer@example.com\",\"body\":\"查一下我的订单\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptTicketId").value("T-FB-9"))
                .andExpect(jsonPath("$.ticketId").value("T-FB-9"));

        verify(receiptWriter, never()).writeReceipt(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("限流拒绝时按渠道可查：429 回包带渠道与工单号")
    void rateLimitedCarriesChannel() throws Exception {
        RateLimitService rateLimit = mock(RateLimitService.class);
        when(rateLimit.tryAcquire(anyString(), anyString(), anyString()))
                .thenReturn(new RateLimitService.Decision(false, 1500L, "buyer"));
        ChatAdmission admission = new ChatAdmission(mock(CacheService.class), rateLimit, mock(FallbackService.class),
                registry);
        ChannelController controller = new ChannelController(agent, admission, mock(FeedbackService.class),
                receiptWriter, new WebhookAdapter(), new EmailAdapter());

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/api/v1/support/webhook/miniapp")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"" + QUERY + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.channel").value("miniapp"))
                .andExpect(jsonPath("$.fallbackReason").value("RATE_LIMITED"));

        verify(agent, never()).run(any(), any(), any());
        assertTrue(registry.get("shoppilot_channel_requests_total").tag("channel", "miniapp").counter().count() == 1.0d);
    }
}
