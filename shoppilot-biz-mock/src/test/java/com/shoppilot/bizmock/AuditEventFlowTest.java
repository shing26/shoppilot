package com.shoppilot.bizmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.bizmock.audit.AuditChannel;
import com.shoppilot.bizmock.repo.AuditEventRowRepository;
import com.shoppilot.bizmock.audit.InMemoryAuditChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计全链路（round23 票 71 / ADR 0054、0056）：三个生产端 → 通道 → 幂等落表 → 查询端点。
 *
 * <p>测试里把 {@link AuditChannel} 换成内存实现（所有者裁定 D），所以跑的是**流那条路**而不是
 * 直写兜底；兜底那条由 {@link #fallsBackToDirectWriteWhenTheChannelIsDown()} 单独钉。
 *
 * <p><b>规则变更那一半随规则表搬到了工单服务</b>（round23 票 72，见那边的
 * {@code RuleAuditTest}）：审计流是两边共用的，但生产动作各有各的归属。
 * 工单出口也换成了测试替身——本轮起不了全栈（裁定 A），而「退款受理要开审批单」必须继续被测到。
 *
 * <p>走真实 HTTP：审计的租户上下文来自 Header，跳过 HTTP 等于把要验的隔离当成前提用掉。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:audit-flow;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "shoppilot.bizmock.seed.orders=200",
        "shoppilot.bizmock.seed.customers=10",
        "shoppilot.bizmock.internal-token=test-internal"
})
class AuditEventFlowTest {

    private static final String TOKEN = "test-internal";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AuditEventRowRepository auditRows;
    @Autowired
    InMemoryAuditChannel channel;

    @TestConfiguration
    static class StubWorkItems {
        /** 工单服务本轮起不来（裁定 A），生产实现换成测试替身。 */
        @Bean
        @Primary
        com.shoppilot.bizmock.workitem.InMemoryWorkItemClient workItemClient() {
            return new com.shoppilot.bizmock.workitem.InMemoryWorkItemClient();
        }
    }
    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TestConfiguration
    static class InMemoryChannelWiring {
        @Bean
        @Primary
        AuditChannel auditChannel() {
            return new InMemoryAuditChannel();
        }
    }

    @Test
    @DisplayName("退款放行与驳回都留痕，且带上自报的审核人")
    void refundReviewIsAudited() throws Exception {
        long refundId = applyRefund();
        review(refundId, "APPROVE", "凭证齐全", "alice");
        reviewOtherRefund("REJECT", "凭证不符", "bob");

        JsonNode approved = auditQuery("REFUND_APPROVED");
        assertThat(approved.isArray()).isTrue();
        List<String> actors = approved.findValuesAsText("actor");
        assertThat(actors).contains("alice");
        assertThat(approved.toString()).as("放行要带金额，供事后对账").contains("退款放行");

        JsonNode rejected = auditQuery("REFUND_REJECTED");
        assertThat(rejected.toString()).contains("bob").contains("凭证不符");
    }

    @Test
    @DisplayName("反馈复核完成留痕")
    void feedbackReviewIsAudited() throws Exception {
        JsonNode created = json(post("/api/feedback", "T001", "C001",
                "{\"customerId\":\"C001\",\"conversationId\":\"conv-audit-1\",\"verdict\":\"DOWN\","
                        + "\"reason\":\"答非所问\"}"));
        String feedbackId = created.path("id").asText();

        assertThat(patch("/api/feedback/" + feedbackId + "/review", "{\"note\":\"已修正\"}", "carol"))
                .as("复核应成功").isEqualTo(200);

        assertThat(auditQuery("FEEDBACK_REVIEWED").toString())
                .as("复核完成要留痕，但复核本身仍不触发任何自动改写（ADR 0039）")
                .contains(feedbackId).contains("carol");
    }

    @Test
    @DisplayName("同一个事件被投递两次只落一行（至少一次投递的幂等落在唯一索引上）")
    void duplicateDeliveryIsStoredOnce() throws Exception {
        long refundId = applyRefund();
        review(refundId, "APPROVE", null, "erin");

        // 查询端点会先收流，所以这一句之后事件才真的在表里
        assertThat(auditsFor(refundId)).as("先收流，表里才有这一条").isEqualTo(1);
        String eventId = storedEventIdOf(refundId);

        // 模拟至少一次投递：把同一条事件再放回队列一次
        channel.redeliver(duplicatingEvent(eventId));
        assertThat(auditsFor(refundId)).as("重投不产生新行").isEqualTo(1);
        // 用原生 SQL 数：AuditEventRow 带 @TenantId，测试里直接调仓储时没有请求上下文，租户是空的
        Integer rowsWithThatEventId = jdbc.queryForObject("select count(*) from audit_event where event_id = ?",
                Integer.class, eventId);
        assertThat(rowsWithThatEventId)
                .as("幂等靠 event_id 唯一约束，重投撞约束即视为已处理").isEqualTo(1);
    }

    @Test
    @DisplayName("通道不可用时事件直写落表，审计不会因为 Redis 挂了而丢")
    void fallsBackToDirectWriteWhenTheChannelIsDown() throws Exception {
        channel.unavailable();
        try {
            review(applyRefund(), "APPROVE", null, "frank");
            assertThat(auditQuery("REFUND_APPROVED").toString())
                    .as("兜底落的那条也要能查到，否则兜底等于没写").contains("frank");
        } finally {
            // 必须复原：通道是测试间共用的同一个实例，不还回去会把后面的用例连坐成「通道不可用」
            channel.markAvailable();
        }
    }

