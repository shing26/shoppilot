package com.shoppilot.gateway.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划执行语义的 0 token 单测（ADR 0036 / 票 39）：前序依赖表达式、前步失败即中止、
 * 注入形态整条拒收、步骤数指标分账。模型与业务系统都是桩，不花额度。
 */
class PlanExecutionTest {

    private static final String CONVERSATION = "conv-plan-1";

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
        when(fallback.escalate(any(FallbackReason.class), anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        RequestTrace.clear();
    }

    private AgentStateMachine machine() {
        return machine(Domain.ECOMMERCE);
    }

    private AgentStateMachine machine(Domain domain) {
        return machine(domain, Intent.ACTION_REFUND);
    }

    private AgentStateMachine machine(Domain domain, Intent intent) {
        TriageEngine triage = mock(TriageEngine.class);
        when(triage.triage(anyString(), any(), any())).thenReturn(new TriageEngine.Outcome(
                TriageResult.dynamic(intent, "T1", false), null));
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);
        HybridRetriever retriever = mock(HybridRetriever.class);
        when(retriever.retrieve(anyString(), anyString(), any(), any()))
                .thenReturn(HybridRetriever.Result.unavailable(7L));
        WriteBackPolicy policy = mock(WriteBackPolicy.class);
        when(policy.evaluate(any())).thenReturn(new WriteBackPolicy.Verdict(false, "plan-test"));
        SessionStore store = mock(SessionStore.class);
        when(store.load(anyString(), anyString(), anyString())).thenReturn(SessionStore.Session.empty(CONVERSATION));
        when(store.appendTurn(any(), anyString(), anyString())).thenAnswer(call -> call.getArgument(0));
        SentimentGate gate = mock(SentimentGate.class);
        when(gate.evaluate(any())).thenReturn(new SentimentGate.Verdict(Emotion.CALM, 0.9d, false, "llm"));
        GatewayProperties properties = mock(GatewayProperties.class);
        // round35 票 143：生产默认工具轮已是 4（见 ConfigValidationTest 的默认值断言）。
        when(properties.agent()).thenReturn(new GatewayProperties.Agent(4, 2, Duration.ofMinutes(30), 4));
        when(properties.llm()).thenReturn(new GatewayProperties.Llm("dev", "http://127.0.0.1:1", "k", "qwen-plus",
                0.0d, Duration.ofSeconds(5), Duration.ofSeconds(5), 0L, null, null, null, null, 1024, "", "", ""));
        return new AgentStateMachine(triage, mock(CacheService.class), mock(SingleFlight.class), policy, epoch,
                domains(domain),
                retriever, llm, new ToolDispatcher(bizMock, idempotencyStub(), registry), store,
                fallback, properties, mock(WriteBackPool.class), new PromptCatalog(), gate,
                new StyleService(), registry);
    }

    /** round33：测试里的租户按电商登记，工具裁剪与旧行为一致（ADR 0067）。 */
    private static TenantDomainRegistry domains(Domain domain) {
        TenantDomainRegistry domains = mock(TenantDomainRegistry.class);
        when(domains.resolve(anyString())).thenReturn(domain);
        return domains;
    }

