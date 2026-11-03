package com.shoppilot.bizmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 满意度反馈的落点与复核队列（ADR 0039 / 票 37）：DOWN 进队列、关联字段随行、
 * 复核状态单向流转、租户隔离与工单同口径。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shoppilot.bizmock.seed.orders=10",
        "shoppilot.bizmock.seed.customers=5",
        "shoppilot.bizmock.internal-token=test-internal"
})
class FeedbackReviewQueueTest {

    private static final String TOKEN = "test-internal";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    @Test
    @DisplayName("点踩落行进复核队列，关联工单与引用块随行；点赞不进队列")
    void downEntersQueueAndUpDoesNot() throws Exception {
        String down = create("C001", "conv-1", "DOWN", "答案与政策对不上", "negative",
                List.of("POLICY-RETURN-01", "POLICY-RETURN-02"), "T-900");
        assertThat(reviewStatus(down)).isEqualTo("PENDING");
        assertThat(ruleIds(down)).containsExactly("POLICY-RETURN-01", "POLICY-RETURN-02");
        assertThat(ticketId(down)).isEqualTo("T-900");

        String up = create("C001", "conv-2", "UP", null, null, List.of("POLICY-SHIPPING-01"), null);
        assertThat(reviewStatus(up)).isEqualTo("NONE");

        JsonNode queue = reviewQueue();
        List<String> queueIds = new java.util.ArrayList<>();
        queue.forEach(row -> queueIds.add(row.path("id").asText()));
        assertThat(queueIds).contains(idOf(down));
        assertThat(queueIds).doesNotContain(idOf(up));
    }

    @Test
    @DisplayName("复核状态单向流转：PENDING -> REVIEWED，重复复核 409")
    void reviewTransitionIsOneWay() throws Exception {
        String id = idOf(create("C001", "conv-3", "DOWN", null, null, List.of(), null));
        assertThat(reviewStatus(markReviewed(id))).isEqualTo("REVIEWED");
        assertThat(patchStatusRaw(id).statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("租户隔离：A 店的反馈不出现在 B 店的复核队列")
    void queueIsTenantScoped() throws Exception {
        String a = idOf(create("C001", "conv-4", "DOWN", null, null, List.of(), null));
        JsonNode queue = reviewQueue("T002", "C002");
        List<String> ids = new java.util.ArrayList<>();
        queue.forEach(row -> ids.add(row.path("id").asText()));
        assertThat(ids).doesNotContain(a);
    }

    @Test
    @DisplayName("复核队列可按状态读：默认 PENDING，REVIEWED 复核后能取回，非法状态 400")
    void queueCanBeReadByStatus() throws Exception {
        String pending = idOf(create("C001", "conv-status-pending", "DOWN", null, null, List.of(), null));
        String reviewed = idOf(create("C001", "conv-status-reviewed", "DOWN", null, null, List.of(), null));
        assertThat(markReviewed(reviewed)).contains("\"reviewStatus\":\"REVIEWED\"");

        List<String> defaults = idsOf(reviewQueue());
        assertThat(defaults).as("不传 status 仍是 PENDING，老客户端行为不变").contains(pending).doesNotContain(reviewed);

        List<String> reviewedIds = idsOf(reviewQueue("T001", "C001", "REVIEWED"));
        assertThat(reviewedIds).as("已复核的能按状态取回，刷新后仍可附稿").contains(reviewed).doesNotContain(pending);

        assertThat(reviewQueueRaw("bogus").statusCode()).as("非法状态回 400，不悄悄退回 PENDING").isEqualTo(400);
    }

    private String create(String customer, String conversation, String verdict, String reason, String signals,
                          List<String> ruleIds, String ticketId) throws Exception {
        String body = JSON.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
            put("customerId", customer);
            put("conversationId", conversation);
            put("verdict", verdict);
            put("reason", reason);
            put("signals", signals);
            put("ruleIds", ruleIds);
            put("ticketId", ticketId);
        }});
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/feedback"))
                .header("X-Internal-Token", TOKEN)
                .header("X-Tenant-Id", "T001")
                .header("X-Customer-Id", customer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private JsonNode reviewQueue() throws Exception {
        return reviewQueue("T001", "C001", null);
    }

    private JsonNode reviewQueue(String tenant, String customer) throws Exception {
        return reviewQueue(tenant, customer, null);
    }

    private JsonNode reviewQueue(String tenant, String customer, String status) throws Exception {
        return JSON.readTree(reviewQueueRaw(status, tenant, customer).body());
    }

    private HttpResponse<String> reviewQueueRaw(String status) throws Exception {
        return reviewQueueRaw(status, "T001", "C001");
    }

    private HttpResponse<String> reviewQueueRaw(String status, String tenant, String customer) throws Exception {
        String query = status == null ? "" : "?status=" + status;
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/feedback/review-queue" + query))
                .header("X-Internal-Token", TOKEN)
                .header("X-Tenant-Id", tenant)
                .header("X-Customer-Id", customer)
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private List<String> idsOf(JsonNode rows) {
        List<String> ids = new java.util.ArrayList<>();
        rows.forEach(row -> ids.add(row.path("id").asText()));
        return ids;
    }

    private HttpResponse<String> patchStatusRaw(String id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/feedback/" + id + "/review"))
                .header("X-Internal-Token", TOKEN)
                .header("X-Tenant-Id", "T001")
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"status\":\"REVIEWED\"}"))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String markReviewed(String id) throws Exception {
        return patchStatusRaw(id).body();
    }

    private String idOf(String row) throws Exception {
        return JSON.readTree(row).path("id").asText();
    }

    private String reviewStatus(String row) throws Exception {
        return JSON.readTree(row).path("reviewStatus").asText();
    }

    private String ticketId(String row) throws Exception {
        return JSON.readTree(row).path("ticketId").asText();
    }

    private List<String> ruleIds(String row) throws Exception {
        JsonNode ids = JSON.readTree(row).path("ruleIds");
        return ids.asText().isEmpty() ? List.of() : List.of(ids.asText().split(","));
    }
}
