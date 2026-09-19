package com.shoppilot.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.AgentStateMachine;
import com.shoppilot.gateway.agent.BizMockClient;
import com.shoppilot.gateway.agent.FallbackReason;
import com.shoppilot.gateway.agent.FallbackService;
import com.shoppilot.gateway.agent.IdempotencyService;
import com.shoppilot.gateway.agent.SessionStore;
import com.shoppilot.gateway.agent.ToolDispatcher;
import com.shoppilot.gateway.cache.CacheEntry;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.cache.SingleFlight;
import com.shoppilot.gateway.cache.WriteBackPolicy;
import com.shoppilot.gateway.cache.WriteBackPool;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.RequestTrace;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.knowledge.HybridRetriever;
import com.shoppilot.gateway.knowledge.KbEpoch;
import com.shoppilot.gateway.llm.LlmGateway;
import com.shoppilot.gateway.llm.LlmTypes;
import com.shoppilot.gateway.ratelimit.RateLimitService;
import com.shoppilot.gateway.triage.TriageEngine;
import com.shoppilot.gateway.triage.TriageResult;
import com.shoppilot.tool.Intent;
import com.shoppilot.tool.ToolName;
import com.shoppilot.tool.view.ToolStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 网关主链路的 JVM 级集成缝（round16 / S2）。
 *
 * <p>这里刻意不启动完整 Spring Boot 容器：网关主链路会拉 Redis、Qdrant、ES、Ollama
 * 四个外部依赖，日常 CI 不该为了三条 smoke 把它们全搬进来。用例用真实
 * {@link ChatController}、真实 {@link AgentStateMachine} 和真实 {@link ToolDispatcher}，
 * 只把跨进程 HTTP、向量检索和模型边界替换成确定性替身。
 *
 * <p>覆盖三条行为：缓存命中不碰模型、工具循环把业务结果交回模型总结、显式转人工落可查工单。
 */
class GatewayMainPathJvmTest {

    private static final String TENANT = "T001";
    private static final String CUSTOMER = "C155";
    private static final String CONVERSATION = "conv-integration-1";
    private static final String QUERY = "七天无理由怎么退";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SimpleMeterRegistry registry;

    @BeforeEach
    void bindIdentity() {
        registry = new SimpleMeterRegistry();
        RequestTrace.start();
        TenantContext.Identity identity = new TenantContext.Identity(TENANT, CUSTOMER, CONVERSATION);
        RequestTrace.bind(identity);
        TenantContext.set(identity);
    }

    @AfterEach
    void clearIdentity() {
        TenantContext.clear();
        RequestTrace.clear();
        registry.close();
    }

    @Test
    @DisplayName("缓存命中：Controller 返回 L1 答案与引用，模型零调用")
    void cacheHitReturnsWithoutCallingTheModel() throws Exception {
        CacheService cache = mock(CacheService.class);
        CacheEntry cached = CacheEntry.of("签收后七天内可以申请退货", Intent.POLICY_RETURN, TENANT,
                CacheService.SCOPE_SHOP, 7L, List.of("POLICY-RETURN-01"), "perf-mock", QUERY);
        when(cache.lookup(eq(TENANT), eq(Intent.POLICY_RETURN), eq(QUERY), eq(7L), any()))
                .thenReturn(new CacheService.Lookup(CacheService.Layer.L1, Optional.of(cached), null,
                        QUERY, false));

        LlmGateway llm = mock(LlmGateway.class);
        MockMvc mvc = mockMvc(TriageResult.policy(Intent.POLICY_RETURN, "T1", 1.0d),
                cache, llm, mock(ToolDispatcher.class), mock(FallbackService.class));

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUERY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheLayer").value("L1"))
                .andExpect(jsonPath("$.answer").value("签收后七天内可以申请退货"))
                .andExpect(jsonPath("$.citations[0]").value("POLICY-RETURN-01"))
                .andExpect(jsonPath("$.toolUsed").value(false));

        verify(cache).recordRequest();
        verifyNoInteractions(llm);
    }

    @Test
    @DisplayName("工具循环：订单工具结果进入第二轮，最终答复由模型总结")
    void toolLoopFeedsBusinessResultBackIntoTheFinalReply() throws Exception {
        ArgumentCaptor<LlmTypes.Request> planRounds = ArgumentCaptor.forClass(LlmTypes.Request.class);
        ArgumentCaptor<LlmTypes.Request> replyRound = ArgumentCaptor.forClass(LlmTypes.Request.class);
        LlmGateway llm = mock(LlmGateway.class);
        LlmTypes.ToolCall queryOrder = new LlmTypes.ToolCall("call-order-1", ToolName.QUERY_ORDER_DETAIL.apiName(),
                Map.of("orderNo", "90001"));
        LlmTypes.Reply toolPlan = new LlmTypes.Reply("", List.of(queryOrder), 12, 3, null);
        LlmTypes.Reply finalPlan = LlmTypes.Reply.text("您的订单正在配送中");
        when(llm.complete(any())).thenReturn(toolPlan, finalPlan);
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("您的订单正在配送中"));

