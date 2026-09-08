package com.shoppilot.bizmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.bizmock.domain.TicketStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 降级终点是工单，不是"稍后为您跟进"这句空话（ticket 14、ADR 0009）。
 * 工单既然要人工处理，状态就得是个状态机而不是一个自由文本字段。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shoppilot.bizmock.seed.orders=10",
        "shoppilot.bizmock.seed.customers=5",
        "shoppilot.bizmock.internal-token=test-internal"
})
class TicketWorkflowTest {

    private static final String TOKEN = "test-internal";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    @Test
    @DisplayName("工单按 OPEN -> ASSIGNED -> RESOLVED 正常流转")
    void walksTheHappyPath() {
        String id = createTicket("TOOL_UNAVAILABLE");
        assertThat(statusOf(patch(id, "ASSIGNED"))).isEqualTo("ASSIGNED");
        assertThat(statusOf(patch(id, "RESOLVED"))).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("已结单不允许回退，未知状态一律拒绝")
    void rejectsIllegalAndUnknownTransitions() {
        String id = createTicket("SLOT_UNRESOLVED");
        assertThat(patch(id, "RESOLVED").statusCode()).isEqualTo(200);
        // 终态不可回退：否则"已处理完"的计数随时能被改写
        assertThat(patch(id, "OPEN").statusCode()).isEqualTo(409);
        assertThat(patch(id, "BOGUS").statusCode()).isEqualTo(400);
        assertThat(statusOf(get(id))).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("未发现的工单返回 404，不与状态冲突混淆")
    void missingTicketIsNotFound() {
        assertThat(patch("T-not-a-ticket", "ASSIGNED").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("状态机本身：RESOLVED 是终态，OPEN 允许直接结单")
    void transitionTable() {
        assertThat(TicketStatus.OPEN.canTransitionTo(TicketStatus.ASSIGNED)).isTrue();
        assertThat(TicketStatus.OPEN.canTransitionTo(TicketStatus.RESOLVED)).isTrue();
        assertThat(TicketStatus.ASSIGNED.canTransitionTo(TicketStatus.OPEN)).isFalse();
        assertThat(TicketStatus.RESOLVED.canTransitionTo(TicketStatus.ASSIGNED)).isFalse();
        assertThat(TicketStatus.parse(" resolved ")).isEqualTo(TicketStatus.RESOLVED);
        assertThat(TicketStatus.parse("nonsense")).isNull();
    }

    /**
     * 用 JDK HttpClient 而不是 TestRestTemplate：后者底层 HttpURLConnection 不支持 PATCH，
     * 会把一次本该断言状态码的请求变成 "Invalid HTTP method" 的 I/O 异常。
     */
    private String createTicket(String reason) {
        HttpResponse<String> created = send("POST", "/api/tickets",
                "{\"customerId\":\"C001\",\"reason\":\"" + reason
                        + "\",\"userQuery\":\"order not found\",\"transcript\":\"t\"}");
        assertThat(created.statusCode()).isEqualTo(200);
        return json(created).path("id").asText();
    }

    private HttpResponse<String> patch(String id, String status) {
        return send("PATCH", "/api/tickets/" + id + "/status", "{\"status\":\"" + status + "\"}");
    }

    private HttpResponse<String> get(String id) {
        return send("GET", "/api/tickets/" + id, null);
    }

    private HttpResponse<String> send(String method, String path, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", TOKEN)
                    .header("X-Tenant-Id", "T001")
                    .header("X-Customer-Id", "C001");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String statusOf(HttpResponse<String> response) {
        return json(response).path("status").asText();
    }

    private static JsonNode json(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
