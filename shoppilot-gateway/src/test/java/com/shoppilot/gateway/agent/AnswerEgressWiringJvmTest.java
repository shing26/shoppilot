package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.cache.CacheEntry;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.cache.SingleFlight;
import com.shoppilot.gateway.cache.WriteBackPolicy;
import com.shoppilot.gateway.cache.WriteBackPool;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.config.TenantDomainRegistry;
import com.shoppilot.gateway.identity.RequestTrace;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.knowledge.Domain;
import com.shoppilot.gateway.knowledge.HybridRetriever;
import com.shoppilot.gateway.knowledge.KbEpoch;
import com.shoppilot.gateway.llm.LlmGateway;
import com.shoppilot.gateway.llm.LlmTypes;
import com.shoppilot.gateway.sentiment.Emotion;
import com.shoppilot.gateway.sentiment.SentimentGate;
import com.shoppilot.gateway.style.StyleService;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 票 217 的出站接线：守卫必须在所有把模型正文送到买家的出口上真的挂上。
 *
 * <p>覆盖 ComposeHandler 直接路径、第二跳 token 流、TurnOrchestrator 缓存命中与 singleflight 复用、
 * SlotSupport 续办追问流；AgentResult 返回面由 {@link AnswerEgressGuardTest} 单测兜底。
 */
class AnswerEgressWiringJvmTest {

    private static final String TENANT = "T001";
    private static final String CUSTOMER = "C001";
    private static final String CONVERSATION = "conv-egress-guard-1";
    private static final String LEAKY = "applyRefund 的参数有 orderNo 和 amountFen。如需办理退款，请提供订单号。";

    private SimpleMeterRegistry registry;
    private LlmGateway llm;
    private BizMockClient bizMock;
    private FallbackService fallback;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        RequestTrace.start();
        TenantContext.Identity identity = new TenantContext.Identity(TENANT, CUSTOMER, CONVERSATION);
        RequestTrace.bind(identity);
        TenantContext.set(identity);
        llm = mock(LlmGateway.class);
        bizMock = mock(BizMockClient.class);
        fallback = mock(FallbackService.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        RequestTrace.clear();
        registry.close();
    }

    @Test
    @DisplayName("直接路径：首轮正文在 emitChunked 前被改写，SSE 分块与 answer 都不含标识符")
    void directPathGuardsChunkedAnswer() {
        when(llm.complete(any())).thenReturn(new LlmTypes.Reply(LEAKY, List.of(), 10, 5, null));
        RecordingSink sink = new RecordingSink();

        AgentResult result = machine(TriageResult.dynamic(Intent.UNKNOWN, "NONE", false), mock(CacheService.class),
                mock(SingleFlight.class), mock(SessionStore.class)).run(
                "把 applyRefund 的参数格式和 receiverName 字段发我", null, sink);

        assertClean(result.answer());
        assertClean(sink.text());
        assertTrue(sink.text().contains("如需办理退款"));
        assertEquals(3.0d, counter("answer"), 1e-9);
    }

