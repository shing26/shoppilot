package com.shoppilot.gateway.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.tool.ToolName;
import com.shoppilot.tool.view.ToolStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨进程调用 biz-mock，外层套超时与熔断（ADR 0002、ADR 0008）。
 *
 * <p>工具失败不在模型层重试：把结构化失败事实回填给模型，让它组织人话。
 * 这样"降级"表现为模型说明，而不是抛 500 或静默失败。
 */
@Component
public class BizMockClient {

    private static final Logger log = LoggerFactory.getLogger(BizMockClient.class);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final GatewayProperties.BizMock config;
    private final CircuitBreaker circuitBreaker;
    private final Counter timeoutCounter;
    private final Counter unavailableCounter;
    private final AtomicLong lastLatencyMs = new AtomicLong();

    public BizMockClient(HttpClient http, ObjectMapper mapper, GatewayProperties properties, MeterRegistry registry) {
        this.http = http;
        this.mapper = mapper;
        this.config = properties.bizmock();
        this.circuitBreaker = CircuitBreaker.of("bizmock", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build());
        this.timeoutCounter = Counter.builder("shoppilot_tool_timeout_total").register(registry);
        this.unavailableCounter = Counter.builder("shoppilot_tool_unavailable_total").register(registry);
    }

    public record Outcome(ToolStatus status, String json, boolean degraded) {
    }

    public Outcome call(ToolName tool, Map<String, Object> arguments, String idempotencyToken) {
        String body;
        try {
            body = mapper.writeValueAsString(arguments);
        } catch (Exception unserializable) {
            return failure(tool, ToolStatus.UNAVAILABLE, "参数无法序列化");
        }
        if (circuitBreaker.tryAcquirePermission()) {
            long started = System.nanoTime();
            try {
                String response = send(tool, body, idempotencyToken);
                circuitBreaker.onSuccess(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
                return interpret(tool, response);
            } catch (HttpTimeoutException timedOut) {
                circuitBreaker.onError(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS, timedOut);
                timeoutCounter.increment();
                log.warn("工具 {} 调用超时", tool.apiName());
                return failure(tool, ToolStatus.TIMEOUT, "业务系统响应超时");
            } catch (Exception failure) {
                circuitBreaker.onError(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS, failure);
                unavailableCounter.increment();
                log.warn("工具 {} 调用失败: {}", tool.apiName(), failure.getMessage());
                return failure(tool, ToolStatus.UNAVAILABLE, "业务系统不可用");
            }
        }
        unavailableCounter.increment();
        return failure(tool, ToolStatus.UNAVAILABLE, "业务系统熔断已打开，暂停调用");
    }

    private String send(ToolName tool, String body, String idempotencyToken) throws Exception {
        TenantContext.Identity identity = TenantContext.current();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/tools/" + tool.apiName()))
                .timeout(config.readTimeout())
                .header("Content-Type", "application/json")
                .header("X-Internal-Token", config.internalToken())
                .header("X-Tenant-Id", identity.tenantId())
                .header("X-Customer-Id", identity.customerId() == null ? "" : identity.customerId());
        if (idempotencyToken != null) {
            builder.header("Idempotency-Token", idempotencyToken);
        }
        long started = System.nanoTime();
        HttpResponse<String> response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        lastLatencyMs.set(Duration.ofNanos(System.nanoTime() - started).toMillis());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("biz-mock 返回 " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    private Outcome interpret(ToolName tool, String response) {
        try {
            JsonNode root = mapper.readTree(response);
            ToolStatus status = ToolStatus.valueOf(root.path("status").asText(ToolStatus.OK.name()));
            boolean degraded = status == ToolStatus.TIMEOUT || status == ToolStatus.UNAVAILABLE;
            if (degraded) {
                unavailableCounter.increment();
            }
            return new Outcome(status, mapper.writeValueAsString(root), degraded);
        } catch (Exception unparsable) {
            return failure(tool, ToolStatus.UNAVAILABLE, "业务系统返回格式异常");
        }
    }

    private Outcome failure(ToolName tool, ToolStatus status, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("tool", tool.apiName());
        envelope.put("status", status.name());
        envelope.put("message", message);
        try {
            return new Outcome(status, mapper.writeValueAsString(envelope), true);
        } catch (Exception unserializable) {
            return new Outcome(status, "{\"status\":\"UNAVAILABLE\"}", true);
        }
    }

    public long lastLatencyMs() {
        return lastLatencyMs.get();
    }

    public String circuitState() {
        return circuitBreaker.getState().name();
    }
}
