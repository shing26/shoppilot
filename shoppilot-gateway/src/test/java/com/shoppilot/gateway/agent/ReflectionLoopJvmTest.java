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
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * 反思分支的 0 token 契约（round35 票 144；口径 = 票 138 的映射表与规则六条，依据 ADR 0069 §四）。
 *
 * <p>钉七格：①闸两格（NOT_FOUND / STATE_NOT_ALLOWED 原出口中止、不反思）、⓪受理与幂等回放各一格、
 * ③操作性失败三格（一次反思后成功 / 同工具第二次收口 / 重试撞闸不再反思）。另加三格覆盖票面的
 * 「挂点三处同规则」：写工具不反思、网关派生枪、槽位续办枪。
 *
 * <p>判据只判可枚举的三态与出口，不判"看起来聪明"：反思的存在与否靠**派发次数 / 规划次数 /
 * 下一轮 messages 里的失败事实**读出来，不依赖任何新观测字段（`attempt`/`reflection`/counter
 * 是票 145 的落地面）。
 */
class ReflectionLoopJvmTest {

    private static final String CONVERSATION = "conv-reflection-1";

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

    // ---------- ①闸：走原出口，不反思 ----------

    @Test
    @DisplayName("①闸 NOT_FOUND：归属拒绝走 plan-aborted 原出口，一次派发、不反思（票 144 七格之一）")
    void notFoundAbortsPlanWithoutReflection() {
        when(llm.complete(any())).thenReturn(toolCall("call-1", ToolName.QUERY_ORDER_DETAIL, "90001"));
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("这笔订单查不到，请核对订单号"));
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(outcome(ToolStatus.NOT_FOUND, "订单不存在"));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true))
                .run("我的订单90001到哪了", null, EventSink.NOOP);

        assertNull(result.fallbackReason(), "闸拦下的调用不许落降级工单：它走的是 plan-aborted 原出口");
        assertEquals(List.of("queryOrderDetail"), toolsInPlan(result));
        assertEquals("NOT_FOUND", result.plan().get(0).status());
        assertTrue(traceHas(result, "plan-aborted"), "①闸的形态必须原样保住（gold 的 PLN-ABORT-* 靠它）");
        assertEquals(1.0d, stepsCounter("aborted"));
        verify(bizMock, times(1)).call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any());
        verify(llm, times(1)).complete(any());
        verify(fallback, never()).escalate(any(), any(), any());
    }

    @Test
    @DisplayName("①闸 STATE_NOT_ALLOWED：终态拒绝同样原出口中止，不反思（票 144 七格之一）")
    void stateNotAllowedAbortsPlanWithoutReflection() {
        when(llm.complete(any())).thenReturn(toolCall("call-1", ToolName.APPLY_REFUND, "90001"));
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("这单已发货，不能再退了"));
        when(bizMock.call(eq(ToolName.APPLY_REFUND), anyMap(), any()))
                .thenReturn(outcome(ToolStatus.STATE_NOT_ALLOWED, "订单已发货，不允许退款"));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_REFUND, "T1", true))
                .run("订单90001申请退款", null, EventSink.NOOP);

        assertNull(result.fallbackReason());
        assertEquals(List.of("applyRefund"), toolsInPlan(result));
        assertEquals("STATE_NOT_ALLOWED", result.plan().get(0).status());
        assertTrue(traceHas(result, "plan-aborted"));
        verify(bizMock, times(1)).call(eq(ToolName.APPLY_REFUND), anyMap(), any());
        verify(llm, times(1)).complete(any());
    }

    // ---------- ⓪非失败：受理与幂等回放都不反思 ----------

    @Test
    @DisplayName("⓪受理（PENDING_APPROVAL）：受理即收尾，不打第二跳模型、不反思（票 144 七格之一）")
    void pendingApprovalNeverReflects() {
        when(llm.complete(any())).thenReturn(toolCall("call-1", ToolName.APPLY_REFUND, "90001"));
        when(bizMock.call(eq(ToolName.APPLY_REFUND), anyMap(), any()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.PENDING_APPROVAL,
                        "{\"status\":\"PENDING_APPROVAL\",\"message\":\"已受理，等待人工审核\"}", false));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_REFUND, "T1", true))
                .run("订单90001申请退款", null, EventSink.NOOP);

        assertEquals("PENDING_APPROVAL", result.plan().get(0).status());
        assertNull(result.fallbackReason(), "受理不是失败，不走降级出口（ADR 0047）");
        verify(llm, times(1)).complete(any());
        verify(llm, never()).stream(any(), any());
        verify(bizMock, times(1)).call(eq(ToolName.APPLY_REFUND), anyMap(), any());
        verify(fallback, never()).escalate(any(), any(), any());
    }

    @Test
    @DisplayName("①闸幂等回放（IDEMPOTENT_REPLAY）：回放结果回填、循环继续，不重执行也不反思")
    void idempotentReplayContinuesWithoutReflection() {
        when(llm.complete(any())).thenReturn(toolCall("call-1", ToolName.APPLY_REFUND, "90001"),
                LlmTypes.Reply.text("这笔退款刚才已经提交过了"));
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("这笔退款刚才已经提交过了"));
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.begin(any(), any(), any(), any(), any(), any()))
                .thenReturn(new IdempotencyService.Guard(true, false, "{\"status\":\"OK\",\"refundId\":\"RF-1\"}",
                        "tok-1", null, null, null));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_REFUND, "T1", true), idempotency, emptyStore())
                .run("订单90001申请退款", null, EventSink.NOOP);

        assertEquals("IDEMPOTENT_REPLAY", result.plan().get(0).status());
        assertNull(result.fallbackReason(), "回放不是降级：落工单会污染「降级 9」的口径（ADR 0046）");
        verify(bizMock, never()).call(any(), anyMap(), any());
        // 工具轮 + 收尾轮：没有多出来的"反思轮"
        verify(llm, times(2)).complete(any());
        verify(fallback, never()).escalate(any(), any(), any());
    }

    // ---------- ③操作性失败：一次反思 ----------

    @Test
    @DisplayName("③读工具 TIMEOUT：一次反思后成功——失败枪不进 plan[]，成功枪进（票 144 七格之一）")
    void operationalTimeoutReflectsOnceThenSucceeds() {
        when(llm.complete(any())).thenReturn(
                toolCall("call-1", ToolName.QUERY_ORDER_DETAIL, "90001"),
                toolCall("call-2", ToolName.QUERY_ORDER_DETAIL, "90001"),
                LlmTypes.Reply.text("您的订单已发货"));
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("您的订单已发货"));
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(outcome(ToolStatus.TIMEOUT, "业务系统响应超时"),
                        new BizMockClient.Outcome(ToolStatus.OK, "{\"status\":\"OK\",\"message\":\"订单已发货\"}", false));

        ArgumentCaptor<LlmTypes.Request> planRounds = ArgumentCaptor.forClass(LlmTypes.Request.class);
        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true))
                .run("我的订单90001到哪了", null, EventSink.NOOP);

        verify(bizMock, times(2)).call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any());
        verify(llm, times(3)).complete(planRounds.capture());
        assertEquals(List.of("queryOrderDetail"), toolsInPlan(result), "失败的那一枪不进 plan[]");
        assertEquals("OK", result.plan().get(0).status());
        assertNull(result.fallbackReason());
        verify(fallback, never()).escalate(any(), any(), any());

        // 失败事实 + 失败上下文必须真的回填给下一轮，否则"反思"只是换个名字的重试
        List<LlmTypes.Message> afterReflection = planRounds.getAllValues().get(1).messages();
        assertTrue(afterReflection.stream().anyMatch(message -> "tool".equals(message.role())
                        && message.content() != null && message.content().contains("TIMEOUT")),
                "失败事实要作为 tool 消息回填（与发起调用的那条 assistant 消息配对）");
        assertTrue(afterReflection.stream().anyMatch(message -> "user".equals(message.role())
                        && message.content() != null && message.content().contains("没有拿到业务结果")),
                "失败上下文说明要跟着一起回填，模型才知道这一次为什么重来");
    }

    @Test
    @DisplayName("③同工具第二次③失败：按原语义收口（TOOL_UNAVAILABLE + 工单），不第二次反思")
    void secondOperationalFailureOfSameToolFallsBack() {
        when(llm.complete(any())).thenReturn(
                toolCall("call-1", ToolName.QUERY_ORDER_DETAIL, "90001"),
                toolCall("call-2", ToolName.QUERY_ORDER_DETAIL, "90001"));
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(outcome(ToolStatus.TIMEOUT, "业务系统响应超时"),
                        outcome(ToolStatus.UNAVAILABLE, "业务系统熔断已打开"));
        when(fallback.escalate(eq(FallbackReason.TOOL_UNAVAILABLE), anyString(), anyString()))
                .thenReturn(Optional.of("T-900"));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true))
                .run("我的订单90001到哪了", null, EventSink.NOOP);

        assertEquals(FallbackReason.TOOL_UNAVAILABLE, result.fallbackReason());
        assertEquals("T-900", result.ticketId());
        // 配额按 ToolName 合计：第二次无论哪种③失败都不再反思，也不再多打一次规划
        verify(bizMock, times(2)).call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any());
        verify(llm, times(2)).complete(any());
        verify(fallback).escalate(eq(FallbackReason.TOOL_UNAVAILABLE), anyString(), anyString());
    }

    @Test
    @DisplayName("R14-A：反思后模型不再调工具、直接作答 → 失败不许被吞，强制 TOOL_UNAVAILABLE + 工单")
    void unansweredReflectionFallsBackInsteadOfAnswering() {
        when(llm.complete(any())).thenReturn(
                toolCall("call-1", ToolName.QUERY_ORDER_DETAIL, "90001"),
                LlmTypes.Reply.text("系统暂时查不到，请联系人工"));
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("系统暂时查不到，请联系人工"));
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(outcome(ToolStatus.TIMEOUT, "业务系统响应超时"));
        when(fallback.escalate(eq(FallbackReason.TOOL_UNAVAILABLE), anyString(), anyString()))
                .thenReturn(Optional.of("T-902"));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true))
                .run("我的订单90001到哪了", null, EventSink.NOOP);

        assertEquals(FallbackReason.TOOL_UNAVAILABLE, result.fallbackReason(),
                "失败没有被成功重试解决，模型正文不能把它写成普通回答");
        assertEquals("T-902", result.ticketId());
        verify(bizMock, times(1)).call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any());
        verify(llm, times(2)).complete(any());
        // 不许进 REPLY：一旦进收尾轮，这段正文就会以 degraded=false 的身份发给买家
        verify(llm, never()).stream(any(), any());
        verify(fallback).escalate(eq(FallbackReason.TOOL_UNAVAILABLE), anyString(), anyString());
    }

    @Test
    @DisplayName("R14-A 正对照：失败后换工具成功（反思允许的补救）→ 正常作答，不误伤为降级")
    void successfulWorkaroundAfterFailureKeepsTheAnswer() {
        when(llm.complete(any())).thenReturn(
                toolCall("call-1", ToolName.QUERY_LOGISTICS, "90001"),
                toolCall("call-2", ToolName.QUERY_ORDER_DETAIL, "90001"),
                LlmTypes.Reply.text("物流查不到，但订单显示已发货"));
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("物流查不到，但订单显示已发货"));
        when(bizMock.call(eq(ToolName.QUERY_LOGISTICS), anyMap(), any()))
                .thenReturn(outcome(ToolStatus.TIMEOUT, "业务系统响应超时"));
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK,
                        "{\"status\":\"OK\",\"message\":\"订单已发货\"}", false));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true))
                .run("我的订单90001到哪了", null, EventSink.NOOP);

        assertNull(result.fallbackReason(), "换工具成功属于提示词明说允许的补救，不该落降级");
        assertEquals(List.of("queryOrderDetail"), toolsInPlan(result));
        verify(fallback, never()).escalate(any(), any(), any());
    }

    @Test
    @DisplayName("③反思重试撞闸：走该闸原出口（plan-aborted），不再第二次反思（票 144 七格之一）")
    void reflectionRetryHittingGateAbortsWithoutSecondReflection() {
        when(llm.complete(any())).thenReturn(
                toolCall("call-1", ToolName.QUERY_ORDER_DETAIL, "90001"),
                toolCall("call-2", ToolName.QUERY_LOGISTICS, "90001"));
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("这单的物流暂时查不到"));
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(outcome(ToolStatus.TIMEOUT, "业务系统响应超时"));
        when(bizMock.call(eq(ToolName.QUERY_LOGISTICS), anyMap(), any()))
                .thenReturn(outcome(ToolStatus.NOT_FOUND, "订单不存在"));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true))
                .run("我的订单90001到哪了", null, EventSink.NOOP);

        // 反思后的重试换了工具、撞上归属拒绝：闸判定先于反思，走闸的原出口，且不许再反思（第三枪）
        verify(bizMock, times(2)).call(any(), anyMap(), any());
        verify(llm, times(2)).complete(any());
        assertEquals(List.of("queryLogistics"), toolsInPlan(result));
        assertEquals("NOT_FOUND", result.plan().get(0).status());
        assertTrue(traceHas(result, "plan-aborted"));
        assertNull(result.fallbackReason());
        verify(fallback, never()).escalate(any(), any(), any());
    }

    // ---------- 写工具与另两处挂点（票面「三处同规则」） ----------

    @Test
    @DisplayName("写工具③失败不反思：applyRefund TIMEOUT 照旧 fallback + 工单")
    void writeToolOperationalFailureNeverReflects() {
        when(llm.complete(any())).thenReturn(toolCall("call-1", ToolName.APPLY_REFUND, "90001"));
        when(bizMock.call(eq(ToolName.APPLY_REFUND), anyMap(), any()))
                .thenReturn(outcome(ToolStatus.TIMEOUT, "业务系统响应超时"));
        when(fallback.escalate(eq(FallbackReason.TOOL_UNAVAILABLE), anyString(), anyString()))
                .thenReturn(Optional.of("T-901"));

        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_REFUND, "T1", true))
                .run("订单90001申请退款", null, EventSink.NOOP);

        assertEquals(FallbackReason.TOOL_UNAVAILABLE, result.fallbackReason());
        assertEquals("T-901", result.ticketId());
        // 结果不明时自动重试资金动作风险大于收益：一次派发、一次规划，不反思
        verify(bizMock, times(1)).call(eq(ToolName.APPLY_REFUND), anyMap(), any());
        verify(llm, times(1)).complete(any());
    }

    @Test
    @DisplayName("网关派生枪③失败：同规则重试一次（占轮），失败事实与重试结果都摆给收尾轮")
    void gatewayDerivedReadFailureReflectsOnce() {
        // 模型只说了话、没发 function call：走网关派生那条路（ACTION_ORDER → queryOrderDetail）
        when(llm.complete(any())).thenReturn(LlmTypes.Reply.text("我先看一下订单状态"));
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("您的订单已发货"));
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(outcome(ToolStatus.TIMEOUT, "业务系统响应超时"),
                        new BizMockClient.Outcome(ToolStatus.OK, "{\"status\":\"OK\",\"message\":\"订单已发货\"}", false));

        ArgumentCaptor<LlmTypes.Request> replyRound = ArgumentCaptor.forClass(LlmTypes.Request.class);
        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_ORDER, "T1", true))
                .run("帮我查一下订单90001现在的状态", null, EventSink.NOOP);

        verify(bizMock, times(2)).call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any());
        assertEquals(List.of("queryOrderDetail"), toolsInPlan(result));
        assertEquals("OK", result.plan().get(0).status());
        assertNull(result.fallbackReason());
        verify(llm).stream(replyRound.capture(), any());
        assertTrue(replyRound.getValue().messages().stream().anyMatch(message -> "tool".equals(message.role())
                        && message.content() != null && message.content().contains("TIMEOUT")),
                "派生枪的失败事实同样要回填");
        assertTrue(replyRound.getValue().messages().stream().anyMatch(message -> "user".equals(message.role())
                        && message.content() != null && message.content().contains("没有拿到业务结果")),
                "失败上下文说明同样要回填");
    }

    @Test
    @DisplayName("槽位续办枪③失败：同规则重试一次，两次事实都进收尾轮的【业务系统返回】")
    void resumedReadFailureReflectsOnce() {
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("物流已更新，正在派送中"));
        when(bizMock.call(eq(ToolName.QUERY_LOGISTICS), anyMap(), any()))
                .thenReturn(outcome(ToolStatus.TIMEOUT, "业务系统响应超时"),
                        new BizMockClient.Outcome(ToolStatus.OK,
                                "{\"status\":\"OK\",\"message\":\"物流已更新\"}", false));
        SessionStore store = mock(SessionStore.class);
        when(store.load(anyString(), anyString(), anyString())).thenReturn(new SessionStore.Session(
                CONVERSATION, List.of(new SessionStore.Turn("user", "帮我查下物流轨迹")),
                ToolName.QUERY_LOGISTICS.apiName(), new LinkedHashMap<>(), 0));
        when(store.appendTurn(any(), anyString(), anyString())).thenAnswer(call -> call.getArgument(0));

        ArgumentCaptor<LlmTypes.Request> replyRound = ArgumentCaptor.forClass(LlmTypes.Request.class);
        AgentResult result = machine(TriageResult.dynamic(Intent.ACTION_LOGISTICS, "T1", true),
                idempotencyProceed(), store).run("90001 这单到哪了", null, EventSink.NOOP);

        verify(bizMock, times(2)).call(eq(ToolName.QUERY_LOGISTICS), anyMap(), any());
        assertNull(result.fallbackReason());
        verify(llm).stream(replyRound.capture(), any());
        assertTrue(replyRound.getValue().messages().stream().anyMatch(message -> "user".equals(message.role())
                        && message.content() != null && message.content().contains("【第一次调用】")
                        && message.content().contains("物流已更新")),
                "续办枪没有下一轮规划：失败事实与重试结果一起进收尾轮的【业务系统返回】");
    }

    // ---------- 脚手架 ----------

    private AgentStateMachine machine(TriageResult triage) {
        return machine(triage, idempotencyProceed(), emptyStore());
    }

    private AgentStateMachine machine(TriageResult triage, IdempotencyService idempotency, SessionStore store) {
        TriageEngine triageEngine = mock(TriageEngine.class);
        when(triageEngine.triage(anyString(), any(), any())).thenReturn(new TriageEngine.Outcome(triage, null));
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);
        HybridRetriever retriever = mock(HybridRetriever.class);
        when(retriever.retrieve(anyString(), anyString(), any(), any()))
                .thenReturn(HybridRetriever.Result.unavailable(7L));
        WriteBackPolicy policy = mock(WriteBackPolicy.class);
        when(policy.evaluate(any())).thenReturn(new WriteBackPolicy.Verdict(false, "reflection-test"));
        SentimentGate gate = mock(SentimentGate.class);
        when(gate.evaluate(any())).thenReturn(new SentimentGate.Verdict(Emotion.CALM, 0.9d, false, "llm"));
        GatewayProperties properties = mock(GatewayProperties.class);
        // 生产默认工具轮 = 4（round35 票 143）；反思占的就是这条预算
        when(properties.agent()).thenReturn(new GatewayProperties.Agent(4, 2, Duration.ofMinutes(30), 4));
        when(properties.llm()).thenReturn(new GatewayProperties.Llm("dev", "http://127.0.0.1:1", "k", "qwen-plus",
                0.0d, Duration.ofSeconds(5), Duration.ofSeconds(5), 0L, null, null, null, null, 1024));
        return new AgentStateMachine(triageEngine, mock(CacheService.class), mock(SingleFlight.class), policy, epoch,
                ecommerceDomains(), retriever, llm, new ToolDispatcher(bizMock, idempotency, registry), store,
                fallback, properties, mock(WriteBackPool.class), new PromptCatalog(), gate, new StyleService(),
                registry);
    }

    private static SessionStore emptyStore() {
        SessionStore store = mock(SessionStore.class);
        when(store.load(anyString(), anyString(), anyString())).thenReturn(SessionStore.Session.empty(CONVERSATION));
        when(store.appendTurn(any(), anyString(), anyString())).thenAnswer(call -> call.getArgument(0));
        return store;
    }

    /** 幂等层放行：不重复、不忙锁、无缓存结果（本类测的是反思语义，不是幂等）。 */
    private static IdempotencyService idempotencyProceed() {
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.begin(any(), any(), any(), any(), any(), any()))
                .thenReturn(new IdempotencyService.Guard(false, false, null, "reflection-test-token",
                        null, null, null));
        return idempotency;
    }

    /** round33：测试里的租户按电商登记，工具裁剪与旧行为一致（ADR 0067）。 */
    private static TenantDomainRegistry ecommerceDomains() {
        TenantDomainRegistry domains = mock(TenantDomainRegistry.class);
        when(domains.resolve(anyString())).thenReturn(Domain.ECOMMERCE);
        return domains;
    }

    private static LlmTypes.Reply toolCall(String id, ToolName tool, String orderNo) {
        return new LlmTypes.Reply("", List.of(new LlmTypes.ToolCall(id, tool.apiName(), Map.of("orderNo", orderNo))),
                10, 2, null);
    }

    private static BizMockClient.Outcome outcome(ToolStatus status, String message) {
        return new BizMockClient.Outcome(status, "{\"status\":\"" + status.name() + "\",\"message\":\""
                + message + "\"}", status == ToolStatus.TIMEOUT || status == ToolStatus.UNAVAILABLE);
    }

    private static List<String> toolsInPlan(AgentResult result) {
        return result.plan().stream().map(AgentResult.PlanStep::tool).toList();
    }

    private static boolean traceHas(AgentResult result, String prefix) {
        return result.trace().stream().anyMatch(step -> step.detail() != null && step.detail().startsWith(prefix));
    }

    private double stepsCounter(String steps) {
        var counter = registry.find("shoppilot_plan_steps_total").tag("steps", steps).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
