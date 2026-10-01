package com.shoppilot.bizmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.bizmock.domain.TicketSource;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单统一实体（round23 票 69 / ADR 0055）。
 *
 * <p>本类钉的是「分流的分母」这件事本身：四种来路必须落进**同一张表**、各自带着可区分的
 * {@code source}，且「降级工单」这个既有公开口径的分母不被新增来路稀释。
 * 走真实 HTTP（与 {@link RefundReviewTest} 同理）：租户上下文来自 Header，
 * 跳过 HTTP 等于把要验的隔离当成前提用掉。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // 独立 H2：本类会真的建四种来源的工单并断言计数，共用默认库会与同包其它用例互相污染
        "spring.datasource.url=jdbc:h2:mem:unified-ticket;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "shoppilot.bizmock.seed.orders=300",
        "shoppilot.bizmock.seed.customers=10",
        "shoppilot.bizmock.internal-token=test-internal"
})
class UnifiedTicketSourceTest {

    private static final String TOKEN = "test-internal";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("降级单与渠道回执单是两个来源，reason=EMAIL_REPLY 不再被当成升级单")
    void degradeAndChannelReceiptAreDistinctSources() throws Exception {
        JsonNode degrade = createTicket("T001", "C001", "TOOL_UNAVAILABLE");
        JsonNode receipt = createTicket("T001", "C001", TicketSource.CHANNEL_RECEIPT_REASON);

        assertThat(degrade.path("source").asText()).isEqualTo("DEGRADE");
        assertThat(receipt.path("source").asText()).isEqualTo("CHANNEL_RECEIPT");
        // 自包含来源不塞 payload：上下文就在 reason / userQuery / transcript 三列里，
        // 为了「字段一律有值」把已有列再抄一遍是冗余，不是完整。
        assertThat(degrade.path("payload").isNull() || degrade.path("payload").asText().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("退款受理即开审批工单，并把工单号回指到退款行")
    void refundApprovalOpensAWorkItemAndLinksBack() throws Exception {
        RefundTarget target = findRefundableOrder();
        JsonNode refund = applyRefund(target);
        long refundId = refund.path("payload").path("refundId").asLong();

        String ticketId = jdbc.queryForObject(
                "select ticket_id from refunds where id = ?", String.class, refundId);
        assertThat(ticketId).as("受理即建工单，且回指写在 refunds.ticket_id 上").isNotBlank();

        JsonNode workItem = json(get("/api/tickets/" + ticketId, target.tenantId(), target.customerId()));
        assertThat(workItem.path("source").asText()).isEqualTo("REFUND_APPROVAL");
        assertThat(workItem.path("status").asText()).isEqualTo("OPEN");
        assertThat(workItem.path("payload").asText())
                .contains("\"refundId\":\"" + refundId + "\"")
                .contains("\"orderId\":\"" + target.orderNo() + "\"");
    }

    @Test
    @DisplayName("点踩进复核队列才开复核工单，点赞不开")
    void downFeedbackOpensReviewWorkItemButUpDoesNot() throws Exception {
        long before = countBySource("T001", "FEEDBACK_REVIEW");

        createFeedback("T001", "C001", "DOWN", "答非所问");
        createFeedback("T001", "C001", "UP", "挺好");

        assertThat(countBySource("T001", "FEEDBACK_REVIEW"))
                .as("只有 PENDING（点踩）需要人工处理，UP 只计不审")
                .isEqualTo(before + 1);

        String payload = jdbc.queryForObject(
                "select payload from tickets where tenant_id = ? and source = 'FEEDBACK_REVIEW'"
                        + " order by created_at desc, id desc fetch first 1 row only",
                String.class, "T001");
        assertThat(payload).contains("\"feedbackId\":\"").contains("\"conversationId\":\"");

        // 复核单不得覆盖 feedback.ticket_id：那列指的是会话里那张降级单，两者是两件事
        String ticketIdColumn = jdbc.queryForObject(
                "select ticket_id from feedback where tenant_id = ? and verdict = 'DOWN'"
                        + " order by created_at desc fetch first 1 row only",
                String.class, "T001");
        assertThat(ticketIdColumn).as("DOWN 反馈未关联升级单时该列保持为空")
                .satisfiesAnyOf(value -> assertThat(value).isNull(), value -> assertThat(value).isEqualTo(""));
    }

    @Test
    @DisplayName("四种来源在同一张表里，且「降级工单」的分母不被新增来路稀释")
    void allSourcesShareOneTableAndDegradeDenominatorStaysClean() throws Exception {
        long degradeBefore = countBySource("T001", "DEGRADE");
        long ticketsBefore = totalTickets("T001");

        createTicket("T001", "C001", "SLOT_UNRESOLVED");
        RefundTarget target = findRefundableOrder();
        applyRefund(target);
        createFeedback("T001", "C001", "DOWN", "内容不对");

        assertThat(countBySource("T001", "DEGRADE")).isEqualTo(degradeBefore + 1);
        assertThat(countBySource("T001", "REFUND_APPROVAL")).isPositive();
        assertThat(countBySource("T001", "FEEDBACK_REVIEW")).isPositive();
        assertThat(totalTickets("T001")).as("新来源进的是同一张表，不另起一张").isEqualTo(ticketsBefore + 3);
        assertThat(countBySource("T999", "DEGRADE")).as("分母按来源分租户计").isZero();
    }

    @Test
    @DisplayName("跨租户看不到别店的任何来源工单")
    void workItemsAreTenantScoped() throws Exception {
        JsonNode mine = createTicket("T001", "C001", "TOOL_UNAVAILABLE");

        ResponseEntity<String> crossTenant = rest.exchange("/api/tickets/" + mine.path("id").asText(),
                HttpMethod.GET, new HttpEntity<>(headers("T999", null)), String.class);

        assertThat(crossTenant.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("V3 迁移已应用：source 回填为非空，refunds.ticket_id 落库")
    void migrationBackfilledSourceAndAddedTheLinkColumn() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from \"flyway_schema_history\" where \"version\" = '3' and \"success\" = true",
                Integer.class);
        assertThat(applied).as("V3 必须在 flyway_schema_history 里留下成功记录").isEqualTo(1);

        String nullable = jdbc.queryForObject(
                "select is_nullable from information_schema.columns"
                        + " where lower(table_name) = 'tickets' and lower(column_name) = 'source'",
                String.class);
        assertThat(nullable).as("回填 + 置非空都跑过，才会有 NOT NULL 约束").isEqualTo("NO");

        Integer linkColumn = jdbc.queryForObject(
                "select count(*) from information_schema.columns"
                        + " where lower(table_name) = 'refunds' and lower(column_name) = 'ticket_id'",
                Integer.class);
        assertThat(linkColumn).isEqualTo(1);
    }

    @Test
    @DisplayName("来源推导：未知 reason 一律算降级（默认来路不能猜成别的）")
    void sourceFallsBackToDegradeForUnknownReasons() {
        assertThat(TicketSource.ofReason("TOOL_UNAVAILABLE")).isEqualTo(TicketSource.DEGRADE);
        assertThat(TicketSource.ofReason("EMAIL_REPLY")).isEqualTo(TicketSource.CHANNEL_RECEIPT);
        assertThat(TicketSource.ofReason(" email_reply ")).isEqualTo(TicketSource.CHANNEL_RECEIPT);
        assertThat(TicketSource.ofReason("SOMETHING_NEW")).isEqualTo(TicketSource.DEGRADE);
        assertThat(TicketSource.ofReason(null)).isEqualTo(TicketSource.DEGRADE);
        assertThat(TicketSource.parse(" refund_approval ")).isEqualTo(TicketSource.REFUND_APPROVAL);
        assertThat(TicketSource.parse("nonsense")).isNull();
        assertThat(TicketSource.names()).containsExactlyInAnyOrder("DEGRADE", "FEEDBACK_REVIEW", "REFUND_APPROVAL",
                "CHANNEL_RECEIPT");
    }

    private JsonNode createTicket(String tenantId, String customerId, String reason) throws Exception {
        return json(post("/api/tickets", tenantId, customerId,
                "{\"customerId\":\"" + customerId + "\",\"reason\":\"" + reason
                        + "\",\"userQuery\":\"退款没到\",\"transcript\":\"t\"}"));
    }

    private JsonNode createFeedback(String tenantId, String customerId, String verdict, String reason) throws Exception {
        return json(post("/api/feedback", tenantId, customerId,
                "{\"customerId\":\"" + customerId + "\",\"conversationId\":\"conv-" + System.nanoTime()
                        + "\",\"verdict\":\"" + verdict + "\",\"reason\":\"" + reason + "\"}"));
    }

    private JsonNode applyRefund(RefundTarget target) throws Exception {
        String token = "work-item-" + System.nanoTime();
        ResponseEntity<String> response = rest.exchange("/api/tools/applyRefund", HttpMethod.POST,
                new HttpEntity<>("{\"orderNo\":\"" + target.orderNo() + "\"}",
                        headersWithIdempotency(target.tenantId(), target.customerId(), token)),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return JSON.readTree(response.getBody());
    }

    private long countBySource(String tenantId, String source) {
        Long count = jdbc.queryForObject(
                "select count(*) from tickets where tenant_id = ? and source = ?", Long.class, tenantId, source);
        return count == null ? 0L : count;
    }

    private long totalTickets(String tenantId) {
        Long count = jdbc.queryForObject("select count(*) from tickets where tenant_id = ?", Long.class, tenantId);
        return count == null ? 0L : count;
    }

    private RefundTarget findRefundableOrder() {
        List<RefundTarget> found = jdbc.query(
                "select id, tenant_id, customer_id from orders"
                        + " where status in ('PAID','SHIPPED','DELIVERED') and created_at > ? limit 5",
                (rs, rowNum) -> new RefundTarget(rs.getString("tenant_id"), rs.getString("id"),
                        rs.getString("customer_id")),
                Instant.now().minusSeconds(6 * 24 * 3600));
        assertThat(found).as("种子数据里应存在可退款订单").isNotEmpty();
        return found.get(0);
    }

    private ResponseEntity<String> post(String path, String tenantId, String customerId, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(tenantId, customerId)),
                String.class);
    }

    private ResponseEntity<String> get(String path, String tenantId, String customerId) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(tenantId, customerId)), String.class);
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

    private HttpHeaders headersWithIdempotency(String tenantId, String customerId, String idempotencyToken) {
        HttpHeaders headers = headers(tenantId, customerId);
        headers.set("Idempotency-Token", idempotencyToken);
        return headers;
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("请求应成功，实际 %s: %s", response.getStatusCode(), response.getBody()).isTrue();
        return JSON.readTree(response.getBody());
    }

    private record RefundTarget(String tenantId, String orderNo, String customerId) {
    }
}
