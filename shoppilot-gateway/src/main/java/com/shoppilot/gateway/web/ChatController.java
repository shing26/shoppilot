package com.shoppilot.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.AgentResult;
import com.shoppilot.gateway.agent.AgentStateMachine;
import com.shoppilot.gateway.agent.EventSink;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.tool.Intent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话入口：同步端点与 SSE 端点共用同一编排核心，只有输出适配不同。

 * <p>压测的 QPS/TP99 走同步端点，SSE 单独测 500 并发长连接的 TTFT 与内存（ADR 0001 口径声明）。
 */
@RestController
@RequestMapping("/api/v1/support")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final long SSE_TIMEOUT_MILLIS = 60_000L;

    private final AgentStateMachine agent;
    private final CacheService cacheService;
    private final ObjectMapper mapper;
    private final Timer ttftTimer;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(AgentStateMachine agent, CacheService cacheService, ObjectMapper mapper,
                          MeterRegistry registry) {
        this.agent = agent;
        this.cacheService = cacheService;
        this.mapper = mapper;
        // TTFT 口径：服务端收到请求 -> 写出首个 token 帧，不含网络往返
        this.ttftTimer = Timer.builder("shoppilot_ttft_seconds")
                .description("服务端首字时延")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        cacheService.recordRequest();
        AgentResult result = agent.run(request.query(), request.idempotencyToken(), EventSink.NOOP);
        return ChatResponse.of(UUID.randomUUID().toString(), result);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        cacheService.recordRequest();
        // 身份必须在请求线程上取出后带进工作线程：ThreadLocal 不会跟着任务跑
        TenantContext.Identity identity = TenantContext.current();
        String traceId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        SseEventSink sink = new SseEventSink(emitter, mapper, traceId, ttftTimer, System.nanoTime());
        emitter.onTimeout(emitter::complete);
        streamExecutor.execute(() -> {
            TenantContext.set(identity);
            try {
                AgentResult result = agent.run(request.query(), request.idempotencyToken(), sink);
                sink.done(traceId, result.citations(), result.promptTokens(), result.completionTokens());
                emitter.complete();
            } catch (RuntimeException failure) {
                // 未预期的异常是缺陷，不当成降级路径伪装成功；也不能 completeWithError——那会让容器
                // 拿 text/event-stream 去渲染错误体，客户端连已收到的帧都读不到
                log.error("编排异常 traceId={} conversationId={}", traceId, identity.conversationId(), failure);
                sink.aborted(failure.getClass().getSimpleName() + ": " + failure.getMessage());
                emitter.complete();
            } finally {
                TenantContext.clear();
            }
        });
        return emitter;
    }

    public record ChatRequest(@NotBlank @Size(max = 500) String query, String idempotencyToken) {
    }

    public record ChatResponse(String answerId, String answer, String intent, String triageLayer,
                               String cacheLayer, List<String> citations, String fallbackReason, String ticketId,
                               boolean slotAsked, int promptTokens, int completionTokens, boolean toolUsed,
                               List<AgentResult.TraceStep> trace) {

        static ChatResponse of(String answerId, AgentResult result) {
            Intent intent = result.intent();
            return new ChatResponse(answerId, result.answer(), intent == null ? null : intent.name(),
                    result.triageLayer(), result.cacheLayer().name(), result.citations(),
                    result.fallbackReason() == null ? null : result.fallbackReason().name(),
                    result.ticketId(), result.slotAsked(), result.promptTokens(), result.completionTokens(),
                    result.toolUsed(), result.trace());
        }
    }
}
