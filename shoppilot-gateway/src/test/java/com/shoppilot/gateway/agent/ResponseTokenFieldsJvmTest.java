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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 响应 token 字段的口径回填（round35 票 145 / R12 登记债的三处缺口）。
 *
 * <p>判据一句话：**响应里的 token 字段 = 本请求全部 LLM 调用之和（含入口层）**，
 * 不再是最后一跳或 0。三处缺口各钉一条：
 * <ol>
 *   <li>预算检查后收尾：此前只报那一跳（票 140 实测 4 轮用例上报 2,811 vs 计费 11,338）；</li>
 *   <li>{@code fallback()} / 受理出口：此前恒报 0，而前面的规划调用已经花掉了；</li>
 *   <li>缓存命中：此前报 0，而入口层情绪分类照付（票 140 实测 ≈0.43k/请求）。</li>
 * </ol>
 *
 * <p>这些字段**不是计费口径**（评测成本列读 {@code shoppilot_llm_tokens_total} 或日预算计数），
 * 本类只钉"别低报"，不把响应字段抬成账本。
 */
class ResponseTokenFieldsJvmTest {

    private static final String TENANT = "T001";
    private static final String CONVERSATION = "conv-token-fields-1";
    private static final String QUERY = "我的订单90001到哪了";

    private SimpleMeterRegistry registry;
    private LlmGateway llm;
    private BizMockClient bizMock;
    private FallbackService fallback;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        RequestTrace.start();
        TenantContext.Identity identity = new TenantContext.Identity(TENANT, "C001", CONVERSATION);
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
    @DisplayName("缓存命中：零模型轮，token 字段报入口层那一跳（此前恒 0 而入口层照付）")
    void cacheHitIncludesEntryLayerTokens() {
        CacheService cache = mock(CacheService.class);
        CacheEntry cached = CacheEntry.of("签收后七天内可以申请退货", Intent.POLICY_RETURN, TENANT,
                CacheService.SCOPE_SHOP, Domain.ECOMMERCE, 7L, List.of("POLICY-RETURN-01"), List.of("七天无理由"),
                List.of("return-01-7day-basic"), "perf-mock", QUERY);
        when(cache.lookup(eq(TENANT), eq(Domain.ECOMMERCE), eq(Intent.POLICY_RETURN), eq(QUERY), eq(7L), any()))
                .thenReturn(new CacheService.Lookup(CacheService.Layer.L1, Optional.of(cached), null,
                        QUERY, false));

        AgentResult result = machine(TriageResult.policy(Intent.POLICY_RETURN, "T1", 1.0d), cache,
                sentiment(400, 30)).run(QUERY, null, EventSink.NOOP);

        assertEquals("签收后七天内可以申请退货", result.answer());
        assertEquals(400, result.promptTokens(), "命中路径也要把入口层情绪分类的输入 token 记上");
        assertEquals(30, result.completionTokens());
        verify(llm, never()).complete(any());
        verify(llm, never()).stream(any(), any());
    }

