package com.shoppilot.gateway.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    public FallbackService(HttpClient http, ObjectMapper mapper, GatewayProperties properties, MeterRegistry registry) {
        this.http = http;
        this.mapper = mapper;
        this.config = properties.bizmock();
        this.registry = registry;
    }

    public Optional<String> escalate(FallbackReason reason, String userQuery, String transcript) {
        Counter.builder("shoppilot_fallback_total").tag("reason", reason.name()).register(registry).increment();
        TenantContext.Identity identity = TenantContext.current();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", identity.customerId());
        payload.put("reason", reason.name());
        payload.put("userQuery", userQuery);
        payload.put("transcript", transcript);
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