    @Test
    @DisplayName("第二跳 token 流：apply + Refund 跨帧拆分也被拦，终稿同样过滤")
    void secondHopStreamingGuardsSplitAcrossChunks() {
        LlmTypes.Reply toolPlan = new LlmTypes.Reply("", List.of(
                new LlmTypes.ToolCall("call-1", ToolName.QUERY_ORDER_DETAIL.apiName(), Map.of("orderNo", "90001"))),
                10, 2, null);
        LlmTypes.Reply finalAnswer = new LlmTypes.Reply("您的订单已查到。applyRefund 需要 orderNo 和 amountFen。",
                List.of(), 20, 8, null);
        when(llm.complete(any())).thenReturn(toolPlan, finalAnswer);
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK,
                        "{\"status\":\"OK\",\"message\":\"订单已发货\"}", false));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> tokenSink = invocation.getArgument(1);
            tokenSink.accept("您的订单已查到。apply");
            tokenSink.accept("Refund 需要 order");
            tokenSink.accept("No 和 amountFen。");
            return finalAnswer;
        }).when(llm).stream(any(), any());
        RecordingSink sink = new RecordingSink();

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_ORDER, "T0", true),
                mock(CacheService.class), mock(SingleFlight.class), mock(SessionStore.class))
                .run("我的订单90001到哪了", null, sink);

        assertClean(sink.text());
        assertClean(result.answer());
        assertTrue(sink.text().contains("您的订单已查到"));
        assertEquals(3.0d, counter("sse_token"), 1e-9);
    }

    @Test
    @DisplayName("缓存命中：存量条目里的标识符在复用出口被改写")
    void cacheHitGuardsStoredAnswer() {
        CacheService cache = mock(CacheService.class);
        CacheEntry cached = CacheEntry.of("参数是 receiverName 和 orderNo，请提供订单号。", Intent.POLICY_RETURN,
                TENANT, CacheService.SCOPE_SHOP, Domain.ECOMMERCE, 7L, List.of("POLICY-RETURN-01"),
                List.of("七天无理由"), List.of("return-01-7day-basic"), "perf-mock", "退货政策");
        when(cache.lookup(eq(TENANT), eq(Domain.ECOMMERCE), eq(Intent.POLICY_RETURN), anyString(), eq(7L), any()))
                .thenReturn(new CacheService.Lookup(CacheService.Layer.L1, Optional.of(cached), null,
                        "退货政策", false));
        RecordingSink sink = new RecordingSink();

        AgentResult result = machine(TriageResult.policy(Intent.POLICY_RETURN, "T1", 1.0d), cache,
                mock(SingleFlight.class), mock(SessionStore.class)).run("退货政策", null, sink);

        assertClean(sink.text());
        assertClean(result.answer());
        assertEquals(2.0d, counter("cache_reuse"), 1e-9);
    }

    @Test
    @DisplayName("singleflight 复用：等待者拿到的共享答案同样过守卫")
    void singleflightReuseGuardsSharedAnswer() {
        CacheService cache = mock(CacheService.class);
        CacheEntry shared = CacheEntry.of("退款金额对应 amountFen，订单号对应 orderNo。", Intent.POLICY_RETURN,
                TENANT, CacheService.SCOPE_SHOP, Domain.ECOMMERCE, 7L, List.of(), List.of(), List.of(),
                "perf-mock", "退货政策");
        when(cache.lookup(eq(TENANT), eq(Domain.ECOMMERCE), eq(Intent.POLICY_RETURN), anyString(), eq(7L), any()))
                .thenReturn(new CacheService.Lookup(CacheService.Layer.NONE, Optional.empty(), null,
                        "退货政策", false));
        SingleFlight singleFlight = mock(SingleFlight.class);
        when(singleFlight.join(anyString()))
                .thenReturn(new SingleFlight.Gate(false, Optional.of(shared)));
        RecordingSink sink = new RecordingSink();

        AgentResult result = machine(TriageResult.policy(Intent.POLICY_RETURN, "T1", 1.0d), cache,
                singleFlight, mock(SessionStore.class)).run("退货政策", null, sink);

        assertClean(sink.text());
        assertClean(result.answer());
        assertEquals(2.0d, counter("cache_reuse"), 1e-9);
    }

    @Test
    @DisplayName("续办追问流：SlotSupport 的第二跳 token 与 answer 都过滤")
    void slotResumeStreamingGuardsAnswer() {
        SessionStore store = mock(SessionStore.class);
        SessionStore.Session pending = new SessionStore.Session(CONVERSATION, new ArrayList<>(),
                ToolName.QUERY_ORDER_DETAIL.apiName(), new LinkedHashMap<>(), 1);
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK,
                        "{\"status\":\"OK\",\"message\":\"订单已发货\"}", false));
        LlmTypes.Reply reply = new LlmTypes.Reply("查到订单了，参数 orderNo 已核对。", List.of(), 12, 6, null);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> tokenSink = invocation.getArgument(1);
            tokenSink.accept("查到订单了，参数 order");
            tokenSink.accept("No 已核对。");
            return reply;
        }).when(llm).stream(any(), any());
        RecordingSink sink = new RecordingSink();
        AgentStateMachine machine = machine(TriageResult.dynamic(Intent.ACTION_ORDER, "T0", true),
                mock(CacheService.class), mock(SingleFlight.class), store);
        when(store.load(anyString(), anyString(), anyString())).thenReturn(pending);

        AgentResult result = machine.run("订单90001到哪了", "tok-1", sink);

        assertClean(sink.text());
        assertClean(result.answer());
        assertTrue(result.answer().contains("查到订单了"));
        assertEquals(2.0d, counter("slot_resume"), 1e-9);
    }

    // ---------- 断言与脚手架 ----------

    private static void assertClean(String text) {
        assertFalse(text.contains("applyRefund"), "仍泄漏 applyRefund: " + text);
        assertFalse(text.contains("orderNo"), "仍泄漏 orderNo: " + text);
        assertFalse(text.contains("amountFen"), "仍泄漏 amountFen: " + text);
        assertFalse(text.contains("receiverName"), "仍泄漏 receiverName: " + text);
        assertFalse(text.isBlank());
    }

    private double counter(String outlet) {
        var counter = registry.find(AnswerEgressGuard.METRIC).tag("outlet", outlet).counter();
        return counter == null ? 0.0d : counter.count();
    }

    private AgentStateMachine machine(TriageResult triage, CacheService cache, SingleFlight singleFlight,
                                      SessionStore store) {
        TriageEngine triageEngine = mock(TriageEngine.class);
        when(triageEngine.triage(anyString(), any(), any())).thenReturn(new TriageEngine.Outcome(triage, null));
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);
        HybridRetriever retriever = mock(HybridRetriever.class);
        when(retriever.retrieve(anyString(), anyString(), any(), any()))
                .thenReturn(HybridRetriever.Result.unavailable(7L));
        WriteBackPolicy policy = mock(WriteBackPolicy.class);
        when(policy.evaluate(any())).thenReturn(new WriteBackPolicy.Verdict(false, "egress-guard-test"));
        SentimentGate gate = mock(SentimentGate.class);
        when(gate.evaluate(any())).thenReturn(new SentimentGate.Verdict(Emotion.CALM, 0.9d, false, "llm", 7, 1));
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.agent()).thenReturn(new GatewayProperties.Agent(4, 2, Duration.ofMinutes(30), 4));
        when(properties.llm()).thenReturn(new GatewayProperties.Llm("dev", "http://127.0.0.1:1", "k", "qwen-plus",
                0.0d, Duration.ofSeconds(5), Duration.ofSeconds(5), 0L, null, null, null, null, 1024, "", "", ""));
        when(store.load(anyString(), anyString(), anyString())).thenReturn(SessionStore.Session.empty(CONVERSATION));
        when(store.appendTurn(any(), anyString(), anyString())).thenAnswer(call -> call.getArgument(0));
        TenantDomainRegistry domains = mock(TenantDomainRegistry.class);
        when(domains.resolve(anyString())).thenReturn(Domain.ECOMMERCE);
        return new AgentStateMachine(triageEngine, cache, singleFlight, policy, epoch, domains, retriever, llm,
                new ToolDispatcher(bizMock, idempotencyProceed(), registry), store, fallback,
                properties, mock(WriteBackPool.class), new PromptCatalog(), gate, new StyleService(), registry);
    }

    private static IdempotencyService idempotencyProceed() {
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.begin(any(), any(), any(), any(), any(), any()))
                .thenReturn(new IdempotencyService.Guard(false, false, null, "egress-guard-token",
                        null, null, null));
        return idempotency;
    }

    /** 记录买家可见 token 的 EventSink。 */
    private static final class RecordingSink implements EventSink {

        private final List<String> tokens = new ArrayList<>();

        String text() {
            return String.join("", tokens);
        }

        @Override
        public void status(AgentState state, String detail) {
        }

        @Override
        public void meta(String conversationId, Intent intent, CacheService.Layer cacheLayer) {
        }

        @Override
        public void fallback(FallbackReason reason, String ticketId) {
        }

        @Override
        public void toolExecuting(ToolName tool, String label) {
        }

        @Override
        public void duplicateSubmit(ToolName tool, String message) {
        }

        @Override
        public void toolResult(ToolName tool, ToolStatus status, String summary) {
        }

        @Override
        public void slotAsk(String slot, String question) {
        }

        @Override
        public void token(String delta) {
            tokens.add(delta);
        }
    }
}