    @Test
    @DisplayName("预算检查后收尾：token 字段 = 入口层 + 规划各轮 + 预算检查那一跳（此前只报最后一跳）")
    void budgetCheckAnswerSumsEveryCall() {
        LlmTypes.Reply toolPlan = new LlmTypes.Reply("", List.of(
                new LlmTypes.ToolCall("call-1", ToolName.QUERY_ORDER_DETAIL.apiName(), Map.of("orderNo", "90001"))),
                10, 2, null);
        LlmTypes.Reply budgetAnswer = new LlmTypes.Reply("您的订单已发货", List.of(), 25, 5, null);
        when(llm.complete(any())).thenReturn(toolPlan, toolPlan, toolPlan, toolPlan, budgetAnswer);
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK, "{\"status\":\"OK\",\"message\":\"订单已发货\"}",
                        false));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true),
                mock(CacheService.class), sentiment(7, 1)).run(QUERY, null, EventSink.NOOP);

        assertEquals("您的订单已发货", result.answer());
        assertEquals(7 + 4 * 10 + 25, result.promptTokens(),
                "4 轮规划 + 预算检查那一跳 + 入口层，一个都不许漏");
        assertEquals(1 + 4 * 2 + 5, result.completionTokens());
        verify(llm, never()).stream(any(), any());
    }

    @Test
    @DisplayName("降级出口：已经花掉的规划轮次照实回填（此前 fallback 恒报 0）")
    void fallbackReportsSpentRoundTokens() {
        when(llm.complete(any())).thenReturn(new LlmTypes.Reply("", List.of(
                new LlmTypes.ToolCall("call-1", ToolName.APPLY_REFUND.apiName(), Map.of("orderNo", "90001"))),
                10, 2, null));
        when(bizMock.call(eq(ToolName.APPLY_REFUND), anyMap(), any()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.TIMEOUT,
                        "{\"status\":\"TIMEOUT\",\"message\":\"业务系统响应超时\"}", true));
        when(fallback.escalate(eq(FallbackReason.TOOL_UNAVAILABLE), anyString(), anyString()))
                .thenReturn(Optional.of("T-960"));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_REFUND, "T1", true),
                mock(CacheService.class), sentiment(7, 1)).run(QUERY, null, EventSink.NOOP);

        assertEquals(FallbackReason.TOOL_UNAVAILABLE, result.fallbackReason());
        assertEquals(7 + 10, result.promptTokens(), "降级不等于没花钱：入口层与那次规划都要报");
        assertEquals(1 + 2, result.completionTokens());
    }

    @Test
    @DisplayName("受理出口：token 字段回填受理前那次规划调用（此前恒报 0）")
    void pendingApprovalReportsSpentRoundTokens() {
        when(llm.complete(any())).thenReturn(new LlmTypes.Reply("", List.of(
                new LlmTypes.ToolCall("call-1", ToolName.APPLY_REFUND.apiName(), Map.of("orderNo", "90001"))),
                10, 2, null));
        when(bizMock.call(eq(ToolName.APPLY_REFUND), anyMap(), any()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.PENDING_APPROVAL,
                        "{\"status\":\"PENDING_APPROVAL\",\"payload\":{\"refundId\":\"RF-1\",\"orderNo\":\"90001\"}}",
                        false));
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.begin(any(), any(), any(), any(), any(), any()))
                .thenReturn(new IdempotencyService.Guard(false, false, null, "tok", null, null, () -> {
                }));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_REFUND, "T1", true),
                mock(CacheService.class), sentiment(7, 1), idempotency).run(QUERY, null, EventSink.NOOP);

        assertEquals("PENDING_APPROVAL", result.plan().get(0).status());
        assertEquals(7 + 10, result.promptTokens());
        assertEquals(1 + 2, result.completionTokens());
        verify(llm, times(1)).complete(any());
    }

    // ---------- 脚手架 ----------

    private AgentStateMachine machine(TriageResult triage, CacheService cache, SentimentGate.Verdict sentiment) {
        return machine(triage, cache, sentiment, idempotencyProceed());
    }

    private AgentStateMachine machine(TriageResult triage, CacheService cache, SentimentGate.Verdict sentiment,
                                      IdempotencyService idempotency) {
        TriageEngine triageEngine = mock(TriageEngine.class);
        when(triageEngine.triage(anyString(), any())).thenReturn(new TriageEngine.Outcome(triage, null));
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);
        HybridRetriever retriever = mock(HybridRetriever.class);
        when(retriever.retrieve(anyString(), anyString(), any(), any()))
                .thenReturn(HybridRetriever.Result.unavailable(7L));
        WriteBackPolicy policy = mock(WriteBackPolicy.class);
        when(policy.evaluate(any())).thenReturn(new WriteBackPolicy.Verdict(false, "token-fields-test"));
        SentimentGate gate = mock(SentimentGate.class);
        when(gate.evaluate(any())).thenReturn(sentiment);
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.agent()).thenReturn(new GatewayProperties.Agent(4, 2, Duration.ofMinutes(30), 4));
        when(properties.llm()).thenReturn(new GatewayProperties.Llm("dev", "http://127.0.0.1:1", "k", "qwen-plus",
                0.0d, Duration.ofSeconds(5), Duration.ofSeconds(5), 0L, null, null, null, null, 1024));
        SessionStore store = mock(SessionStore.class);
        when(store.load(anyString(), anyString(), anyString())).thenReturn(SessionStore.Session.empty(CONVERSATION));
        when(store.appendTurn(any(), anyString(), anyString())).thenAnswer(call -> call.getArgument(0));
        TenantDomainRegistry domains = mock(TenantDomainRegistry.class);
        when(domains.resolve(anyString())).thenReturn(Domain.ECOMMERCE);
        SingleFlight singleFlight = mock(SingleFlight.class);
        when(singleFlight.join(anyString())).thenReturn(new SingleFlight.Gate(true, Optional.empty()));
        return new AgentStateMachine(triageEngine, cache, singleFlight, policy, epoch, domains, retriever, llm,
                new ToolDispatcher(bizMock, idempotency, registry), store, fallback, properties,
                mock(WriteBackPool.class), new PromptCatalog(), gate, new StyleService(), registry);
    }

    /** 入口层的情绪分类：dev 口径下真的调了模型，token 用量随 Verdict 带回来。 */
    private static SentimentGate.Verdict sentiment(int promptTokens, int completionTokens) {
        return new SentimentGate.Verdict(Emotion.CALM, 0.9d, false, "llm", promptTokens, completionTokens);
    }

    private static IdempotencyService idempotencyProceed() {
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.begin(any(), any(), any(), any(), any(), any()))
                .thenReturn(new IdempotencyService.Guard(false, false, null, "token-fields-test-token",
                        null, null, null));
        return idempotency;
    }
}