    @Test
    @DisplayName("跨租户看不到别店的审计")
    void auditIsTenantScoped() throws Exception {
        review(applyRefund(), "APPROVE", null, "heidi");

        assertThat(auditQueryAs("T001", "REFUND_APPROVED").size()).isPositive();
        ResponseEntity<String> crossTenant = rest.exchange("/api/audit?action=REFUND_APPROVED", HttpMethod.GET,
                new HttpEntity<>(headers("T999", null)), String.class);
        assertThat(JSON.readTree(crossTenant.getBody()).size())
                .as("T999 看不到 T001 的放行记录").isZero();
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * 这一笔退款在审计里出现几次。按 objectId 数而不是按 action 数——同一个测试类里
     * 别的用例也会产生 REFUND_APPROVED，共用 H2 时按动作计数会互相污染。
     */
    private long auditsFor(long refundId) {
        return auditQuery("REFUND_APPROVED").findValuesAsText("objectId").stream()
                .filter(objectId -> objectId.equals(String.valueOf(refundId)))
                .count();
    }

    /**
     * 这一笔退款落库后的 event_id。用原生 SQL 而不是仓储：{@code AuditEventRow} 带 {@code @TenantId}，
     * 而测试里直接调仓储时没有请求上下文，租户是空的，{@code findAll()} 会返回空集。
     */
    private String storedEventIdOf(long refundId) {
        return jdbc.queryForObject(
                "select event_id from audit_event where object_id = ? order by id desc limit 1",
                String.class, String.valueOf(refundId));
    }

    /** 造一条 eventId 相同、内容不同的重投事件：正是它必须被幂等挡掉的那种。 */
    private com.shoppilot.tool.audit.AuditEvent duplicatingEvent(String eventId) {
        return new com.shoppilot.tool.audit.AuditEvent(eventId, 1, "REFUND_APPROVED", "REFUND", "x", "T001",
                "erin", "重投", Instant.now());
    }

    private long applyRefund() throws Exception {
        List<String> found = jdbc.query(
                "select id from orders where status in ('PAID','SHIPPED','DELIVERED') and created_at > ? limit 1",
                (rs, rowNum) -> rs.getString("id"), Instant.now().minusSeconds(6 * 24 * 3600));
        assertThat(found).as("种子数据里应存在可退款订单").isNotEmpty();
        String orderNo = found.get(0);
        String customerId = jdbc.queryForObject("select customer_id from orders where id = ?", String.class, orderNo);
        String tenantId = jdbc.queryForObject("select tenant_id from orders where id = ?", String.class, orderNo);

        ResponseEntity<String> applied = rest.exchange("/api/tools/applyRefund", HttpMethod.POST,
                new HttpEntity<>("{\"orderNo\":\"" + orderNo + "\"}",
                        headersWithToken(tenantId, customerId, "audit-" + System.nanoTime())), String.class);
        assertThat(applied.getStatusCode().is2xxSuccessful()).isTrue();
        return JSON.readTree(applied.getBody()).path("payload").path("refundId").asLong();
    }

    private void review(long refundId, String decision, String note, String reviewer) throws Exception {
        String body = "{\"decision\":\"" + decision + "\""
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
        ResponseEntity<String> response = rest.exchange("/api/refunds/" + refundId + "/review", HttpMethod.POST,
                new HttpEntity<>(body, headersWithReviewer("T001", reviewer)), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("审核应成功，实际 %s", response.getStatusCode()).isTrue();
    }

    /** 换一个还没审过的退款单，避免同一单被审两次（409）。 */
    private void reviewOtherRefund(String decision, String note, String reviewer) throws Exception {
        review(applyRefund(), decision, note, reviewer);
    }

    /**
     * PATCH 走 JDK HttpClient 而不是 TestRestTemplate：后者底层 HttpURLConnection 不支持 PATCH，
     * 会把「断言状态码」变成一个 I/O 异常（同 {@code TicketWorkflowTest} 的理由）。
     */
    private int patch(String path, String body, String reviewer) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("X-Internal-Token", TOKEN)
                .header("X-Tenant-Id", "T001")
                .header("X-Reviewer", reviewer)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private JsonNode auditQuery(String action) {
        return auditQueryAs("T001", action);
    }

    private JsonNode auditQueryAs(String tenantId, String action) {
        ResponseEntity<String> response = rest.exchange("/api/audit?action=" + action, HttpMethod.GET,
                new HttpEntity<>(headers(tenantId, null)), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        try {
            return JSON.readTree(response.getBody());
        } catch (Exception unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    private ResponseEntity<String> post(String path, String tenantId, String customerId, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(tenantId, customerId)), String.class);
    }

    private HttpHeaders headers(String tenantId, String customerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", TOKEN);
        headers.set("X-Tenant-Id", tenantId);
        if (customerId != null) {
            headers.set("X-Customer-Id", customerId);
        }
        return headers;
    }

    private HttpHeaders headersWithReviewer(String tenantId, String reviewer) {
        HttpHeaders headers = headers(tenantId, null);
        headers.set("X-Reviewer", reviewer);
        return headers;
    }

    private HttpHeaders headersWithToken(String tenantId, String customerId, String idempotencyToken) {
        HttpHeaders headers = headers(tenantId, customerId);
        headers.set("Idempotency-Token", idempotencyToken);
        return headers;
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("请求应成功，实际 %s: %s", response.getStatusCode(), response.getBody()).isTrue();
        return JSON.readTree(response.getBody());
    }
}