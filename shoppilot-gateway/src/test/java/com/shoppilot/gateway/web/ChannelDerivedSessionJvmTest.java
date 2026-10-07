package com.shoppilot.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.AgentResult;
import com.shoppilot.gateway.agent.AgentStateMachine;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.channel.Channel;
import com.shoppilot.gateway.channel.ChannelAdapter;
import com.shoppilot.gateway.channel.ChannelContext;
import com.shoppilot.gateway.channel.EmailAdapter;
import com.shoppilot.gateway.channel.EmailReceiptWriter;
import com.shoppilot.gateway.channel.WebhookAdapter;
import com.shoppilot.gateway.channel.WebSseAdapter;
import com.shoppilot.gateway.feedback.FeedbackService;
import com.shoppilot.gateway.identity.AuthFilter;
import com.shoppilot.gateway.identity.JwtService;
import com.shoppilot.gateway.identity.RequestTrace;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.ratelimit.RateLimitService;
import com.shoppilot.tool.Intent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 票 96（round31 / ADR 0065 决策 3）的两格派生契约：适配器从平台事件派生会话 id 与幂等 token。
 * 会话 id 让「不传 X-Conversation-Id」不再断追问；clientToken 让幂等不再靠参数猜。
 * 渠道标签进派生键（防两个平台共用一个 session 键）；显式给出的值恒优先于派生值。
 */
class ChannelDerivedSessionJvmTest {

    private static final String QUERY = "七天无理由退货怎么操作";

    private final List<String> seenConversations = new ArrayList<>();
    private final List<String> seenIdempotencyTokens = new ArrayList<>();
    private AgentStateMachine agent;

