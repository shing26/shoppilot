package com.shoppilot.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.AgentStateMachine;
import com.shoppilot.gateway.agent.BizMockClient;
import com.shoppilot.gateway.agent.FallbackReason;
import com.shoppilot.gateway.agent.FallbackService;
import com.shoppilot.gateway.agent.PromptCatalog;
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
import com.shoppilot.gateway.channel.ChannelContext;
import com.shoppilot.gateway.sentiment.Emotion;
import com.shoppilot.gateway.sentiment.SentimentGate;
import com.shoppilot.gateway.style.StyleService;
import com.shoppilot.gateway.feedback.FeedbackService;
import com.shoppilot.gateway.triage.T0RuleLayer;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    @DisplayName("回归：local 档命中路径必须零模型调用——情绪门第二层在 INTAKE 会给每条未命中词表的请求各打一次分类")
    void cacheHitPathStaysZeroModelCallsInLocalMode() throws Exception {
        CacheService cache = mock(CacheService.class);
        CacheEntry cached = CacheEntry.of("签收后七天内可以申请退货", Intent.POLICY_RETURN, TENANT,
                CacheService.SCOPE_SHOP, 7L, List.of("POLICY-RETURN-01"), "perf-mock", QUERY);
        when(cache.lookup(eq(TENANT), eq(Intent.POLICY_RETURN), eq(QUERY), eq(7L), any()))
                .thenReturn(new CacheService.Lookup(CacheService.Layer.L1, Optional.of(cached), null,
                        QUERY, false));

        // local 档（演示与 22 步活体验收的口径，ADR 0043）：这条平静问句不命中情绪门词表，若第二层
        // 在 local 也开着就会在 INTAKE 交给小模型分类。而 INTAKE 在 CACHE_READ 之前——命中路径的
        // 「零模型调用」就是这一步丢掉的（round17 活体验收登记 F1：47/50，94%）。dev 档保留第二层。
        LlmGateway gateLlm = mock(LlmGateway.class);
        when(gateLlm.mode()).thenReturn("local");
        when(gateLlm.complete(any()))
                .thenReturn(LlmTypes.Reply.text("{\"emotion\":\"CALM\",\"confidence\":0.9}"));

        MockMvc mvc = mockMvc(TriageResult.policy(Intent.POLICY_RETURN, "T1", 1.0d),
                cache, mock(LlmGateway.class), mock(ToolDispatcher.class), mock(FallbackService.class),
                new SentimentGate(gateLlm, new ObjectMapper(), registry,
                        new PromptCatalog("prompts/sentiment-classifier/")));

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUERY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheLayer").value("L1"));

        // 断的是「有没有打模型调用」，不是 verifyNoInteractions——后者会把门读一次 mode() 也算成交互，
        // 那样这条断言在修好后仍然红，等于把判据钉在无关的读上。
        verify(gateLlm, never()).complete(any());
        verify(gateLlm, never()).stream(any(), any());
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
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, mock(IdempotencyService.class), registry);

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

    @Test
    @DisplayName("回归：情绪门第二层判 URGENT 时，显式转人工仍必须落 USER_REQUESTED（ADR 0017 不被 ADR 0034 覆盖）")
    void explicitEscalationSurvivesAnUrgentSentimentVerdict() throws Exception {
        FallbackService fallback = mock(FallbackService.class);
        when(fallback.escalate(eq(FallbackReason.USER_REQUESTED), eq("转人工"), eq(null)))
                .thenReturn(Optional.of("T-901"));

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ESCALATE, "T1", false),
                mock(CacheService.class), mock(LlmGateway.class), mock(ToolDispatcher.class), fallback,
                urgentGate());

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"转人工\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackReason").value("USER_REQUESTED"));
    }

    @Test
    @DisplayName("轮次上限：预算检查后模型仍要工具 → 超限 FALLBACK 落工单，只有两次真实派发（票 41）")
    void toolRoundsExhaustedFallsBackWithTicket() throws Exception {
        LlmGateway llm = mock(LlmGateway.class);
        LlmTypes.ToolCall queryOrder = new LlmTypes.ToolCall("call-round-1", ToolName.QUERY_ORDER_DETAIL.apiName(),
                Map.of("orderNo", "90001"));
        LlmTypes.Reply alwaysWantsTool = new LlmTypes.Reply("", List.of(queryOrder), 12, 3, null);
        when(llm.complete(any())).thenReturn(alwaysWantsTool, alwaysWantsTool, alwaysWantsTool);

        BizMockClient bizMock = mock(BizMockClient.class);
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), eq(null)))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK,
                        "{\"status\":\"OK\",\"message\":\"订单已发货\"}", false));
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, mock(IdempotencyService.class), registry);

        FallbackService fallback = mock(FallbackService.class);
        when(fallback.escalate(eq(FallbackReason.TOOL_ROUNDS_EXHAUSTED), anyString(), anyString()))
                .thenReturn(Optional.of("T-801"));

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true),
                mock(CacheService.class), llm, dispatcher, fallback);

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"我的订单90001到哪了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackReason").value("TOOL_ROUNDS_EXHAUSTED"))
                .andExpect(jsonPath("$.ticketId").value("T-801"))
                .andExpect(jsonPath("$.answer").value(containsString("工单号 T-801")));

        // 两次工具轮真实派发；第三次 complete 是预算检查，模型仍要工具即超限，不再派发、不再流式总结
        verify(bizMock, times(2)).call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), eq(null));
        verify(llm, times(3)).complete(any());
        verify(llm, never()).stream(any(), any());
        assertEquals(1.0d, registry.get("shoppilot_tool_round_exhausted_total").counter().count());
    }

    @Test
    @DisplayName("预算检查后模型不再要工具：两轮链照常出答案，不落工单（ADR 0008「2 轮覆盖真实链式调用」）")
    void budgetCheckWithoutFurtherToolNeedAnswersFromThePlan() throws Exception {
        LlmGateway llm = mock(LlmGateway.class);
        LlmTypes.ToolCall queryOrder = new LlmTypes.ToolCall("call-chain-1", ToolName.QUERY_ORDER_DETAIL.apiName(),
                Map.of("orderNo", "90001"));
        LlmTypes.Reply toolPlan = new LlmTypes.Reply("", List.of(queryOrder), 12, 3, null);
        when(llm.complete(any())).thenReturn(toolPlan, toolPlan,
                LlmTypes.Reply.text("您的订单已发货，物流正在配送途中"));

        BizMockClient bizMock = mock(BizMockClient.class);
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), eq(null)))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK,
                        "{\"status\":\"OK\",\"message\":\"订单已发货\"}", false));
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, mock(IdempotencyService.class), registry);

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true),
                mock(CacheService.class), llm, dispatcher, mock(FallbackService.class));

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"我的订单90001到哪了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("您的订单已发货，物流正在配送途中"))
                .andExpect(jsonPath("$.toolUsed").value(true))
                .andExpect(jsonPath("$.fallbackReason").doesNotExist());

        verify(bizMock, times(2)).call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), eq(null));
        // 预算检查那轮的正文就是最终答案：不再额外打一次流式总结
        verify(llm, times(3)).complete(any());
        verify(llm, never()).stream(any(), any());
        assertEquals(0.0d, registry.get("shoppilot_tool_round_exhausted_total").counter().count());
    }

    @Test
    @DisplayName("写动作轮次内没办成：预算用尽直接 FALLBACK，不给口头承诺收尾留门（票 41 守卫）")
    void writePendingAtBudgetExhaustionFallsBackWithoutAnotherPlanCall() throws Exception {
        LlmGateway llm = mock(LlmGateway.class);
        LlmTypes.ToolCall queryOrder = new LlmTypes.ToolCall("call-refund-1", ToolName.QUERY_ORDER_DETAIL.apiName(),
                Map.of("orderNo", "90001"));
        LlmTypes.Reply readToolPlan = new LlmTypes.Reply("", List.of(queryOrder), 12, 3, null);
        when(llm.complete(any())).thenReturn(readToolPlan, readToolPlan);

        BizMockClient bizMock = mock(BizMockClient.class);
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), eq(null)))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK,
                        "{\"status\":\"OK\",\"message\":\"订单已发货\"}", false));
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, mock(IdempotencyService.class), registry);

        FallbackService fallback = mock(FallbackService.class);
        when(fallback.escalate(eq(FallbackReason.TOOL_ROUNDS_EXHAUSTED), anyString(), anyString()))
                .thenReturn(Optional.of("T-802"));

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ACTION_REFUND, "T1", true),
                mock(CacheService.class), llm, dispatcher, fallback);

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"订单90001申请退款\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackReason").value("TOOL_ROUNDS_EXHAUSTED"))
                .andExpect(jsonPath("$.ticketId").value("T-802"));

        // 两轮都花在读工具上、applyRefund 没执行：直接转人工，连预算检查那次规划调用都不该有
        verify(bizMock, times(2)).call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), eq(null));
        verify(llm, times(2)).complete(any());
        verify(llm, never()).stream(any(), any());
    }

    @Test
    @DisplayName("一次返回多个 toolCalls：只派发并只记录第一个，转录保持协议配对（票 41 防御）")
    void multipleToolCallsInOneReplyDispatchOnlyTheFirst() throws Exception {
        ArgumentCaptor<LlmTypes.Request> planRounds = ArgumentCaptor.forClass(LlmTypes.Request.class);
        LlmGateway llm = mock(LlmGateway.class);
        LlmTypes.ToolCall first = new LlmTypes.ToolCall("call-multi-1", ToolName.QUERY_ORDER_DETAIL.apiName(),
                Map.of("orderNo", "90001"));
        LlmTypes.ToolCall second = new LlmTypes.ToolCall("call-multi-2", ToolName.QUERY_ORDER_DETAIL.apiName(),
                Map.of("orderNo", "90002"));
        LlmTypes.Reply greedyPlan = new LlmTypes.Reply("", List.of(first, second), 12, 3, null);
        when(llm.complete(any())).thenReturn(greedyPlan, LlmTypes.Reply.text("您的订单正在配送中"));
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("您的订单正在配送中"));

        BizMockClient bizMock = mock(BizMockClient.class);
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), eq(null)))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK,
                        "{\"status\":\"OK\",\"message\":\"订单已发货\"}", false));
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, mock(IdempotencyService.class), registry);

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true),
                mock(CacheService.class), llm, dispatcher, mock(FallbackService.class));

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"我的订单90001到哪了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("您的订单正在配送中"));

        // 只派发第一个：第二个调用不执行、也不出现在转录里冒充已执行
        verify(bizMock, times(1)).call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), eq(null));
        verify(llm, times(2)).complete(planRounds.capture());
        List<LlmTypes.Message> secondRound = planRounds.getAllValues().get(1).messages();
        LlmTypes.Message assistant = secondRound.stream()
                .filter(message -> "assistant".equals(message.role()) && !message.toolCalls().isEmpty())
                .findFirst()
                .orElseThrow(() -> new AssertionError("第二轮规划请求缺少发起调用的 assistant 消息"));
        assertEquals(1, assistant.toolCalls().size(), "assistant 转录只允许带被派发的那一个 tool_call");
        assertEquals("call-multi-1", assistant.toolCalls().get(0).id());
        assertEquals(1, secondRound.stream().filter(message -> "tool".equals(message.role())).count(),
                "tool 响应必须与 assistant 的 tool_call 一一配对");
        assertEquals(1.0d, registry.get("shoppilot_llm_multi_tool_calls_total").counter().count());
    }

    @Test
    @DisplayName("情绪门：词典命中的愤怒买家在 TRIAGE 之前落 EMOTION_ESCALATION 高优工单（票 36）")
    void angryQueryEscalatesBeforeTriage() throws Exception {
        FallbackService fallback = mock(FallbackService.class);
        when(fallback.escalate(eq(FallbackReason.EMOTION_ESCALATION), anyString(), anyString(), eq("high")))
                .thenReturn(Optional.of("T-901"));
        LlmGateway llm = mock(LlmGateway.class);

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ACTION_REFUND, "T1", true),
                mock(CacheService.class), llm, mock(ToolDispatcher.class), fallback);

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"你们就是骗子！退款拖了半个月，我要投诉到底\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackReason").value("EMOTION_ESCALATION"))
                .andExpect(jsonPath("$.ticketId").value("T-901"))
                .andExpect(jsonPath("$.promptVersion").value("v1.0.0"));

        // 情绪升级发生在 TRIAGE 之前：判定不出意图、不进缓存、答案话术先安抚
        verify(fallback).escalate(eq(FallbackReason.EMOTION_ESCALATION), anyString(), anyString(), eq("high"));
        verify(llm, never()).complete(any());
        verify(llm, never()).stream(any(), any());
    }

    @Test
    @DisplayName("反馈端点：DOWN 回传带会话线索的 ack，非法 verdict 400（票 37）")
    void feedbackEndpointValidatesAndDelegates() throws Exception {
        FeedbackService feedbackService = mock(FeedbackService.class);
        when(feedbackService.recordExplicit(eq("conv-integration-1"), eq("DOWN"), any()))
                .thenReturn(new FeedbackService.Ack("FB-9", true, "T-901", List.of("POLICY-RETURN-01")));
        TriageEngine triage = mock(TriageEngine.class);
        when(triage.triage(anyString())).thenReturn(new TriageEngine.Outcome(
                TriageResult.policy(Intent.POLICY_RETURN, "T1", 1.0d), null));
        CacheService cache = mock(CacheService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        when(rateLimit.tryAcquire(any(), any(), any())).thenReturn(RateLimitService.Decision.pass());
        FallbackService fallback = mock(FallbackService.class);
        ChatController controller = new ChatController(mock(AgentStateMachine.class), MAPPER, registry,
                new PromptCatalog(), feedbackService,
                new ChatAdmission(cache, rateLimit, fallback, registry));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/v1/support/chat/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv-integration-1\",\"verdict\":\"DOWN\",\"reason\":\"答案不对\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedbackId").value("FB-9"))
                .andExpect(jsonPath("$.reviewQueued").value(true))
                .andExpect(jsonPath("$.ticketId").value("T-901"));

        mvc.perform(post("/api/v1/support/chat/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv-integration-1\",\"verdict\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("风格注入：app + CALM → CONCISE 档，注入段拼在版本化基座之后同一次模型调用发出（风格票）")
    void styleInjectionRidesTheSameModelCall() throws Exception {
        ArgumentCaptor<LlmTypes.Request> planRound = ArgumentCaptor.forClass(LlmTypes.Request.class);
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.complete(any())).thenReturn(LlmTypes.Reply.text("订单已发货，请留意短信通知"));

        SentimentGate calmGate = mock(SentimentGate.class);
        when(calmGate.evaluate(any())).thenReturn(
                new SentimentGate.Verdict(com.shoppilot.gateway.sentiment.Emotion.CALM, 0.9d, false, "llm"));
        TriageEngine triage = mock(TriageEngine.class);
        when(triage.triage(anyString())).thenReturn(new TriageEngine.Outcome(
                TriageResult.dynamic(Intent.POLICY_RETURN, "T1", false), null));
        WriteBackPolicy policy = mock(WriteBackPolicy.class);
        when(policy.evaluate(any())).thenReturn(new WriteBackPolicy.Verdict(false, "style-test"));
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);
        HybridRetriever retriever = mock(HybridRetriever.class);
        when(retriever.retrieve(anyString(), anyString(), any()))
                .thenReturn(HybridRetriever.Result.unavailable(7L));
        SessionStore store = mock(SessionStore.class);
        when(store.load(anyString(), anyString(), anyString())).thenReturn(SessionStore.Session.empty(CONVERSATION));
        when(store.appendTurn(any(), anyString(), anyString())).thenAnswer(call -> call.getArgument(0));

        AgentStateMachine machine = new AgentStateMachine(triage, mock(CacheService.class),
                mock(SingleFlight.class), policy, epoch, retriever, llm, mock(ToolDispatcher.class), store,
                mock(FallbackService.class), properties(), mock(WriteBackPool.class), new PromptCatalog(),
                calmGate, new StyleService(), registry);
        RateLimitService rateLimit = mock(RateLimitService.class);
        when(rateLimit.tryAcquire(any(), any(), any())).thenReturn(RateLimitService.Decision.pass());
        ChatAdmission admission = new ChatAdmission(mock(CacheService.class), rateLimit,
                mock(FallbackService.class), registry);
        // app 渠道只能从 webhook 端点进入（/chat 按设计恒为 web 渠道），走它验证三元组里的渠道维度
        ChannelController controller = new ChannelController(machine, admission, mock(FeedbackService.class),
                mock(com.shoppilot.gateway.channel.EmailReceiptWriter.class),
                new com.shoppilot.gateway.channel.WebhookAdapter(),
                new com.shoppilot.gateway.channel.EmailAdapter());

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/api/v1/support/webhook/app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"七天无理由怎么退\"}"))
                .andExpect(status().isOk());

        verify(llm).complete(planRound.capture());
        String system = planRound.getValue().messages().get(0).content();
        assertTrue(system.startsWith(new PromptCatalog().systemPrompt()), "基座在前");
        assertTrue(system.endsWith(new StyleService().injection(StyleService.Tier.CONCISE)),
                "CONCISE 注入段拼在基座之后，实际尾部="
                        + system.substring(Math.max(0, system.length() - 60)));
        assertEquals(1.0d, registry.get("shoppilot_style_applied_total")
                .tag("style", "CONCISE").counter().count());
    }

    @Test
    @DisplayName("计划记录：两步链在响应里带两条 PlanStep（工具名/状态/参数/耗时），ADR 0036 执行语义不变（票 48）")
    void planStepsAreReportedInExecutionOrder() throws Exception {
        LlmGateway llm = mock(LlmGateway.class);
        LlmTypes.ToolCall queryOrder = new LlmTypes.ToolCall("call-plan-1", ToolName.QUERY_ORDER_DETAIL.apiName(),
                Map.of("orderNo", "90001"));
        LlmTypes.Reply toolPlan = new LlmTypes.Reply("", List.of(queryOrder), 12, 3, null);
        when(llm.complete(any())).thenReturn(toolPlan, toolPlan, LlmTypes.Reply.text("您的订单已发货"));

        BizMockClient bizMock = mock(BizMockClient.class);
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), eq(null)))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK,
                        "{\"status\":\"OK\",\"message\":\"订单已发货\"}", false));
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, mock(IdempotencyService.class), registry);

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true),
                mock(CacheService.class), llm, dispatcher, mock(FallbackService.class));

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"我的订单90001到哪了\"}"))
                .andExpect(status().isOk())
                // 两步链＝两次真实派发，与 ADR 0036「有序步骤 ≤ 2」是同一条边界
                .andExpect(jsonPath("$.plan.length()").value(2))
                .andExpect(jsonPath("$.plan[0].tool").value("queryOrderDetail"))
                .andExpect(jsonPath("$.plan[0].status").value("OK"))
                .andExpect(jsonPath("$.plan[0].arguments.orderNo").value("90001"))
                .andExpect(jsonPath("$.plan[0].latencyMillis").isNumber())
                .andExpect(jsonPath("$.plan[1].tool").value("queryOrderDetail"));
    }

    @Test
    @DisplayName("纯政策回答不带计划：plan 是空数组而不是 null（票 48）")
    void policyAnswerReportsEmptyPlanArray() throws Exception {
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.complete(any())).thenReturn(LlmTypes.Reply.text("七天无理由，自签收次日起算"));

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.POLICY_RETURN, "T1", true),
                mock(CacheService.class), llm, mock(ToolDispatcher.class), mock(FallbackService.class));

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"七天无理由怎么算\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").isArray())
                .andExpect(jsonPath("$.plan.length()").value(0))
                .andExpect(jsonPath("$.context").exists());
    }

    @Test
    @DisplayName("上下文组成：context.ruleIds 与 citations 同源同序，历史轮数按实际注入的用户轮计（票 49）")
    void contextCompositionMirrorsCitationsAndHistory() throws Exception {
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.complete(any())).thenReturn(LlmTypes.Reply.text("七天无理由，自签收次日起算"));

        List<SessionStore.Turn> turns = List.of(
                new SessionStore.Turn("user", "上次那个单子"),
                new SessionStore.Turn("assistant", "好的"),
                new SessionStore.Turn("user", "还是想问退货"));
        SessionStore store = mock(SessionStore.class);
        when(store.load(anyString(), anyString(), anyString()))
                .thenReturn(new SessionStore.Session(CONVERSATION, turns, null, new LinkedHashMap<>(), 0));
        when(store.appendTurn(any(), anyString(), anyString())).thenAnswer(call -> call.getArgument(0));

        HybridRetriever.Result retrieved = new HybridRetriever.Result(List.of(
                new HybridRetriever.Retrieved("return-01-7day-basic", 0.91d, 1, 1, "platform", "7天无理由", "自签收次日起算"),
                new HybridRetriever.Retrieved("return-02-7day-exclusions", 0.84d, 2, 3, "platform", "例外", "定制商品除外")),
                2, 2, 7L);

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.POLICY_RETURN, "T1", true),
                mock(CacheService.class), llm, mock(ToolDispatcher.class), mock(FallbackService.class),
                perfModeGate(), retrieved, store);

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"七天无理由怎么算\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations.length()").value(2))
                .andExpect(jsonPath("$.context.ruleIds.length()").value(2))
                .andExpect(jsonPath("$.context.ruleIds[0]").value("return-01-7day-basic"))
                .andExpect(jsonPath("$.context.ruleIds[1]").value("return-02-7day-exclusions"))
                // 注入的是 3 条消息（2 user + 1 assistant）→ 历史用户轮数 = 2
                .andExpect(jsonPath("$.context.historyTurns").value(2))
                .andExpect(jsonPath("$.context.estimatedPromptTokens").isNumber());
    }

    @Test
    @DisplayName("零召回：context.ruleIds 是空数组，且 Prompt 仍输出「未检索到相关条款」占位（票 49）")
    void zeroRecallKeepsEmptyRuleIdsAndThePlaceholder() throws Exception {
        ArgumentCaptor<LlmTypes.Request> planRound = ArgumentCaptor.forClass(LlmTypes.Request.class);
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.complete(any())).thenReturn(LlmTypes.Reply.text("抱歉，没有找到对应条款"));

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.POLICY_RETURN, "T1", true),
                mock(CacheService.class), llm, mock(ToolDispatcher.class), mock(FallbackService.class));

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"一个语料里不存在的问题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.ruleIds").isArray())
                .andExpect(jsonPath("$.context.ruleIds.length()").value(0));

        // 票 49 的核心不变量：加了观测字段，Prompt 文本一个字节都不能变
        verify(llm).complete(planRound.capture());
        String userMessage = planRound.getValue().messages().stream()
                .filter(message -> "user".equals(message.role()))
                .map(LlmTypes.Message::content)
                .findFirst()
                .orElseThrow(() -> new AssertionError("规划请求里没有买家问题那条 user 消息"));
        assertTrue(userMessage.startsWith("【政策条款】\n（本轮未检索到相关条款）\n\n【买家问题】\n"),
                "零召回占位文本必须原样保留，实际=" + userMessage);
    }

    @Test
    @DisplayName("票 56：模型给的订单号格式合法但买家没报过 → 转 slot_ask，不拿它撞库")
    void fabricatedOrderNoIsAskedBackInsteadOfDispatched() throws Exception {
        LlmGateway llm = mock(LlmGateway.class);
        // 10023 是工具 schema 描述里的示例值（`平台订单号，例如 10023`）——本地 3B 会照抄它
        when(llm.complete(any())).thenReturn(new LlmTypes.Reply("", List.of(
                new LlmTypes.ToolCall("call-p56-1", ToolName.QUERY_LOGISTICS.apiName(),
                        Map.of("orderNo", "10023"))), 10, 2, null));
        BizMockClient bizMock = mock(BizMockClient.class);
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, mock(IdempotencyService.class), registry);

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ACTION_LOGISTICS, "T1", true),
                mock(CacheService.class), llm, dispatcher, mock(FallbackService.class));

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"帮我查下物流轨迹\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotAsked").value(true));

        verify(bizMock, never()).call(any(), anyMap(), any());
    }

    @Test
    @DisplayName("票 56：单号只出现在助手的旧回复里 → 不算出处，照样转 slot_ask")
    void orderNoOnlyInAnAssistantTurnIsNotProvenance() throws Exception {
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.complete(any())).thenReturn(new LlmTypes.Reply("", List.of(
                new LlmTypes.ToolCall("call-p56-2", ToolName.QUERY_LOGISTICS.apiName(),
                        Map.of("orderNo", "10023"))), 10, 2, null));
        BizMockClient bizMock = mock(BizMockClient.class);
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, mock(IdempotencyService.class), registry);
        SessionStore store = mock(SessionStore.class);
        when(store.load(anyString(), anyString(), anyString())).thenReturn(new SessionStore.Session(
                CONVERSATION,
                List.of(new SessionStore.Turn("user", "帮我查下物流轨迹"),
                        new SessionStore.Turn("assistant", "您的订单 10023 正在配送中")),
                null, new LinkedHashMap<>(), 0));
        when(store.appendTurn(any(), anyString(), anyString())).thenAnswer(call -> call.getArgument(0));

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ACTION_LOGISTICS, "T1", true),
                mock(CacheService.class), llm, dispatcher, mock(FallbackService.class), perfModeGate(),
                HybridRetriever.Result.unavailable(7L), store);

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"帮我查下物流轨迹\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotAsked").value(true));

        // 助手回复里的单号不构成出处：它的来源可能就是模型自己上一次编的
        verify(bizMock, never()).call(any(), anyMap(), any());
    }

    @Test
    @DisplayName("票 56 续办：追问之后买家补单号 → 这一轮的单号算出处，照常派发")
    void resumedTurnTrustsTheOrderNoTheBuyerJustSupplied() throws Exception {
        BizMockClient bizMock = mock(BizMockClient.class);
        when(bizMock.call(eq(ToolName.QUERY_LOGISTICS), anyMap(), eq(null)))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK,
                        "{\"status\":\"OK\",\"message\":\"物流已更新\"}", false));
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, mock(IdempotencyService.class), registry);
        SessionStore store = mock(SessionStore.class);
        when(store.load(anyString(), anyString(), anyString())).thenReturn(new SessionStore.Session(
                CONVERSATION, List.of(new SessionStore.Turn("user", "帮我查下物流轨迹")),
                ToolName.QUERY_LOGISTICS.apiName(), new LinkedHashMap<>(), 0));
        when(store.appendTurn(any(), anyString(), anyString())).thenAnswer(call -> call.getArgument(0));
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("物流已更新，正在派送中"));

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ACTION_LOGISTICS, "T1", true),
                mock(CacheService.class), llm, dispatcher, mock(FallbackService.class),
                perfModeGate(), HybridRetriever.Result.unavailable(7L), store);

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"90001 这单到哪了\"}"))
                .andExpect(status().isOk());

        // 待办重放这条路不进模型（正则取槽位）；单号由本轮买家那句给出，所以溯源放行
        verify(bizMock).call(eq(ToolName.QUERY_LOGISTICS), anyMap(), eq(null));
    }

    @Test
    @DisplayName("请求级幂等回放：同 token 重试不再问模型、不重复执行，直接回放首次结果（ADR 0046 票 58）")
    void repeatedRequestReplaysTheFirstResultWithoutCallingTheModel() throws Exception {
        LlmGateway llm = mock(LlmGateway.class);
        BizMockClient bizMock = mock(BizMockClient.class);
        FallbackService fallback = mock(FallbackService.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        // 首次执行留下的结果：索引命中后回放的就是它（真实实现里 complete() 写指针、结果另存一个键）
        when(idempotency.lookupByClientToken(eq(TENANT), eq(CUSTOMER), eq("tok-1"), anyString()))
                .thenReturn(Optional.of(new IdempotencyService.Replay(ToolName.APPLY_REFUND,
                        "{\"status\":\"OK\",\"payload\":{\"refundId\":\"RF-777\",\"orderNo\":\"90001\","
                                + "\"status\":\"PROCESSING\"}}")));
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, idempotency, registry);

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ACTION_REFUND, "T1", true),
                mock(CacheService.class), llm, dispatcher, fallback);

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"这单我要退款 90001\",\"idempotencyToken\":\"tok-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(containsString("RF-777")))
                .andExpect(jsonPath("$.answer").value(containsString("本次没有重复提交")))
                .andExpect(jsonPath("$.toolUsed").value(true));

        // 回放的全部意义就在这四条：不打模型、不重复执行、不当成降级落工单
        verify(llm, never()).complete(any());
        verify(llm, never()).stream(any(), any());
        verify(bizMock, never()).call(any(), anyMap(), any());
        verify(fallback, never()).escalate(any(), any(), any());
    }

    @Test
    @DisplayName("正对照：请求里没有 token 时不查索引——回放不该被无条件触发")
    void withoutAClientTokenTheRequestNeverLooksUpTheReplayIndex() throws Exception {
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.complete(any())).thenReturn(LlmTypes.Reply.text("已为您登记退款申请"));
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("已为您登记退款申请"));
        IdempotencyService idempotency = mock(IdempotencyService.class);
        ToolDispatcher dispatcher = new ToolDispatcher(mock(BizMockClient.class), idempotency, registry);

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ACTION_REFUND, "T1", true),
                mock(CacheService.class), llm, dispatcher, mock(FallbackService.class));

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"这单我要退款 90001\"}"))
                .andExpect(status().isOk());

        verify(idempotency, never()).lookupByClientToken(any(), any(), any(), any());
    }

    @Test
    @DisplayName("退款受理：PENDING_APPROVAL 回确定性受理话术并收尾，不打第二跳模型（ADR 0047 票 59）")
    void refundApprovalStopsAtTheGateWithoutASecondModelHop() throws Exception {
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.complete(any())).thenReturn(new LlmTypes.Reply("", List.of(
                new LlmTypes.ToolCall("call-refund-1", ToolName.APPLY_REFUND.apiName(), Map.of("orderNo", "90001"))),
                12, 3, null));

        BizMockClient bizMock = mock(BizMockClient.class);
        when(bizMock.call(eq(ToolName.APPLY_REFUND), anyMap(), anyString()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.PENDING_APPROVAL,
                        "{\"status\":\"PENDING_APPROVAL\",\"payload\":{\"refundId\":\"RF-777\",\"orderNo\":\"90001\","
                                + "\"status\":\"PENDING_REVIEW\"}}", false));
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.begin(anyString(), anyString(), any(ToolName.class), anyMap(), any(), any()))
                .thenReturn(new IdempotencyService.Guard(false, false, null, "tok", null, null, () -> {
                }));
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, idempotency, registry);

        FallbackService fallback = mock(FallbackService.class);
        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ACTION_REFUND, "T1", true),
                mock(CacheService.class), llm, dispatcher, fallback);

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"订单90001申请退款\"}"))
                .andExpect(status().isOk())
                // 受理话术写明资金未动、等待人工审核，并带上申请编号（只可能来自工具结果）
                .andExpect(jsonPath("$.answer").value(containsString("等待人工审核")))
                .andExpect(jsonPath("$.answer").value(containsString("RF-777")))
                .andExpect(jsonPath("$.toolUsed").value(true))
                .andExpect(jsonPath("$.fallbackReason").doesNotExist());

        // 受理即收尾：一次规划调用，没有第二跳、没有流式总结、不落工单（受理不是降级）
        verify(llm, times(1)).complete(any());
        verify(llm, never()).stream(any(), any());
        verify(fallback, never()).escalate(any(), any(), any());
        assertEquals(1.0d, registry.get("shoppilot_refund_pending_total").counter().count());
    }

    @Test
    @DisplayName("空答案不是答案：模型不发工具也不给正文时，返回可查工单的降级而不是 200 空串（黑盒 QA 修复）")
    void blankModelAnswerFallsBackWithATicket() throws Exception {
        LlmGateway llm = mock(LlmGateway.class);
        // 本地 3B 实测会出现这一形态：既没有 tool call，也没有任何正文
        when(llm.complete(any())).thenReturn(new LlmTypes.Reply("", List.of(), 10, 0, null));
        BizMockClient bizMock = mock(BizMockClient.class);
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, mock(IdempotencyService.class), registry);
        FallbackService fallback = mock(FallbackService.class);
        when(fallback.escalate(eq(FallbackReason.LLM_CIRCUIT_OPEN), anyString(), anyString()))
                .thenReturn(Optional.of("T-blank"));

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ACTION_REFUND, "T1", true),
                mock(CacheService.class), llm, dispatcher, fallback);

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"订单90001我要退款\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(containsString("工单号 T-blank")))
                .andExpect(jsonPath("$.fallbackReason").value("LLM_CIRCUIT_OPEN"))
                .andExpect(jsonPath("$.ticketId").value("T-blank"));
        // 落工单是这条断言的要害：买家没拿到答案，就必须有一个可查的出口（ADR 0009）
        verify(fallback).escalate(eq(FallbackReason.LLM_CIRCUIT_OPEN), anyString(), anyString());
    }

    @Test
    @DisplayName("网关派生的读工具也要进 plan：toolUsed=true 不许与 plan=[] 同现（黑盒 QA 修复）")
    void gatewayDerivedDispatchIsRecordedInPlan() throws Exception {
        LlmGateway llm = mock(LlmGateway.class);
        // 模型没发 function call：走网关派生那条路（ACTION_ORDER → 派生 queryOrderDetail）
        when(llm.complete(any())).thenReturn(LlmTypes.Reply.text("订单 90001 目前处于已支付状态。"));
        // 派生派发把 rounds 推到了 1，所以收尾走的是流式总结那一支
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("订单 90001 目前处于已支付状态。"));
        BizMockClient bizMock = mock(BizMockClient.class);
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), eq(null)))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK,
                        "{\"status\":\"OK\",\"payload\":{\"orderNo\":\"90001\"}}", false));
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, mock(IdempotencyService.class), registry);

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true),
                mock(CacheService.class), llm, dispatcher, mock(FallbackService.class));

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"帮我查一下订单90001现在的状态\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolUsed").value(true))
                // 票 48 的契约：plan 记的是真的执行过的步；这一枪真的派发了，所以必须有一条
                .andExpect(jsonPath("$.plan.length()").value(1))
                .andExpect(jsonPath("$.plan[0].tool").value("queryOrderDetail"))
                .andExpect(jsonPath("$.plan[0].status").value("OK"));
    }

    @Test
    @DisplayName("缓存命中路径的 context.ruleIds 镜像 citations（票 49 的不变量在命中路径同样成立）")
    void cacheHitContextMirrorsCitations() throws Exception {
        CacheService cache = mock(CacheService.class);
        CacheEntry cached = CacheEntry.of("签收后七天内可以申请退货", Intent.POLICY_RETURN, TENANT,
                CacheService.SCOPE_SHOP, 7L, List.of("POLICY-RETURN-01", "POLICY-RETURN-02"), "perf-mock", QUERY);
        when(cache.lookup(eq(TENANT), eq(Intent.POLICY_RETURN), eq(QUERY), eq(7L), any()))
                .thenReturn(new CacheService.Lookup(CacheService.Layer.L1, Optional.of(cached), null,
                        QUERY, false));

        MockMvc mvc = mockMvc(TriageResult.policy(Intent.POLICY_RETURN, "T1", 1.0d),
                cache, mock(LlmGateway.class), mock(ToolDispatcher.class), mock(FallbackService.class));

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUERY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations.length()").value(2))
                .andExpect(jsonPath("$.context.ruleIds.length()").value(2))
                .andExpect(jsonPath("$.context.ruleIds[0]").value("POLICY-RETURN-01"))
                .andExpect(jsonPath("$.context.ruleIds[1]").value("POLICY-RETURN-02"))
                // 命中路径没有 Prompt（零模型调用），所以这两格照实为零
                .andExpect(jsonPath("$.context.historyTurns").value(0))
                .andExpect(jsonPath("$.context.estimatedPromptTokens").value(0));
    }

    @Test
    @DisplayName("幂等回放话术不把内部枚举念给买家：PENDING_REVIEW 要翻成中文（黑盒 QA 修复）")
    void replayReplyDoesNotLeakInternalRefundStatus() throws Exception {
        LlmGateway llm = mock(LlmGateway.class);
        BizMockClient bizMock = mock(BizMockClient.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.lookupByClientToken(eq(TENANT), eq(CUSTOMER), eq("tok-9"), anyString()))
                .thenReturn(Optional.of(new IdempotencyService.Replay(ToolName.APPLY_REFUND,
                        "{\"status\":\"PENDING_APPROVAL\",\"payload\":{\"refundId\":\"RF-9\",\"orderNo\":\"90001\","
                                + "\"status\":\"PENDING_REVIEW\"}}")));
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, idempotency, registry);

        MockMvc mvc = mockMvc(TriageResult.dynamic(Intent.ACTION_REFUND, "T1", true),
                mock(CacheService.class), llm, dispatcher, mock(FallbackService.class));

        mvc.perform(post("/api/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"这单我要退款 90001\",\"idempotencyToken\":\"tok-9\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(containsString("待人工审核")))
                .andExpect(jsonPath("$.answer").value(not(containsString("PENDING_REVIEW"))));
    }

    private MockMvc mockMvc(TriageResult triageResult, CacheService cache, LlmGateway llm,
                            ToolDispatcher dispatcher, FallbackService fallback) {
        return mockMvc(triageResult, cache, llm, dispatcher, fallback, perfModeGate());
    }

    private MockMvc mockMvc(TriageResult triageResult, CacheService cache, LlmGateway llm,
                            ToolDispatcher dispatcher, FallbackService fallback, SentimentGate gate) {
        HybridRetriever retriever = mock(HybridRetriever.class);
        when(retriever.retrieve(anyString(), anyString(), any()))
                .thenReturn(HybridRetriever.Result.unavailable(7L));
        SessionStore sessionStore = mock(SessionStore.class);
        when(sessionStore.load(anyString(), anyString(), anyString()))
                .thenReturn(SessionStore.Session.empty(CONVERSATION));
        when(sessionStore.appendTurn(any(), anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(0));
        return mockMvc(triageResult, cache, llm, dispatcher, fallback, gate,
                HybridRetriever.Result.unavailable(7L), sessionStore);
    }

    /**
     * 最全的那条装配缝：允许注入检索结果与会话状态。
     *
     * <p>票 49 的观测字段（规则块编号、历史轮数）只在"真的检索到东西"与"真的有历史"时才非空，
     * 所以那两条用例必须能注入这两样，否则断言只能落在空值上、等于没测。
     */
    private MockMvc mockMvc(TriageResult triageResult, CacheService cache, LlmGateway llm,
                            ToolDispatcher dispatcher, FallbackService fallback, SentimentGate gate,
                            HybridRetriever.Result retrieved, SessionStore sessionStore) {
        TriageEngine triage = mock(TriageEngine.class);
        when(triage.triage(anyString())).thenReturn(new TriageEngine.Outcome(triageResult, null));
        // 显式转人工谓词走真实现（纯词表、0 token）：mock 若默认返回 false，ADR 0042 的优先级
        // 在这条缝上就永远不生效，回归用例会变成一条恒绿假防线。
        when(triage.isExplicitEscalation(anyString()))
                .thenAnswer(call -> T0RuleLayer.explicitEscalation(call.getArgument(0)));

        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);

        HybridRetriever retriever = mock(HybridRetriever.class);
        when(retriever.retrieve(anyString(), anyString(), any()))
                .thenReturn(retrieved);

        WriteBackPolicy writeBackPolicy = mock(WriteBackPolicy.class);
        when(writeBackPolicy.evaluate(any())).thenReturn(new WriteBackPolicy.Verdict(false, "integration-test"));

        AgentStateMachine machine = new AgentStateMachine(triage, cache, mock(SingleFlight.class),
                writeBackPolicy, epoch, retriever, llm, dispatcher, sessionStore, fallback,
                properties(), mock(WriteBackPool.class), new PromptCatalog(), gate, new StyleService(), registry);

        RateLimitService rateLimit = mock(RateLimitService.class);
        when(rateLimit.tryAcquire(any(), any(), any())).thenReturn(RateLimitService.Decision.pass());

        ChatController controller = new ChatController(machine, MAPPER, registry, new PromptCatalog(),
                mock(FeedbackService.class), new ChatAdmission(cache, rateLimit, fallback, registry));
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** 情绪门第二层可用（dev 口径）且判 URGENT：复现 2026-09-20 矩阵 F2 的触发条件。 */
    private SentimentGate urgentGate() {
        LlmGateway gateLlm = mock(LlmGateway.class);
        when(gateLlm.mode()).thenReturn("dev");
        when(gateLlm.complete(any()))
                .thenReturn(LlmTypes.Reply.text("{\"emotion\":\"URGENT\",\"confidence\":0.9}"));
        return new SentimentGate(gateLlm, new ObjectMapper(), registry, new PromptCatalog("prompts/sentiment-classifier/"));
    }

    /** 情绪门用词典层就够（perf-mode 跳过第二层 LLM 分类）：集成测试里的升级全部来自词典定案。 */
    private SentimentGate perfModeGate() {
        LlmGateway gateLlm = mock(LlmGateway.class);
        when(gateLlm.mode()).thenReturn("perf");
        return new SentimentGate(gateLlm, new ObjectMapper(), registry, new PromptCatalog("prompts/sentiment-classifier/"));
    }

    private static GatewayProperties properties() {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.agent()).thenReturn(new GatewayProperties.Agent(2, 2,
                Duration.ofMinutes(30), 4));
        when(properties.llm()).thenReturn(new GatewayProperties.Llm("perf", "http://127.0.0.1:1",
                "unused", "perf-mock", 0.0d, Duration.ofSeconds(1), Duration.ofSeconds(1),
                0L, null, null, null, null, 1024));
        when(properties.cache()).thenReturn(new GatewayProperties.Cache(true, Duration.ofHours(1),
                0.95d, Duration.ofSeconds(60), Duration.ofSeconds(2), true));
        return properties;
    }
}
