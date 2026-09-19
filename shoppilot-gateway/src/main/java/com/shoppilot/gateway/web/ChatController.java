package com.shoppilot.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.AgentResult;
import com.shoppilot.gateway.agent.AgentStateMachine;
import com.shoppilot.gateway.agent.FallbackReason;
import com.shoppilot.gateway.agent.EventSink;
import com.shoppilot.gateway.agent.FallbackService;
import com.shoppilot.gateway.agent.PromptCatalog;
import com.shoppilot.gateway.feedback.FeedbackService;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.channel.Channel;
import com.shoppilot.gateway.channel.ChannelContext;
import com.shoppilot.gateway.identity.RequestTrace;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.tool.Intent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话入口：同步端点与 SSE 端点共用同一编排核心，只有输出适配不同。
 *
 * <p>压测的 QPS/TP99 走同步端点，SSE 单独测 500 并发长连接的 TTFT 与内存（ADR 0001 口径声明）。
 */
@RestController
@RequestMapping("/api/v1/support")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final long SSE_TIMEOUT_MILLIS = 60_000L;

    private final AgentStateMachine agent;
    private final ChatAdmission admission;
    private final PromptCatalog promptCatalog;
    private final FeedbackService feedbackService;
    private final ObjectMapper mapper;
    private final Timer ttftTimer;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(AgentStateMachine agent, ObjectMapper mapper, MeterRegistry registry,
                          PromptCatalog promptCatalog, FeedbackService feedbackService, ChatAdmission admission) {
        this.agent = agent;
        this.admission = admission;
        this.promptCatalog = promptCatalog;
        this.feedbackService = feedbackService;
        this.mapper = mapper;
        // TTFT 口径：服务端收到请求 -> 写出首个 token 帧，不含网络往返
        this.ttftTimer = Timer.builder("shoppilot_ttft_seconds")
                .description("服务端首字时延")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request,
                                             HttpServletRequest servletRequest) {
        ChannelContext.set(Channel.WEB);
        try {
            ChatAdmission.Guard guard = admission.check(servletRequest, request.query());
            if (!guard.allowed()) {
                // 同步端点用标准 429 + Retry-After，脚本与中间层都能直接理解
                ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, guard.retryAfterMs() / 1000)));
                if (guard.ticketId() != null) {
                    builder.header("X-Fallback-Ticket", guard.ticketId());
                }
                return builder.build();
            }
            AgentResult result = agent.run(request.query(), request.idempotencyToken(), EventSink.NOOP);
            // 反馈账本（ADR 0039）：重问检测、negative 计数、点踩关联线索都在这一处更新，同步与流式同形
            TenantContext.Identity chatIdentity = TenantContext.current();
            feedbackService.noteAnswer(chatIdentity.conversationId(),
                    result.intent() == null ? null : result.intent().name(),
                    result.fallbackReason() == null ? null : result.fallbackReason().name(),
                    result.citations(), result.ticketId());
            // 链路号取自鉴权入口，不在这儿另起一个：REST 与流式共用一个来源，日志与响应体才认得回同一条链
            String traceId = RequestTrace.traceId();
            // 一行"这一单办完了"：四个坐标由日志模板带出来，按 traceId grep 才捞得到东西
            log.info("同步问答完成 intent={} cache={} degraded={}", result.intent(), result.cacheLayer(),
                    result.degraded());
            return ResponseEntity.ok(ChatResponse.of(traceId, result, Channel.WEB.label()));
        } finally {
            ChannelContext.clear();
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request, HttpServletRequest servletRequest) {
        ChannelContext.set(Channel.WEB);
        // 身份必须在请求线程上取出后带进工作线程：ThreadLocal 不会跟着任务跑
        TenantContext.Identity identity = TenantContext.current();
        String traceId = RequestTrace.traceId();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        SseEventSink sink = new SseEventSink(emitter, mapper, traceId, promptCatalog.version(), Channel.WEB.label(),
                ttftTimer, System.nanoTime());
        emitter.onTimeout(emitter::complete);

        ChatAdmission.Guard guard = admission.check(servletRequest, request.query());
        if (!guard.allowed()) {
            // 被限流的用户看到的仍是同一条通道里的正常提示，而不是裸 429
            sink.meta(identity.conversationId(), null, CacheService.Layer.NONE);
            // 降级必须留可查证的痕迹，否则"转人工"只是话术
            sink.fallback(FallbackReason.RATE_LIMITED, guard.ticketId());
            sink.rateLimited(guard.retryAfterMs(),
                    "当前咨询人数较多，请稍后再试。（限流维度：" + guard.decision().dimension() + "）");
            emitter.complete();
            ChannelContext.clear();
            return emitter;
        }

        Channel channel = ChannelContext.current();
        // 编排跑在工作线程上，那儿的日志要靠这次 wrap 才认得回是谁触发的
        streamExecutor.execute(RequestTrace.wrap(() -> {
            TenantContext.set(identity);
            ChannelContext.set(channel);
            try {
                AgentResult result = agent.run(request.query(), request.idempotencyToken(), sink);
                sink.done(traceId, result.citations(), result.promptTokens(), result.completionTokens());
                // 与同步路径同形：流式答案也要进反馈账本，点踩关联与重问检测不分通道
                feedbackService.noteAnswer(identity.conversationId(),
                        result.intent() == null ? null : result.intent().name(),
                        result.fallbackReason() == null ? null : result.fallbackReason().name(),
                        result.citations(), result.ticketId());
                emitter.complete();
                log.info("流式问答完成 intent={} cache={} degraded={}", result.intent(), result.cacheLayer(),
                        result.degraded());
            } catch (RuntimeException failure) {
                // 未预期的异常是缺陷，不当成降级路径伪装成功；也不能 completeWithError——那会让容器
                // 拿 text/event-stream 去渲染错误体，客户端连已收到的帧都读不到
                log.error("编排异常 traceId={} conversationId={}", traceId, identity.conversationId(), failure);
                sink.aborted(failure.getClass().getSimpleName() + ": " + failure.getMessage());
                emitter.complete();
            } finally {
                TenantContext.clear();
                ChannelContext.clear();
            }
        }));
        return emitter;
    }

    /**
     * 显式满意度回传（ADR 0039）：赞/踩 + 可选原因。同步与 SSE 客户端同形使用这一个端点；
     * 会话结束未评 = 无信号，端点不做任何猜测或补评。DOWN 自动关联工单与引用块进复核队列，
     * biz-mock 不可达时以 accepted=false 如实返回，不静默假装成功。
     */
    @PostMapping("/chat/feedback")
    public ResponseEntity<FeedbackAck> feedback(@Valid @RequestBody FeedbackRequest request) {
        if (!"UP".equals(request.verdict()) && !"DOWN".equals(request.verdict())) {
            return ResponseEntity.badRequest().body(new FeedbackAck(null, false, null, List.of()));
        }
        FeedbackService.Ack ack = feedbackService.recordExplicit(request.conversationId(), request.verdict(),
                request.reason());
        return ResponseEntity.ok(new FeedbackAck(ack.feedbackId(), ack.reviewQueued(), ack.ticketId(), ack.ruleIds()));
    }

    public record FeedbackRequest(String conversationId, String verdict, String reason) {
    }

    public record FeedbackAck(String feedbackId, boolean reviewQueued, String ticketId, List<String> ruleIds) {
    }

    /** 校验文案写成中文的可显示句子（ADR 0028：400 的文案进 {@code message} 回带，回显那一半在票 26）。 */
    public record ChatRequest(@NotBlank(message = "问题不能为空")
                              @Size(max = 500, message = "问题太长，上限 500 字，请精简后再问")
                              String query, String idempotencyToken) {
    }

    public record ChatResponse(String answerId, String answer, String intent, String triageLayer,
                               String cacheLayer, List<String> citations, String fallbackReason, String ticketId,
                               boolean slotAsked, int promptTokens, int completionTokens, boolean toolUsed,
                               List<AgentResult.TraceStep> trace, String promptVersion, String channel) {

        static ChatResponse of(String answerId, AgentResult result, String channel) {
            Intent intent = result.intent();
            return new ChatResponse(answerId, result.answer(), intent == null ? null : intent.name(),
                    result.triageLayer(), result.cacheLayer().name(), result.citations(),
                    result.fallbackReason() == null ? null : result.fallbackReason().name(),
                    result.ticketId(), result.slotAsked(), result.promptTokens(), result.completionTokens(),
                    result.toolUsed(), result.trace(), result.promptVersion(), channel);
        }
    }
}
