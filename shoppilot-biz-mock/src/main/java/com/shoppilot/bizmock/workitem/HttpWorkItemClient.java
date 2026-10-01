package com.shoppilot.bizmock.workitem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.bizmock.tenant.TenantContextHolder;
import com.shoppilot.tool.workitem.TicketSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 走 HTTP 落工作项（生产路径，ADR 0053「跨域只走 API」）。
 *
 * <p>两个刻意的选择：
 * <ol>
 *   <li><b>失败不静默</b>：工单服务不可达时返回 {@code null} 并 warn，**不假装落单成功**。
 *       调用方（退款受理）据此决定继续还是中止——资金动作宁可让人看见「单子没开成」，也不要
 *       留下一张永远没人处理的退款；</li>
 *   <li><b>短超时</b>：调用方在用户的对话回合里，多等一秒就是一次可感知的卡顿。
 *       工单是异步工作项，抢不到这一次调用就下轮补。</li>
 * </ol>
 */
@Component
public class HttpWorkItemClient implements WorkItemClient {

    private static final Logger log = LoggerFactory.getLogger(HttpWorkItemClient.class);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String internalToken;
    private final Duration timeout;

    public HttpWorkItemClient(ObjectMapper mapper,
                              @Value("${shoppilot.bizmock.ticket-base-url}") String baseUrl,
                              @Value("${shoppilot.bizmock.internal-token}") String internalToken,
                              @Value("${shoppilot.bizmock.ticket-timeout:2s}") Duration timeout) {
        this.http = HttpClient.newHttpClient();
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.internalToken = internalToken;
        this.timeout = timeout;
    }

    @Override
    public String create(TicketSource source, String customerId, String reason, String userQuery, String transcript,
                         String priority, String payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", source.name());
        body.put("customerId", customerId);
        body.put("reason", reason);
        body.put("userQuery", userQuery == null ? "" : userQuery);
        body.put("transcript", transcript == null ? "" : transcript);
        if (priority != null) {
            body.put("priority", priority);
        }
        if (payload != null) {
            body.put("payload", payload);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tickets"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .header("X-Tenant-Id", TenantContextHolder.tenantId())
                    .header("X-Customer-Id", customerId == null ? "" : customerId)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("工单服务拒绝落单 status={} body={}", response.statusCode(), response.body());
                return null;
            }
            JsonNode created = mapper.readTree(response.body());
            return created.path("id").asText(null);
        } catch (Exception unreachable) {
            log.warn("工单服务不可达，单子没开成 source={} reason={}: {}", source, reason, unreachable.getMessage());
            return null;
        }
    }

    @Override
    public boolean available() {
        try {
            http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health"))
                    .timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofString());
            return true;
        } catch (Exception unreachable) {
            return false;
        }
    }
}