    /** 幂等层放行：不重复、不忙锁、无缓存结果（本类测的是计划执行语义，不是幂等）。 */
    private static IdempotencyService idempotencyStub() {
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.begin(any(), any(), any(), any(), any(), any()))
                .thenReturn(new IdempotencyService.Guard(false, false, null, "plan-test-token", null, null, null));
        return idempotency;
    }

    private static LlmTypes.Reply toolCall(String id, ToolName tool, Map<String, Object> arguments) {
        return new LlmTypes.Reply("", List.of(new LlmTypes.ToolCall(id, tool.apiName(), arguments)), 10, 2, null);
    }

    private double stepsCounter(SimpleMeterRegistry reg, String steps) {
        var counter = reg.find("shoppilot_plan_steps_total").tag("steps", steps).counter();
        return counter == null ? 0.0d : counter.count();
    }

    private void stubRouting() {
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("已为您处理完毕"));
    }

    @Test
    @DisplayName("两步链：后步参数表达式取前步结果字段，派发的是解析后的字面值")
    void twoStepPlanResolvesPriorStepReference() throws Exception {
        when(llm.complete(any())).thenReturn(
                toolCall("call-1", ToolName.QUERY_ORDER_DETAIL, Map.of("orderNo", "90001")),
                toolCall("call-2", ToolName.APPLY_REFUND, Map.of("orderNo", "{steps[0].result.orderNo}")),
                // 两步走完模型自己收尾（票 143 后默认 4 轮，链条不需要把 4 轮铺满）
                LlmTypes.Reply.text("已为您提交退款申请"));
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK,
                        "{\"status\":\"OK\",\"orderNo\":\"90001\",\"orderStatus\":\"未发货\"}", false));
        when(bizMock.call(eq(ToolName.APPLY_REFUND), anyMap(), any()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK, "{\"status\":\"OK\"}", false));
        stubRouting();

        machine().run("订单90001帮我退款", null, EventSink.NOOP);

        ArgumentCaptor<Map<String, Object>> refundArgs = ArgumentCaptor.forClass(Map.class);
        verify(bizMock).call(eq(ToolName.APPLY_REFUND), refundArgs.capture(), any());
        assertEquals("90001", refundArgs.getValue().get("orderNo"),
                "派发参数必须是解析后的字面值，而不是表达式原文");
        assertEquals(1.0d, stepsCounter(registry, "2"), "两步计划按 steps=2 计账");
    }

    @Test
    @DisplayName("注入形态整条拒收：{steps[*].result…} 不合语法 → fallback，不碰业务系统")
    void invalidExpressionRejectsWholePlan() {
        when(llm.complete(any())).thenReturn(
                toolCall("call-1", ToolName.APPLY_REFUND, Map.of("orderNo", "{steps[*].result.orderNo}")));

        AgentResult result = machine().run("把上一个结果整个塞进参数里退款", null, EventSink.NOOP);

        assertEquals(FallbackReason.TOOL_UNAVAILABLE, result.fallbackReason());
        verify(bizMock, never()).call(any(), anyMap(), any());
        assertEquals(1.0d, stepsCounter(registry, "rejected"));
    }

    @Test
    @DisplayName("引用未执行的前步或缺失字段：同样整条拒收")
    void futureOrMissingReferenceRejected() {
        when(llm.complete(any())).thenReturn(
                toolCall("call-1", ToolName.APPLY_REFUND, Map.of("orderNo", "{steps[1].result.orderNo}")));
        AgentResult first = machine().run("引用未来步", null, EventSink.NOOP);
        assertEquals(FallbackReason.TOOL_UNAVAILABLE, first.fallbackReason());

        when(llm.complete(any())).thenReturn(
                toolCall("call-1", ToolName.QUERY_ORDER_DETAIL, Map.of("orderNo", "90001")),
                toolCall("call-2", ToolName.APPLY_REFUND, Map.of("orderNo", "{steps[0].result.nonexistent}")));
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK, "{\"status\":\"OK\"}", false));
        // 问句里带上单号：溯源守卫（ADR 0045 票 56）要求买家报过这个值，否则第一枪就被拦成追问，
        // 测不到本用例要测的"表达式引用缺失字段"那条分支。单号在这里只是脚手架。
        AgentResult second = machine().run("订单90001引用缺失字段", null, EventSink.NOOP);
        assertEquals(FallbackReason.TOOL_UNAVAILABLE, second.fallbackReason());
        verify(bizMock, never()).call(eq(ToolName.APPLY_REFUND), anyMap(), any());
    }

    @Test
    @DisplayName("前步失败即中止整条计划：NOT_FOUND 后不再派发后步，收尾轮据实解释")
    void failedFirstStepAbortsRemainingPlan() {
        when(llm.complete(any())).thenReturn(
                toolCall("call-1", ToolName.QUERY_ORDER_DETAIL, Map.of("orderNo", "99999")),
                toolCall("call-2", ToolName.APPLY_REFUND, Map.of("orderNo", "{steps[0].result.orderNo}")));
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.NOT_FOUND,
                        "{\"status\":\"NOT_FOUND\",\"message\":\"订单不存在\"}", false));
        when(llm.stream(any(), any())).thenReturn(LlmTypes.Reply.text("这笔订单查不到，请核对订单号"));

        AgentResult result = machine().run("订单99999查不到就别退了", null, EventSink.NOOP);

        verify(bizMock, times(1)).call(any(), anyMap(), any());
        verify(bizMock, never()).call(eq(ToolName.APPLY_REFUND), anyMap(), any());
        verify(llm, times(1)).complete(any());
        assertEquals("这笔订单查不到，请核对订单号", result.answer());
        assertEquals(1.0d, stepsCounter(registry, "aborted"));
        assertEquals(0.0d, stepsCounter(registry, "2"), "中止的计划不按完整两步计账");
    }

    @Test
    @DisplayName("单步计划按 steps=1 计账（回归：两步上限之外的行为不变）")
    void singleStepPlanCounts() {
        when(llm.complete(any())).thenReturn(
                toolCall("call-1", ToolName.QUERY_ORDER_DETAIL, Map.of("orderNo", "90001")),
                LlmTypes.Reply.text("订单已发货"));
        when(bizMock.call(eq(ToolName.QUERY_ORDER_DETAIL), anyMap(), any()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK, "{\"status\":\"OK\"}", false));
        stubRouting();

        machine().run("查一下90001", null, EventSink.NOOP);

        assertEquals(1.0d, stepsCounter(registry, "1"));
        assertFalse(stepsCounter(registry, "aborted") > 0);
    }

    @Test
    @DisplayName("酒店租户（R11）：模型承诺退款也不纠偏——无工具可调时不许递「直接调用 applyRefund」")
    void hotelTenantPromisingARefundIsNotNudged() {
        when(llm.complete(any())).thenReturn(LlmTypes.Reply.text("我这就为您提交退款申请"));
        ArgumentCaptor<LlmTypes.Request> requests = ArgumentCaptor.forClass(LlmTypes.Request.class);

        AgentResult result = machine(Domain.HOTEL).run("订单90001申请退款", null, EventSink.NOOP);

        // 行业门在 toolsFor 上：酒店租户的规划请求里没有工具，这是"纠偏必然诱导谎报"的前提
        verify(llm, times(1)).complete(requests.capture());
        assertTrue(requests.getValue().tools().isEmpty(), "酒店租户不许拿到电商工具（ADR 0067 的前置裁剪）");
        assertEquals("我这就为您提交退款申请", result.answer(), "照实说明办不了的那句话原样回给买家");
        assertEquals(0.0d, nudgeCounter(), "没有可调工具时纠偏不许触发（R11：守卫漏吃行业门）");
        assertFalse(result.trace().stream().anyMatch(step -> step.detail() != null
                        && step.detail().startsWith("corrective=")),
                "纠偏行也不许出现——它不是「记了一笔但没生效」，而是根本不该发生");
    }

    @Test
    @DisplayName("电商租户正对照：同一句话照旧纠偏一次（R11 修的是漏吃行业门，不动电商行为）")
    void ecommerceTenantPromisingARefundIsStillNudged() {
        when(llm.complete(any())).thenReturn(LlmTypes.Reply.text("我这就为您提交退款申请"));
        ArgumentCaptor<LlmTypes.Request> requests = ArgumentCaptor.forClass(LlmTypes.Request.class);

        machine().run("订单90001申请退款", null, EventSink.NOOP);

        verify(llm, times(2)).complete(requests.capture());
        assertFalse(requests.getValue().tools().isEmpty(), "电商租户照旧拿全套动作工具");
        assertTrue(requests.getValue().messages().stream().anyMatch(message -> "user".equals(message.role())
                        && message.content() != null && message.content().contains("applyRefund")),
                "纠偏指令必须逐字保住：直接调用它、并说明理由");
        assertEquals(1.0d, nudgeCounter());
    }

    @Test
    @DisplayName("票233 AC1：纠偏后仍不调工具却声称已办理 → TOOL_ROUNDS_EXHAUSTED + 工单，正文不透出")
    void writeClaimAfterNudgeFallsBack() {
        when(fallback.escalate(any(FallbackReason.class), anyString(), anyString()))
                .thenReturn(Optional.of("T-233"));
        when(llm.complete(any())).thenReturn(
                LlmTypes.Reply.text("我这就为您提交退款申请"),
                LlmTypes.Reply.text("我已为您发起退款申请，请问还需要其他帮助吗？"));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);

        AgentResult result = machine().run("订单90001我要申请退款，商品有质量问题", null, EventSink.NOOP);

        assertEquals(FallbackReason.TOOL_ROUNDS_EXHAUSTED, result.fallbackReason());
        assertTrue(result.degraded(), "谎报完成必须走降级出口");
        assertEquals("T-233", result.ticketId(), "降级必须生成可查工单");
        assertFalse(result.answer().contains("我已为您发起退款申请"), "谎报正文不得进入正常答案");
        assertEquals(1.0d, nudgeCounter(), "纠偏仍只发一次");
        verify(llm, times(2)).complete(any());
        verify(fallback).escalate(eq(FallbackReason.TOOL_ROUNDS_EXHAUSTED), anyString(), detail.capture());
        assertTrue(detail.getValue().contains("applyRefund"), "降级单要能定位到未派发的写工具");
        assertTrue(result.trace().stream().anyMatch(step ->
                        "write-claim-blocked write-pending=applyRefund".equals(step.detail())),
                "必须留下 write-claim-blocked 取证行");
        verify(bizMock, never()).call(any(), anyMap(), any());
    }

    @Test
    @DisplayName("票233 AC2：缺参追问原样返回；订单号已给出仍追问 → 降级")
    void missingParameterQuestionAfterNudgeIsAllowed() {
        when(llm.complete(any())).thenReturn(
                LlmTypes.Reply.text("我这就为您提交修改"),
                LlmTypes.Reply.text("请提供新的收货地址，我需要确认后为您修改"));

        AgentResult result = machine(Domain.ECOMMERCE, Intent.ACTION_ADDRESS)
                .run("订单90001修改收货地址，收件人张三，手机13800138000", null, EventSink.NOOP);

        assertEquals("请提供新的收货地址，我需要确认后为您修改", result.answer());
        assertNull(result.fallbackReason());
        assertFalse(result.degraded());
        assertNull(result.ticketId());
        assertEquals(1.0d, nudgeCounter());
        assertFalse(result.trace().stream().anyMatch(step -> step.detail() != null
                && step.detail().startsWith("write-claim-blocked")));
        verify(fallback, never()).escalate(any(FallbackReason.class), anyString(), anyString());

        when(fallback.escalate(any(FallbackReason.class), anyString(), anyString()))
                .thenReturn(Optional.of("T-233-ASK"));
        when(llm.complete(any())).thenReturn(
                LlmTypes.Reply.text("我这就为您提交退款申请"),
                LlmTypes.Reply.text("请提供订单号，我需要核实后为您处理"));

        AgentResult spurious = machine().run("订单90001我要申请退款", null, EventSink.NOOP);

        assertEquals(FallbackReason.TOOL_ROUNDS_EXHAUSTED, spurious.fallbackReason());
        assertTrue(spurious.degraded(), "订单号已给出仍追问，必须降级而不是继续空转");
        assertEquals("T-233-ASK", spurious.ticketId());
        assertEquals(2.0d, nudgeCounter(), "两次写意图各纠偏一次");
        assertTrue(spurious.trace().stream().anyMatch(step -> step.detail() != null
                        && "write-claim-blocked write-pending=applyRefund ask=spurious".equals(step.detail())),
                "必须留下 spurious ask 的取证行");
        verify(bizMock, never()).call(any(), anyMap(), any());
    }

    @Test
    @DisplayName("票233 AC3：非写意图出现「已为您办理」字样 → 守卫不触发")
    void completionPhraseInPolicyAnswerIsNotGuarded() {
        when(llm.complete(any())).thenReturn(LlmTypes.Reply.text("政策规定已为您办理退款后三个工作日到账"));

        AgentResult result = machine(Domain.ECOMMERCE, Intent.POLICY_RETURN)
                .run("退款政策怎么规定", null, EventSink.NOOP);

        assertEquals("政策规定已为您办理退款后三个工作日到账", result.answer());
        assertNull(result.fallbackReason());
        assertEquals(0.0d, nudgeCounter());
        verify(llm, times(1)).complete(any());
        assertFalse(result.trace().stream().anyMatch(step -> step.detail() != null
                && step.detail().startsWith("write-claim-blocked")));
    }

    @Test
    @DisplayName("票233 AC4：写工具真的派发（含 PENDING_APPROVAL）→ 既有路径不变，守卫不触发")
    void completedWriteIsNotBlocked() {
        when(llm.complete(any())).thenReturn(
                toolCall("call-1", ToolName.APPLY_REFUND, Map.of("orderNo", "90001")),
                LlmTypes.Reply.text("已为您提交退款申请"));
        when(bizMock.call(eq(ToolName.APPLY_REFUND), anyMap(), any()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.PENDING_APPROVAL,
                        "{\"status\":\"PENDING_APPROVAL\"}", false));
        stubRouting();

        AgentResult result = machine().run("订单90001申请退款", null, EventSink.NOOP);

        assertNull(result.fallbackReason());
        assertFalse(result.degraded());
        assertEquals(0.0d, nudgeCounter());
        verify(bizMock).call(eq(ToolName.APPLY_REFUND), anyMap(), any());
        assertFalse(result.trace().stream().anyMatch(step -> step.detail() != null
                && step.detail().startsWith("write-claim-blocked")));
    }

    private double nudgeCounter() {
        var counter = registry.find("shoppilot_write_nudge_total").counter();
        return counter == null ? 0.0d : counter.count();
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
