package com.shoppilot.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运维侧两个端点（round23 票 75 清场日补的，热修当时只赶着让它活过来）。
 *
 * <p>它们是清场日抓到的两个洞：{@code /status} 缺失让调试台的状态按钮 404，
 * {@code /count} 的口径错了让运维面板上的工单数恒为 0。
 * 两个都是「出问题才会显形」的路径，所以这里各钉一组：**合法流转进、非法流转 409、未知状态 400、跨租户 404**，
 * 以及**跨租户总量**这件事真的按平台口径算。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shoppilot.ticket.internal-token=test-internal"
})
class TicketOpsEndpointTest {

    private static final String TOKEN = "test-internal";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @Test
    @DisplayName("状态流转：合法进、非法回退 409、未知状态 400")
    void statusTransitionsFollowTheStateMachine() {
        String id = seedTicket();

        assertThat(patch(id, "ASSIGNED", "T001")).isEqualTo(204);
        assertThat(patch(id, "RESOLVED", "T001")).isEqualTo(204);
        assertThat(patch(id, "OPEN", "T001")).as("终态不可回退").isEqualTo(409);
        assertThat(patch(id, "BOGUS", "T001")).as("未知状态值 400").isEqualTo(400);
    }

    @Test
    @DisplayName("状态流转按租户隔离：别店的工单看不见，改不动")
    void statusTransitionIsTenantScoped() {
        String id = seedTicket();

        assertThat(patch(id, "ASSIGNED", "T999")).as("跨租户一律「不存在」").isEqualTo(404);
    }

    @Test
    @DisplayName("工单计数是平台级跨租户总量：带 @TenantId 的仓储查询在无租户上下文时只会数到 PLATFORM 那一档")
    void countIsPlatformScoped() throws Exception {
        seedTicket();
        Integer before = count();

        ResponseEntity<String> response = rest.exchange("/api/tickets/count", HttpMethod.GET,
                // **不带 X-Tenant-Id**：平台级路径要的就是这个上下文，代码也只在原生 SQL 那条路上成立
                new HttpEntity<>(headers(null)), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.path("count").asLong()).as("至少看得到刚建的那张，且不小于历史存量").isGreaterThanOrEqualTo(before);
    }

    @Test
    @DisplayName("计数端点只认内部凭证（平台级不等于公开）")
    void countStillRequiresTheInternalToken() {
        HttpHeaders noToken = new HttpHeaders();
        noToken.set("X-Tenant-Id", "T001");

        ResponseEntity<String> response = rest.exchange("/api/tickets/count", HttpMethod.GET,
                new HttpEntity<>(noToken), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    private Integer count() {
        return jdbc.queryForObject("select count(*) from tickets", Integer.class);
    }

    private String seedTicket() {
        Instant now = Instant.now();
        String id = "T" + now.toEpochMilli() + "-" + (System.nanoTime() % 1_000_000);
        jdbc.update("""
                insert into tickets (id, tenant_id, customer_id, reason, user_query, transcript, status,
                                     created_at, source, priority, queue, sla_deadline)
                values (?, 'T001', 'C001', 'TOOL_UNAVAILABLE', 'q', 't', 'OPEN', ?, 'DEGRADE', 'normal',
                        'ESCALATION', ?)
                """, id, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now.plusSeconds(3600)));
        return id;
    }

    /**
     * PATCH 走 JDK HttpClient 而不是 TestRestTemplate：后者底层 HttpURLConnection 不支持 PATCH，
     * 会把一次本该断言状态码的请求变成传输异常（同 biz-mock 的 TicketWorkflowTest、票 72 的注释）。
     */
    private int patch(String id, String status, String tenantId) {
        try {
            HttpRequest.Builder builder = HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:" + port + "/api/tickets/" + id + "/status"))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", TOKEN);
            if (tenantId != null) {
                builder.header("X-Tenant-Id", tenantId);
            }
            HttpRequest request = builder
                    .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"status\":\"" + status + "\"}"))
                    .build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private HttpHeaders headers(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", TOKEN);
        if (tenantId != null) {
            headers.set("X-Tenant-Id", tenantId);
        }
        return headers;
    }
}