        BizMockClient bizMock = mock(BizMockClient.class);
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), eq(null)))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK,
                        "{\"status\":\"OK\",\"message\":\"订单已发货\"}", false));
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, mock(IdempotencyService.class));

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true),
                mock(CacheService.class), llm, dispatcher, mock(FallbackService.class));

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"我的订单90001到哪了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("ACTION_ORDER"))
                .andExpect(jsonPath("$.toolUsed").value(true))
                .andExpect(jsonPath("$.answer").value("您的订单正在配送中"));

        verify(bizMock).call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), eq(null));

        // 只断言 HTTP 响应会假绿：两次 complete 的桩按调用次序返回，就算状态机把 tool 消息丢掉，
        // 第二轮照样拿得到总结文案。所以这里直接查请求体，钉死"工具结果回填"这件事本身。
        verify(llm, times(2)).complete(planRounds.capture());
        verify(llm).stream(replyRound.capture(), any());

        List<LlmTypes.Message> secondRound = planRounds.getAllValues().get(1).messages();
        LlmTypes.Message toolResult = secondRound.stream()
                .filter(message -> "tool".equals(message.role()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("第二轮规划请求没有带上 tool 消息：" + secondRound));
        assertEquals("call-order-1", toolResult.toolCallId(), "tool 消息必须指回模型发起的那次调用");
        assertTrue(toolResult.content().contains("订单已发货"),
                "tool 消息必须带业务结果原文，实际=" + toolResult.content());
        assertTrue(secondRound.stream()
                        .anyMatch(message -> "assistant".equals(message.role()) && !message.toolCalls().isEmpty()),
                "第二轮规划请求必须同时带上发起工具调用的那条 assistant 消息");
        assertTrue(replyRound.getValue().messages().stream()
                        .anyMatch(message -> "tool".equals(message.role())
                                && message.content().contains("订单已发货")),
                "收尾那轮必须看得到工具结果");
    }

    @Test
    @DisplayName("显式转人工：fallback 原因与工单号进入响应，模型不参与")
    void explicitEscalationReturnsAQueryableTicket() throws Exception {
        FallbackService fallback = mock(FallbackService.class);
        when(fallback.escalate(eq(FallbackReason.USER_REQUESTED), eq("我要找人工"), eq(null)))
                .thenReturn(Optional.of("T-900"));
        LlmGateway llm = mock(LlmGateway.class);

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ESCALATE, "T1", false),
                mock(CacheService.class), llm, mock(ToolDispatcher.class), fallback);

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"我要找人工\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackReason").value("USER_REQUESTED"))
                .andExpect(jsonPath("$.ticketId").value("T-900"))
                .andExpect(jsonPath("$.answer").value(containsString("工单号 T-900")));

        verify(fallback).escalate(FallbackReason.USER_REQUESTED, "我要找人工", null);
        verifyNoInteractions(llm);
    }

    private MockMvc mockMvc(TriageResult triageResult, CacheService cache, LlmGateway llm,
                            ToolDispatcher dispatcher, FallbackService fallback) {
        TriageEngine triage = mock(TriageEngine.class);
        when(triage.triage(anyString())).thenReturn(new TriageEngine.Outcome(triageResult, null));

        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);

        HybridRetriever retriever = mock(HybridRetriever.class);
        when(retriever.retrieve(anyString(), anyString(), any()))
                .thenReturn(HybridRetriever.Result.unavailable(7L));

        WriteBackPolicy writeBackPolicy = mock(WriteBackPolicy.class);
        when(writeBackPolicy.evaluate(any())).thenReturn(new WriteBackPolicy.Verdict(false, "integration-test"));

        SessionStore sessionStore = mock(SessionStore.class);
        when(sessionStore.load(anyString(), anyString(), anyString()))
                .thenReturn(SessionStore.Session.empty(CONVERSATION));
        when(sessionStore.appendTurn(any(), anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(0));

        AgentStateMachine machine = new AgentStateMachine(triage, cache, mock(SingleFlight.class),
                writeBackPolicy, epoch, retriever, llm, dispatcher, sessionStore, fallback,
                properties(), mock(WriteBackPool.class), registry);

        RateLimitService rateLimit = mock(RateLimitService.class);
        when(rateLimit.tryAcquire(any(), any(), any())).thenReturn(RateLimitService.Decision.pass());

        ChatController controller = new ChatController(machine, cache, rateLimit, MAPPER, registry, fallback);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static GatewayProperties properties() {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.agent()).thenReturn(new GatewayProperties.Agent(2, 2,
                Duration.ofMinutes(30), 4));
        when(properties.llm()).thenReturn(new GatewayProperties.Llm("perf", "http://127.0.0.1:1",
                "unused", "perf-mock", 0.0d, Duration.ofSeconds(1), Duration.ofSeconds(1),
                0L, null, null, null, null));
        when(properties.cache()).thenReturn(new GatewayProperties.Cache(true, Duration.ofHours(1),
                0.95d, Duration.ofSeconds(60), Duration.ofSeconds(2), true));
        return properties;
    }
}
