package com.shoppilot.gateway.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.AgentResult;
import com.shoppilot.gateway.agent.AgentStateMachine;
import com.shoppilot.gateway.agent.EventSink;
import com.shoppilot.gateway.agent.FallbackService;
import com.shoppilot.gateway.agent.PromptCatalog;
import com.shoppilot.gateway.sentiment.SentimentGate;
import com.shoppilot.gateway.feedback.FeedbackService;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 响应发出去之后打的日志认得回触发者（ADR 0027，票 24）。
 *
 * <p>缺陷本体：{@code MDC} 是 ThreadLocal 家族，任务一跨过线程边界坐标就没了。两处提交点里
 * 缓存写回最容易骗人——它在响应之后才跑，日志时序上看完全正常，出事时却一行都捞不到；工单升级
 * 发生在流式工作线程上，同理。
 *
 * <p>两处都用**真实**线程池跑：写回用与 {@code AsyncConfig} 同形的 {@code WriteBackPool}，流式用
 * {@code ChatController} 自己那个虚拟线程执行器。摘掉任一处 {@code RequestTrace.wrap}，对应那格当场判红。
 */
class TraceCorrelationAcrossAsyncTest {

    private static final String TENANT = "T001";
    private static final String CUSTOMER = "C155";
    private static final String CONV = "conv-trace-1";
    private static final String QUERY = "七天内可以退货吗";

    private final Map<String, String> table = new HashMap<>();
    private final SessionStore store = new SessionStore(redis(), new ObjectMapper(), properties());

    @AfterEach
    void wipeContext() {
        TenantContext.clear();
        RequestTrace.clear();
    }

