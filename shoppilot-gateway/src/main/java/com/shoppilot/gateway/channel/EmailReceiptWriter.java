package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
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
 * 邮件回执落点（ADR 0035）：邮件渠道没有实时回包通道，结果以工单为交付形态。
 * reason 固定 {@code EMAIL_REPLY}——与转人工工单（降级因）在数据上可区分，
 * 人工队列按 reason 一看就知道这条不是升级单。
 */
@Component
public class EmailReceiptWriter {

    private static final Logger log = LoggerFactory.getLogger(EmailReceiptWriter.class);
    public static final String RECEIPT_REASON = "EMAIL_REPLY";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final GatewayProperties.BizMock config;

    public EmailReceiptWriter(HttpClient http, ObjectMapper mapper, GatewayProperties properties) {
        this.http = http;
        this.mapper = mapper;
        this.config = properties.bizmock();
    }

    /** @return 回执工单号；下游不可达时 empty（如实返回，不静默假装落单成功）。 */
    public Optional<String> writeReceipt(String query, String answer, String contact) {
        TenantContext.Identity identity = TenantContext.current();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", identity.customerId());
        payload.put("reason", RECEIPT_REASON);
        payload.put("userQuery", query);
        payload.put("transcript", answer + (contact == null ? "" : "\n【回执渠道】" + contact));
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
                log.warn("邮件回执落单失败 status={}", response.statusCode());
                return Optional.empty();
            }
            return Optional.ofNullable(mapper.readTree(response.body()).path("id").asText(null));
        } catch (Exception failure) {
            log.warn("邮件回执落单异常: {}", failure.getMessage());
            return Optional.empty();
        }
    }
}
