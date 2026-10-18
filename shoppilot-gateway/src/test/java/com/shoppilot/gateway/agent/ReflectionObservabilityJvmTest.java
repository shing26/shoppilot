package com.shoppilot.gateway.agent;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 反思观测面的 0 token 契约（round35 票 145；口径 = 票 139 的字段 / 载体清单）。
 *
 * <p>钉三态（触发 / 不触发 / 次数用尽）的**可机读载体**：
 * <ul>
 *   <li>{@code plan[].attempt} / {@code plan[].reflection}——反思重试成功的那一步读得到 2 / true；</li>
 *   <li>{@code shoppilot_reflection_total{outcome=triggered|exhausted}}——两条各 +1；</li>
 *   <li>trace 的**小写头**行 {@code reflection=<tool> attempt=<n>}（大写会被 gold 的
 *       {@code executed_tools} 当成工具序列判红）；</li>
 * </ul>
 *
 * <p>失败的那一枪不进 {@code plan[]} 的不变量在票 144 的用例里已钉；本类再钉一次 attempt 的
 * 数法——它必须把失败枪算进去，否则反思重试成功的那一步会是 attempt=1，与票 139 的判据对不上。
 */
class ReflectionObservabilityJvmTest {

    private static final String CONVERSATION = "conv-reflection-obs-1";