    @BeforeEach
    void setUp() {
        RequestTrace.start();
        TenantContext.set(new TenantContext.Identity("T001", "C155", "conv-from-authfilter"));
        seenConversations.clear();
        seenIdempotencyTokens.clear();
        agent = mock(AgentStateMachine.class);
        when(agent.run(any(), any(), any())).thenAnswer(invocation -> {
            // agent.run 从 TenantContext 读会话坐标——捕获它看到的值就是「传下去了」的证据
            seenConversations.add(TenantContext.current().conversationId());
            seenIdempotencyTokens.add(invocation.getArgument(1));
            return answer();
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        ChannelContext.clear();
        RequestTrace.clear();
    }

    private MockMvc mvc() {
        RateLimitService rateLimit = mock(RateLimitService.class);
        when(rateLimit.tryAcquire(anyString(), anyString(), anyString())).thenReturn(RateLimitService.Decision.pass());
        ChatAdmission admission = new ChatAdmission(mock(CacheService.class), rateLimit,
                mock(com.shoppilot.gateway.agent.FallbackService.class), new SimpleMeterRegistry());
        ChannelController controller = new ChannelController(agent, admission, mock(FeedbackService.class),
                mock(EmailReceiptWriter.class), new WebhookAdapter(), new EmailAdapter());
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static AgentResult answer() {
        return new AgentResult("签收后七天内可以申请退货", Intent.POLICY_RETURN, "T1", CacheService.Layer.NONE,
                List.of("R-1"), List.of(), null, null, false, 3, 5, false, false, "v1.0.0", List.of(),
                AgentResult.ContextComposition.NONE);
    }

    private MvcResult postWebhook(String channel, String json) throws Exception {
        return mvc().perform(post("/api/v1/support/webhook/" + channel)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn();
    }

    @Test
    @DisplayName("不传会话头：两次 webhook 调用落在同一段派生会话（现状是每条消息一段新会话）")
    void derivedSessionIdSameAcrossCallsWithoutHeader() throws Exception {
        String payload = "{\"query\":\"" + QUERY + "\",\"messageId\":\"m-1\"}";

        MvcResult first = postWebhook("app", payload);
        MvcResult second = postWebhook("app", payload);

        assertThat(seenConversations).containsExactly("app:chat:C155", "app:chat:C155");
        String returned = new ObjectMapper().readTree(first.getResponse().getContentAsString())
                .path("conversationId").asText();
        assertThat(returned).isEqualTo("app:chat:C155");
        assertThat(new ObjectMapper().readTree(second.getResponse().getContentAsString())
                .path("conversationId").asText()).isEqualTo("app:chat:C155");
    }

    @Test
    @DisplayName("同一个人、不同渠道派生出不同会话 id（防两个平台共用一个 session 键）")
    void derivedSessionIdDiffersAcrossChannelsForSameBuyer() throws Exception {
        String payload = "{\"query\":\"" + QUERY + "\",\"messageId\":\"m-1\"}";

        postWebhook("app", payload);
        postWebhook("miniapp", payload);
        mvc().perform(post("/api/v1/support/email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"buyer@example.com\",\"body\":\"" + QUERY + "\",\"messageId\":\"m-1\"}"))
                .andExpect(status().isOk());

        assertThat(seenConversations).containsExactly("app:chat:C155", "miniapp:chat:C155", "email:chat:C155");
    }

    @Test
    @DisplayName("同渠道、不同 sessionId 是不同聊天 → 不同会话 id（派生必须含聊天维度）")
    void sameChannelDifferentSessionsDiffer() throws Exception {
        postWebhook("app", "{\"query\":\"" + QUERY + "\",\"sessionId\":\"s-1\"}");
        postWebhook("app", "{\"query\":\"" + QUERY + "\",\"sessionId\":\"s-2\"}");

        assertThat(seenConversations).containsExactly("app:chat:s-1", "app:chat:s-2");
    }

    @Test
    @DisplayName("不同 message id → 传给幂等层的 clientToken 不同：两次都执行，不是第二次判重放")
    void differentMessageIdsGiveDifferentIdempotencyTokens() throws Exception {
        postWebhook("app", "{\"query\":\"" + QUERY + "\",\"messageId\":\"m-1\"}");
        postWebhook("app", "{\"query\":\"" + QUERY + "\",\"messageId\":\"m-2\"}");

        assertThat(seenIdempotencyTokens).containsExactly("app:msg:m-1", "app:msg:m-2");
    }

    @Test
    @DisplayName("相同 message id → 相同 clientToken：幂等层同键仍判重放（幂等没被绕过）")
    void sameMessageIdGivesSameIdempotencyToken() throws Exception {
        String payload = "{\"query\":\"" + QUERY + "\",\"messageId\":\"m-1\"}";

        postWebhook("app", payload);
        postWebhook("app", payload);

        assertThat(seenIdempotencyTokens).containsExactly("app:msg:m-1", "app:msg:m-1");
    }

    @Test
    @DisplayName("显式 idempotencyToken 优先于派生 clientToken（显式 > 派生，同一哲学）")
    void explicitIdempotencyTokenWinsOverDerived() throws Exception {
        postWebhook("app", "{\"query\":\"" + QUERY + "\",\"idempotencyToken\":\"explicit-1\",\"messageId\":\"m-1\"}");

        assertThat(seenIdempotencyTokens).containsExactly("explicit-1");
    }

    @Test
    @DisplayName("请求头显式给出的 X-Conversation-Id 优先于派生会话 id")
    void explicitHeaderWinsOverDerivedConversation() throws Exception {
        // 头是 AuthFilter 解析进 TenantContext 的——这条用例挂上真过滤器，走真实链路形态
        JwtService jwt = new JwtService("unit-test-secret-value-at-least-32-bytes!!");
        MockMvc withAuth = MockMvcBuilders.standaloneSetup(controller())
                .addFilters(new AuthFilter(jwt, new ApiErrorWriter(new ObjectMapper())))
                .build();

        withAuth.perform(post("/api/v1/support/webhook/app")
                        .header("Authorization", "Bearer " + jwt.issue("T001", "C155"))
                        .header("X-Conversation-Id", "conv-header")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUERY + "\",\"sessionId\":\"s-1\"}"))
                .andExpect(status().isOk());

        assertThat(seenConversations).containsExactly("conv-header");
    }

    private ChannelController controller() {
        RateLimitService rateLimit = mock(RateLimitService.class);
        when(rateLimit.tryAcquire(anyString(), anyString(), anyString())).thenReturn(RateLimitService.Decision.pass());
        ChatAdmission admission = new ChatAdmission(mock(CacheService.class), rateLimit,
                mock(com.shoppilot.gateway.agent.FallbackService.class), new SimpleMeterRegistry());
        return new ChannelController(agent, admission, mock(FeedbackService.class),
                mock(EmailReceiptWriter.class), new WebhookAdapter(), new EmailAdapter());
    }

    @Test
    @DisplayName("messageId 过长 → 400（与 contact 同一长度家法，超长键进不了下游）")
    void oversizedMessageIdRejected() throws Exception {
        mvc().perform(post("/api/v1/support/webhook/app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUERY + "\",\"messageId\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());

        assertThat(seenConversations).isEmpty();
    }

    @Test
    @DisplayName("web 渠道不派生两格：行为零变更（会话走自己的头机制，token 由调用方给）")
    void webAdapterStaysNull() {
        Map<String, Object> payload = Map.of("query", QUERY);
        ChannelAdapter.NormalizedChat normalized = new WebSseAdapter().normalize(payload);

        assertThat(normalized.conversationId()).isNull();
        assertThat(normalized.clientToken()).isNull();
        assertThat(new WebSseAdapter().channel()).isEqualTo(Channel.WEB);
    }

    @Test
    @DisplayName("派生辅助本身：渠道标签与聊天维度都进键，缺一不可")
    void derivationHelpersIncludeChannelAndChat() {
        assertThat(ChannelAdapter.deriveConversationId(Channel.APP, "C155")).isEqualTo("app:chat:C155");
        assertThat(ChannelAdapter.deriveConversationId(Channel.MINIAPP, "C155")).isEqualTo("miniapp:chat:C155");
        assertThat(ChannelAdapter.deriveConversationId(Channel.APP, "s-1")).isEqualTo("app:chat:s-1");
        assertThat(ChannelAdapter.deriveClientToken(Channel.APP, "m-1")).isEqualTo("app:msg:m-1");
        assertThat(ChannelAdapter.deriveClientToken(Channel.EMAIL, "m-1")).isEqualTo("email:msg:m-1");
    }
}
