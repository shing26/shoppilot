package com.shoppilot.gateway.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 转人工落点：在 biz-mock 生成可查证的工单（ADR 0009）。
 *
 * <p>只推事件不落单等于假功能，面试官一句"工单去哪了"就露底。
 */
@Component
public class FallbackService {

    private static final Logger log = LoggerFactory.getLogger(FallbackService.class);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final GatewayProperties.BizMock config;
    private final MeterRegistry registry;
    private final StringRedisTemplate redis;

    public FallbackService(HttpClient http, ObjectMapper mapper, GatewayProperties properties, MeterRegistry registry,
                           StringRedisTemplate redis) {
        this.http = http;
        this.mapper = mapper;
        this.config = properties.bizmock();
        this.registry = registry;
        this.redis = redis;
    }

    /**
     * 限流降级的落单窗口。
     *
     * <p>限流是降级原因里唯一会自我放大的：被限流的请求本身就在洪峰上，每个 429 都落一单
     * 会把工单表变成 DDoS 目标，人工队列瞬间失去可读性。所以按 (租户, 买家) 合并成一张单，
     * 窗口内后续命中只更新既有工单。
     */
    public static final Duration RATE_LIMIT_TICKET_WINDOW = Duration.ofMinutes(5);

    /** 限流专用：同一买家在窗口内只保留一张工单。 */
    public Optional<String> escalateRateLimited(String userQuery, String tenantId, String customerId) {
        String key = "shoppilot:ticket:ratelimit:" + tenantId + ":" + customerId;
        try {
            String existing = redis.opsForValue().get(key);
            if (existing != null) {
                // 已有在办工单：计数而不是再开一张，人工侧看到的是"这个买家被限流 N 次"
                redis.opsForValue().increment(key + ":hits");
                return Optional.of(existing);
            }
        } catch (RuntimeException redisUnavailable) {
            log.warn("限流工单去重存储不可用，本次直接落单: {}", redisUnavailable.getMessage());
        }
        Optional<String> ticket = escalate(FallbackReason.RATE_LIMITED, userQuery,
                "tenant=" + tenantId + " customer=" + customerId);
        ticket.ifPresent(id -> {
            try {
                redis.opsForValue().set(key, id, RATE_LIMIT_TICKET_WINDOW);
            } catch (RuntimeException redisUnavailable) {
                log.debug("限流工单去重键未写入: {}", redisUnavailable.getMessage());
            }
        });
        return ticket;
    }

    public Optional<String> escalate(FallbackReason reason, String userQuery, String transcript) {
        return escalate(reason, userQuery, transcript, null);
    }

    /**
     * 带优先级的落单：情绪升级（ADR 0034）传 "high"，人工队列按此排序；
     * 其余降级不传（null），工单表按原口径排队。
     */
    public Optional<String> escalate(FallbackReason reason, String userQuery, String transcript, String priority) {
        Counter.builder("shoppilot_fallback_total").tag("reason", reason.name()).register(registry).increment();
        TenantContext.Identity identity = TenantContext.current();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", identity.customerId());
        payload.put("reason", reason.name());
        payload.put("userQuery", userQuery);
        payload.put("transcript", transcript);
        if (priority != null) {
            payload.put("priority", priority);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/tickets"))
                    .timeout(config.readTimeout())
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", config.internalToken())
                    .header("X-Tenant-Id", identity.tenantId())
                    .header("X-Customer-Id", identity.customerId() == null ? "" : identity.customerId())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("落工单失败 status={} body={}", response.statusCode(), response.body());
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(response.body());
            return Optional.ofNullable(root.path("id").isMissingNode() ? null : root.path("id").asText());
        } catch (Exception failure) {
            // 工单落不下去也不能让用户拿不到回复
            log.warn("落工单异常: {}", failure.getMessage());
            return Optional.empty();
        }
    }
}