    @Test
    @DisplayName("缓存写回在响应之后异步发生，那条失败日志仍带触发者的四个坐标")
    void writeBackFailureCarriesTheTriggersCoordinates() throws Exception {
        Logger machineLogger = (Logger) LoggerFactory.getLogger(AgentStateMachine.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        machineLogger.addAppender(appender);

        // 与 AsyncConfig 的写回池同形：小池、有界队列、饱和与关闭后走提交线程代跑
        WriteBackPool writeBack = new WriteBackPool(1, 1, 0L, 1, new SimpleMeterRegistry());
        CacheService cache = mock(CacheService.class);
        when(cache.prepareWrite(any(), any(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(entry()));
        doThrow(new IllegalStateException("Redis 写不进去")).when(cache).writeBack(any(), any());
        AgentStateMachine machine = machine(cache, writeBack);

        String traceId = RequestTrace.start();
        RequestTrace.bind(new TenantContext.Identity(TENANT, CUSTOMER, CONV));
        TenantContext.set(new TenantContext.Identity(TENANT, CUSTOMER, CONV));
        try {
            machine.run(QUERY, "tok-write-back", EventSink.NOOP);

            writeBack.close(Duration.ofSeconds(10));

            ILoggingEvent failure = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("异步写回失败"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("写回失败那一行没打出来，用例前提就没了："
                            + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList()));
            assertThat(failure.getMDCPropertyMap())
                    .containsEntry(RequestTrace.TRACE_ID, traceId)
                    .containsEntry(RequestTrace.TENANT_ID, TENANT)
                    .containsEntry(RequestTrace.CUSTOMER_ID, CUSTOMER)
                    .containsEntry(RequestTrace.CONVERSATION_ID, CONV);
        } finally {
            machineLogger.detachAppender(appender);
            writeBack.close(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("REST 与流式共用同一个 traceId 来源：响应体与两处日志行都指向它")
    void streamWorkerSharesTheRestTraceId() throws Exception {
        Logger controllerLogger = (Logger) LoggerFactory.getLogger(ChatController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        controllerLogger.addAppender(appender);
        try {
            String traceId = RequestTrace.start();
            RequestTrace.bind(new TenantContext.Identity(TENANT, CUSTOMER, CONV));
            TenantContext.set(new TenantContext.Identity(TENANT, CUSTOMER, CONV));

            // REST：响应体里的 answerId 必须就是 MDC 里那一个，不再自己 new 一个 UUID
            AgentStateMachine rest = mock(AgentStateMachine.class);
            when(rest.run(any(), any(), any())).thenReturn(result());
            ChatController.ChatResponse body = controller(rest).chat(new ChatController.ChatRequest(QUERY, null),
                    new MockHttpServletRequest("POST", "/api/v1/support/chat")).getBody();
            assertThat(body).isNotNull();
            assertThat(body.answerId()).isEqualTo(traceId);
            // 收口那一行是"按 traceId grep 能捞出一行"的落点，没这行本地跑通无从可验
            assertThat(awaitEvent(appender, "同步问答完成").getMDCPropertyMap())
                    .containsEntry(RequestTrace.TRACE_ID, traceId)
                    .containsEntry(RequestTrace.CONVERSATION_ID, CONV);

            // 流式跑成功：工作线程上那行完成日志也要带同一个链路号
            AgentStateMachine smooth = mock(AgentStateMachine.class);
            when(smooth.run(any(), any(), any())).thenReturn(result());
            controller(smooth).stream(new ChatController.ChatRequest(QUERY, null),
                    new MockHttpServletRequest("POST", "/api/v1/support/chat/stream"));
            assertThat(awaitEvent(appender, "流式问答完成").getMDCPropertyMap())
                    .containsEntry(RequestTrace.TRACE_ID, traceId);

            // 流式：编排在工作线程上炸，那一行日志仍要带同一个链路号
            AgentStateMachine broken = mock(AgentStateMachine.class);
            when(broken.run(any(), any(), any())).thenThrow(new IllegalStateException("编排脱轨"));
            controller(broken).stream(new ChatController.ChatRequest(QUERY, null),
                    new MockHttpServletRequest("POST", "/api/v1/support/chat/stream"));

            ILoggingEvent event = awaitEvent(appender, "编排异常");
            assertThat(event.getMDCPropertyMap()).containsEntry(RequestTrace.TRACE_ID, traceId);
            assertThat(event.getFormattedMessage()).contains("traceId=" + traceId);
        } finally {
            controllerLogger.detachAppender(appender);
        }
    }

    // ---- 夹具 ----

    private static ILoggingEvent awaitEvent(ListAppender<ILoggingEvent> appender, String needle)
            throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            Optional<ILoggingEvent> found = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains(needle))
                    .findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(25);
        }
        throw new AssertionError("等不到含「" + needle + "」的日志行，异步边界没接上的话这里就是红的");
    }

    private ChatController controller(AgentStateMachine machine) {
        RateLimitService rateLimit = mock(RateLimitService.class);
        when(rateLimit.tryAcquire(any(), any(), any())).thenReturn(RateLimitService.Decision.pass());
        return new ChatController(machine, new ObjectMapper(), new SimpleMeterRegistry(), new PromptCatalog(),
                mock(FeedbackService.class),
                new ChatAdmission(mock(CacheService.class), rateLimit, mock(FallbackService.class),
                        new SimpleMeterRegistry()));
    }

    private AgentStateMachine machine(CacheService cache, WriteBackPool writeBack) {
        TriageEngine triage = mock(TriageEngine.class);
        // dynamic：cacheAdmissible=false，直接走模型路径，写回判定仍在末尾
        when(triage.triage(anyString())).thenReturn(new TriageEngine.Outcome(
                TriageResult.dynamic(Intent.POLICY_RETURN, "T3", false), null));
        WriteBackPolicy policy = mock(WriteBackPolicy.class);
        when(policy.evaluate(any())).thenReturn(new WriteBackPolicy.Verdict(true, "单元测试放行写回"));
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);
        HybridRetriever retriever = mock(HybridRetriever.class);
        when(retriever.retrieve(any(), any(), any())).thenReturn(HybridRetriever.Result.unavailable(7L));
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.complete(any())).thenReturn(LlmTypes.Reply.text("支持七天无理由退货"));
        return new AgentStateMachine(triage, cache, mock(SingleFlight.class), policy, epoch, retriever, llm,
                mock(ToolDispatcher.class), store, mock(FallbackService.class), properties(), writeBack,
                new PromptCatalog(), perfModeGate(), new SimpleMeterRegistry());
    }

    private SentimentGate perfModeGate() {
        LlmGateway gateLlm = mock(LlmGateway.class);
        when(gateLlm.mode()).thenReturn("perf");
        return new SentimentGate(gateLlm, new ObjectMapper(), new SimpleMeterRegistry());
    }

    private static CacheEntry entry() {
        return CacheEntry.of("支持七天无理由退货", Intent.POLICY_RETURN, TENANT, "SHOP", 7L, List.of("R-1"),
                "qwen2.5:3b", QUERY);
    }

    private static AgentResult result() {
        return new AgentResult("支持七天无理由退货", Intent.POLICY_RETURN, "T3", CacheService.Layer.NONE,
                List.of(), new ArrayList<>(), null, null, false, 3, 5, false, false, "v1.0.0");
    }

    private static GatewayProperties properties() {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.agent()).thenReturn(new GatewayProperties.Agent(2, 2, Duration.ofMinutes(30), 4));
        when(properties.llm()).thenReturn(new GatewayProperties.Llm("local", "http://127.0.0.1:1", "unused",
                "qwen2.5:3b", 0.0d, Duration.ofSeconds(2), Duration.ofSeconds(30), 0L, null, null, null, null));
        return properties;
    }

    private StringRedisTemplate redis() {
        @SuppressWarnings("unchecked")
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(values.get(anyString())).thenAnswer(call -> table.get(call.getArgument(0, String.class)));
        doAnswer(call -> table.put(call.getArgument(0), call.getArgument(1)))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(template.opsForValue()).thenReturn(values);
        return template;
    }
}