    private SimpleMeterRegistry registry;
    private LlmGateway llm;
    private BizMockClient bizMock;
    private FallbackService fallback;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        RequestTrace.start();
        TenantContext.Identity identity = new TenantContext.Identity("T001", "C001", CONVERSATION);
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
    @DisplayName("触发：反思重试成功的那一步 attempt=2 且 reflection=true，counter triggered +1，trace 有小写 reflection= 行")
    void reflectionRoundExposesAttemptReflectionAndTriggeredCounter() {
        when(llm.complete(any())).thenReturn(
                toolCall("call-1", ToolName.QUERY_ORDER_DETAIL),
                toolCall("call-2", ToolName.QUERY_ORDER_DETAIL),
                LlmTypes.Reply.text("您的订单已发货"));
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("您的订单已发货"));
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(outcome(ToolStatus.TIMEOUT, "业务系统响应超时"),
                        new BizMockClient.Outcome(ToolStatus.OK, "{\"status\":\"OK\",\"message\":\"订单已发货\"}",
                                false));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true))
                .run("我的订单90001到哪了", null, EventSink.NOOP);

        // plan[] 里只有办成的那一枪，且它带反思的印记
        assertEquals(1, result.plan().size(), "失败的那一枪不进 plan[]（eval_task.py 取第一个同名工具步的前提）");
        AgentResult.PlanStep step = result.plan().get(0);
        assertEquals("queryOrderDetail", step.tool());
        assertEquals("OK", step.status());
        assertEquals(2, step.attempt(), "attempt 按工具计、含失败的那一枪：反思重试成功读 2");
        assertTrue(step.reflection(), "这一步是反思重试，必须显式标出来");

        assertEquals(1.0d, reflectionCounter("triggered"));
        assertEquals(0.0d, reflectionCounter("exhausted"));
        assertTrue(traceHas(result, "reflection=queryOrderDetail attempt=2"),
                "trace 的反思行必须是**小写头**且带 attempt；实际 trace=" + result.trace());
        assertNull(result.fallbackReason());
    }

    @Test
    @DisplayName("不触发：一次就办成的回合 attempt=1、reflection=false，两条 counter 都不动、trace 无反思行")
    void nonReflectionRoundHasNoReflectionMarkers() {
        when(llm.complete(any())).thenReturn(
                toolCall("call-1", ToolName.QUERY_ORDER_DETAIL),
                LlmTypes.Reply.text("您的订单已发货"));
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("您的订单已发货"));
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK, "{\"status\":\"OK\",\"message\":\"订单已发货\"}",
                        false));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true))
                .run("我的订单90001到哪了", null, EventSink.NOOP);

        assertEquals(1, result.plan().size());
        assertEquals(1, result.plan().get(0).attempt());
        assertFalse(result.plan().get(0).reflection());
        assertEquals(0.0d, reflectionCounter("triggered"));
        assertEquals(0.0d, reflectionCounter("exhausted"));
        assertFalse(traceHas(result, "reflection="), "没反思就不许有反思行");
    }

    @Test
    @DisplayName("次数用尽：同工具第二次③失败 → counter exhausted +1、回合落 TOOL_UNAVAILABLE，plan[] 一条失败枪都没有")
    void secondOperationalFailureCountsExhausted() {
        when(llm.complete(any())).thenReturn(
                toolCall("call-1", ToolName.QUERY_ORDER_DETAIL),
                toolCall("call-2", ToolName.QUERY_ORDER_DETAIL));
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(outcome(ToolStatus.TIMEOUT, "业务系统响应超时"),
                        outcome(ToolStatus.UNAVAILABLE, "业务系统熔断已打开"));
        when(fallback.escalate(eq(FallbackReason.TOOL_UNAVAILABLE), anyString(), anyString()))
                .thenReturn(Optional.of("T-950"));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true))
                .run("我的订单90001到哪了", null, EventSink.NOOP);

        assertEquals(FallbackReason.TOOL_UNAVAILABLE, result.fallbackReason());
        assertEquals("T-950", result.ticketId());
        assertTrue(result.plan().isEmpty(), "两次都是失败枪：plan[] 不许出现任何一条");
        assertEquals(1.0d, reflectionCounter("triggered"), "只反思了一次");
        assertEquals(1.0d, reflectionCounter("exhausted"), "第二次③失败是配额用尽那一格");
        verify(bizMock, times(2)).call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any());
    }

    @Test
    @DisplayName("写工具③失败不反思，也不落进 triggered / exhausted 任何一格（票 139：两者皆无）")
    void writeToolFailureCountsNeitherOutcome() {
        when(llm.complete(any())).thenReturn(toolCall("call-1", ToolName.APPLY_REFUND));
        when(bizMock.call(eq(ToolName.APPLY_REFUND), anyMap(), any()))
                .thenReturn(outcome(ToolStatus.TIMEOUT, "业务系统响应超时"));
        when(fallback.escalate(eq(FallbackReason.TOOL_UNAVAILABLE), anyString(), anyString()))
                .thenReturn(Optional.of("T-951"));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_REFUND, "T1", true))
                .run("订单90001申请退款", null, EventSink.NOOP);

        assertEquals(FallbackReason.TOOL_UNAVAILABLE, result.fallbackReason());
        assertEquals(0.0d, reflectionCounter("triggered"));
        assertEquals(0.0d, reflectionCounter("exhausted"));
        assertFalse(traceHas(result, "reflection="));
    }

    @Test
    @DisplayName("反思后模型换了路：那笔反思印记作废——同一工具后来的另一次正常调用不许被打上 reflection")
    void reflectionMarkIsNotCarriedOverToAnUnrelatedCall() {
        // 第一枪 90001 超时 → 反思；第二枪模型换了工具（提示词明说允许），第三枪才回头查 QOD 的另一单
        when(llm.complete(any())).thenReturn(
                toolCall("call-1", ToolName.QUERY_ORDER_DETAIL, "90001"),
                toolCall("call-2", ToolName.QUERY_LOGISTICS, "90001"),
                toolCall("call-3", ToolName.QUERY_ORDER_DETAIL, "90002"),
                LlmTypes.Reply.text("两单都在路上"));
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("两单都在路上"));
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(outcome(ToolStatus.TIMEOUT, "业务系统响应超时"),
                        new BizMockClient.Outcome(ToolStatus.OK, "{\"status\":\"OK\",\"message\":\"已发货\"}", false));
        when(bizMock.call(eq(ToolName.QUERY_LOGISTICS), anyMap(), any()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK, "{\"status\":\"OK\",\"message\":\"运输中\"}",
                        false));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true))
                .run("90001 先看下，那个 90002 也一起查一下", null, EventSink.NOOP);

        assertEquals(2, result.plan().size());
        assertEquals("queryLogistics", result.plan().get(0).tool());
        assertFalse(result.plan().get(0).reflection());
        AgentResult.PlanStep secondOrder = result.plan().get(1);
        assertEquals("queryOrderDetail", secondOrder.tool());
        assertEquals(2, secondOrder.attempt(), "失败的那一枪仍算这一工具的第 1 枪");
        assertFalse(secondOrder.reflection(), "模型换了路，这笔反思印记必须作废——它不是同工具的反思重试");
        // 三态仍读作「触发」：counter 与 trace 两处载体都在（plan[] 的印记如实缺席）
        assertEquals(1.0d, reflectionCounter("triggered"));
        assertTrue(traceHas(result, "reflection=queryOrderDetail attempt=2"));
    }

    // ---------- 脚手架 ----------

    private AgentStateMachine machine(TriageResult triage) {
        TriageEngine triageEngine = mock(TriageEngine.class);
        when(triageEngine.triage(anyString(), any())).thenReturn(new TriageEngine.Outcome(triage, null));
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);
        HybridRetriever retriever = mock(HybridRetriever.class);
        when(retriever.retrieve(anyString(), anyString(), any(), any()))
                .thenReturn(HybridRetriever.Result.unavailable(7L));
        WriteBackPolicy policy = mock(WriteBackPolicy.class);
        when(policy.evaluate(any())).thenReturn(new WriteBackPolicy.Verdict(false, "reflection-obs-test"));
        SentimentGate gate = mock(SentimentGate.class);
        when(gate.evaluate(any())).thenReturn(new SentimentGate.Verdict(Emotion.CALM, 0.9d, false, "llm"));
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.agent()).thenReturn(new GatewayProperties.Agent(4, 2, Duration.ofMinutes(30), 4));
        when(properties.llm()).thenReturn(new GatewayProperties.Llm("dev", "http://127.0.0.1:1", "k", "qwen-plus",
                0.0d, Duration.ofSeconds(5), Duration.ofSeconds(5), 0L, null, null, null, null, 1024));
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.begin(any(), any(), any(), any(), any(), any()))
                .thenReturn(new IdempotencyService.Guard(false, false, null, "reflection-obs-token",
                        null, null, null));
        SessionStore store = mock(SessionStore.class);
        when(store.load(anyString(), anyString(), anyString())).thenReturn(SessionStore.Session.empty(CONVERSATION));
        when(store.appendTurn(any(), anyString(), anyString())).thenAnswer(call -> call.getArgument(0));
        TenantDomainRegistry domains = mock(TenantDomainRegistry.class);
        when(domains.resolve(anyString())).thenReturn(Domain.ECOMMERCE);
        return new AgentStateMachine(triageEngine, mock(CacheService.class), mock(SingleFlight.class), policy, epoch,
                domains, retriever, llm, new ToolDispatcher(bizMock, idempotency, registry), store,
                fallback, properties, mock(WriteBackPool.class), new PromptCatalog(), gate, new StyleService(),
                registry);
    }

    private static LlmTypes.Reply toolCall(String id, ToolName tool) {
        return toolCall(id, tool, "90001");
    }

    private static LlmTypes.Reply toolCall(String id, ToolName tool, String orderNo) {
        return new LlmTypes.Reply("", List.of(new LlmTypes.ToolCall(id, tool.apiName(), Map.of("orderNo", orderNo))),
                10, 2, null);
    }

    private static BizMockClient.Outcome outcome(ToolStatus status, String message) {
        return new BizMockClient.Outcome(status, "{\"status\":\"" + status.name() + "\",\"message\":\""
                + message + "\"}", status == ToolStatus.TIMEOUT || status == ToolStatus.UNAVAILABLE);
    }

    private double reflectionCounter(String outcome) {
        var counter = registry.find("shoppilot_reflection_total").tag("outcome", outcome).counter();
        return counter == null ? 0.0d : counter.count();
    }

    private static boolean traceHas(AgentResult result, String prefix) {
        return result.trace().stream()
                .anyMatch(step -> step.detail() != null && step.detail().startsWith(prefix));
    }
}
