package com.shoppilot.gateway.feedback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 满意度反馈的网关侧账本（ADR 0039 / 票 37）。
 *
 * <p>隐式信号全是既有可观测转移上的计数埋点，状态机零改动：重问同意图在问答完成处
 * （本服务记录的会话线索）比对，FALLBACK 走到即 negative（由 ChatController 在结果上观测），
 * 幂等重放在 ToolDispatcher 的 duplicate 分支计数。显式点踩/点赞落 biz-mock feedback 表，
 * DOWN 行自动关联当次会话的工单与检索引用块，进 ingest 待复核队列；人工复核前不发生任何改写。
 *
 * <p>会话线索是单机内存态：重启即清空，重启后未评 = 无信号（不猜测原则），不外推多实例。
 */
@Component
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);
    /** 重问窗口：同会话同意图且间隔小于该窗口 → implied_dissatisfied（ADR 0039 的"会话窗口"）。 */
    static final Duration REPEAT_WINDOW = Duration.ofMinutes(30);

    /** biz-mock 落点。返回 null 表示下游不可达或非 2xx，反馈以"未记录"如实返回客户端。 */
    public interface FeedbackPoster {
        String post(Map<String, Object> payload);
    }

    private final ObjectMapper mapper;
    private final MeterRegistry registry;
    private final FeedbackPoster poster;
    private final Duration repeatWindow;
    private final Map<String, Trail> trails = new ConcurrentHashMap<>();

    /**
     * 生产入口：从配置拼 HTTP 落点。
     *
     * <p>{@code @Autowired} 是必需的：本类有两个构造器（生产 + 测试缝），
     * 不加注解 Spring 会因"没有唯一构造器"拒绝启动（2026-09-19 活体起栈当场抓到，
     * JVM 测试都手工 new 因而没覆盖启动路径）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public FeedbackService(ObjectMapper mapper, MeterRegistry registry, GatewayProperties properties,
                           HttpClient http) {
        this(mapper, registry, defaultPoster(mapper, properties, http), REPEAT_WINDOW);
    }

    FeedbackService(ObjectMapper mapper, MeterRegistry registry, FeedbackPoster poster, Duration repeatWindow) {
        this.mapper = mapper;
        this.registry = registry;
        this.poster = poster;
        this.repeatWindow = repeatWindow;
    }

    private static FeedbackPoster defaultPoster(ObjectMapper mapper, GatewayProperties properties, HttpClient http) {
        return payload -> {
            try {
                TenantContext.Identity identity = TenantContext.current();
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create(properties.bizmock().baseUrl() + "/api/feedback"))
                        .timeout(properties.bizmock().readTimeout())
                        .header("Content-Type", "application/json")
                        .header("X-Internal-Token", properties.bizmock().internalToken())
                        .header("X-Tenant-Id", identity.tenantId())
                        .header("X-Customer-Id", identity.customerId() == null ? "" : identity.customerId())
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    log.warn("反馈落库失败 status={}", response.statusCode());
                    return null;
                }
                return response.body();
            } catch (Exception down) {
                log.warn("反馈落库异常: {}", down.getMessage());
                return null;
            }
        };
    }

    /**
     * 每次问答完成后由 ChatController 调用（同步与流式同形）：重问检测、negative 计数、
     * 更新会话关联线索（点踩时用它联动工单与引用块）。
     */
    public void noteAnswer(String conversationId, String intentName, String fallbackReason,
                           List<String> citations, String ticketId) {
        Instant now = Instant.now();
        Trail previous = trails.put(conversationId,
                new Trail(intentName, now, citations == null ? List.of() : citations, ticketId, fallbackReason));
        if (fallbackReason != null) {
            implied("negative");
        }
        if (intentName != null && previous != null && intentName.equals(previous.intentName())
                && previous.at() != null && now.minus(repeatWindow).isBefore(previous.at())) {
            implied("implied_dissatisfied");
        }
    }

    /** 幂等重放的隐式信号由 ToolDispatcher 的 duplicate 分支直接计数（kind=implied_retry），不经本服务。 */

    /**
     * 显式点踩/点赞。DOWN 且线索可查时返回 reviewQueued=true（进 ingest 复核队列）；
     * 下游不可达时 accepted=false——反馈丢没丢要如实告诉客户端，不静默假装成功。
     */
    public Ack recordExplicit(String conversationId, String verdict, String reason) {
        registry.counter("shoppilot_feedback_explicit_total", "verdict", verdict).increment();
        Trail trail = trails.get(conversationId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", TenantContext.current().customerId());
        payload.put("conversationId", conversationId);
        payload.put("verdict", verdict);
        payload.put("reason", reason);
        payload.put("signals", trail != null && trail.fallbackReason() != null ? "negative" : null);
        payload.put("ruleIds", trail == null ? null : trail.citations());
        payload.put("ticketId", trail == null ? null : trail.ticketId());
        String body = poster.post(payload);
        if (body == null) {
            return new Ack(null, false, trail == null ? null : trail.ticketId(),
                    trail == null ? List.of() : trail.citations());
        }
        try {
            JsonNode root = mapper.readTree(body);
            return new Ack(root.path("id").asText(null),
                    "PENDING".equals(root.path("reviewStatus").asText("")), trail == null ? null : trail.ticketId(),
                    trail == null ? List.of() : trail.citations());
        } catch (Exception unparsable) {
            log.warn("反馈回执解析失败: {}", unparsable.getMessage());
            return new Ack(null, false, trail == null ? null : trail.ticketId(),
                    trail == null ? List.of() : trail.citations());
        }
    }

    private void implied(String kind) {
        registry.counter("shoppilot_feedback_implied_total", "kind", kind).increment();
    }

    private record Trail(String intentName, Instant at, List<String> citations, String ticketId,
                         String fallbackReason) {
    }

    public record Ack(String feedbackId, boolean reviewQueued, String ticketId, List<String> ruleIds) {
    }
}
