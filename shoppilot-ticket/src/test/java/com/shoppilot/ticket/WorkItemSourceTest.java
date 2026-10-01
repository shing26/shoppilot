package com.shoppilot.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.tool.workitem.TicketSource;
import org.junit.jupiter.api.AfterEach;
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
 * 四种来源与统一工单实体（round23 票 72；用例随数据一起搬过来）。
 *
 * <p>本类钉的是「分流的分母」这件事本身：四种来路必须落进**同一张表**、各自带着可区分的
 * {@code source}，且「降级工单」这个既有公开口径的分母不被新增来路稀释。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shoppilot.ticket.internal-token=test-internal"
})
class WorkItemSourceTest {

    private static final String TOKEN = "test-internal";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    /** 规则表是全局的（不按租户切数据），所以本类插进去的行会影响同类其它用例——收尾清掉。 */
    @AfterEach
    void removeRulesAddedByThisTest() {
        jdbc.update("delete from routing_rules where updated_by = 'test'");
    }

    @Test
    @DisplayName("降级单与渠道回执单是两个来源，reason=EMAIL_REPLY 不再被当成升级单")
    void degradeAndChannelReceiptAreDistinctSources() throws Exception {
        JsonNode degrade = createTicket("T001", "C001", "TOOL_UNAVAILABLE", null);
        JsonNode receipt = createTicket("T001", "C001", TicketSource.CHANNEL_RECEIPT_REASON, null);

        assertThat(degrade.path("source").asText()).isEqualTo("DEGRADE");
        assertThat(receipt.path("source").asText()).isEqualTo("CHANNEL_RECEIPT");
        assertThat(degrade.path("payload").isNull() || degrade.path("payload").asText().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("调用方指名来源时按它落——退款审批与反馈复核就是这么来的")
    void explicitSourceWins() throws Exception {
        JsonNode workItem = send("POST", "/api/tickets", "T001", "C001",
                "{\"source\":\"REFUND_APPROVAL\",\"customerId\":\"C001\",\"reason\":\"REFUND_APPROVAL\","
                        + "\"userQuery\":\"订单 90001 的退款待人工审核\",\"transcript\":\"\","
                        + "\"payload\":\"{\\\"refundId\\\":\\\"7\\\"}\"}");

        assertThat(workItem.path("source").asText()).isEqualTo("REFUND_APPROVAL");
        assertThat(workItem.path("queue").asText()).as("退款审批单进 REFUND 队列").isEqualTo("REFUND");
        assertThat(workItem.path("payload").asText()).contains("\"refundId\"");
    }

    @Test
    @DisplayName("未知 reason 一律算降级：默认来路不能猜成别的")
    void unknownReasonFallsBackToDegrade() throws Exception {
        assertThat(createTicket("T001", "C001", "SOMETHING_NEW", null).path("source").asText())
                .isEqualTo("DEGRADE");
        assertThat(TicketSource.ofReason(null)).isEqualTo(TicketSource.DEGRADE);
        assertThat(TicketSource.parse("nonsense")).isNull();
        assertThat(TicketSource.names()).containsExactlyInAnyOrder("DEGRADE", "FEEDBACK_REVIEW", "REFUND_APPROVAL",
                "CHANNEL_RECEIPT");
    }

    @Test
    @DisplayName("跨租户看不到别店工单的任何来源")
    void workItemsAreTenantScoped() throws Exception {
        JsonNode mine = createTicket("T001", "C001", "TOOL_UNAVAILABLE", null);

        assertThat(statusOf(get("/api/tickets/" + mine.path("id").asText(), "T999")))
                .as("跨租户读不到别人的工单").isEqualTo(404);
    }

    @Test
    @DisplayName("SLA 超时只打戳：状态不动、队列不动、闭环不自动发生")
    void overdueOnlyGetsStamped() throws Exception {
        JsonNode ticket = createTicket("T001", "C001", "TOOL_UNAVAILABLE", null);
        String id = ticket.path("id").asText();
        jdbc.update("update tickets set sla_deadline = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(3600)), id);

        // 列表读路径触发打戳
        send("GET", "/api/tickets", "T001", null, null);
        JsonNode after = send("GET", "/api/tickets/" + id, "T001", null, null);
        assertThat(after.path("escalatedAt").asText("")).isNotBlank();
        assertThat(after.path("status").asText()).as("超时不是关闭").isEqualTo("OPEN");
        assertThat(after.path("queue").asText()).isEqualTo("ESCALATION");
    }

    @Test
    @DisplayName("终态不回溯标超时：已经结掉的单不该再被算进超时")
    void resolvedTicketsAreNotEscalated() throws Exception {
        JsonNode ticket = createTicket("T001", "C001", "TOOL_UNAVAILABLE", null);
        String id = ticket.path("id").asText();
        send("POST", "/api/tickets/" + id + "/claim", "T001", null, null, "alice");
        send("POST", "/api/tickets/" + id + "/resolve", "T001", null, "{\"note\":\"done\"}", "alice");

        jdbc.update("update tickets set escalated_at = null, sla_deadline = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(3600)), id);
        send("GET", "/api/tickets", "T001", null, null);

        assertThat(send("GET", "/api/tickets/" + id, "T001", null, null).path("escalatedAt").asText(""))
                .isEmpty();
    }

    @Test
    @DisplayName("四种来源在同一张表里，且「降级工单」的分母不被新增来路稀释")
    void allSourcesShareOneTableAndDegradeDenominatorStaysClean() throws Exception {
        long degradeBefore = countBySource("DEGRADE");
        long ticketsBefore = totalTickets("T001");

        createTicket("T001", "C001", "SLOT_UNRESOLVED", null);
        send("POST", "/api/tickets", "T001", "C001",
                "{\"source\":\"FEEDBACK_REVIEW\",\"customerId\":\"C001\",\"reason\":\"FEEDBACK_REVIEW\","
                        + "\"userQuery\":\"会话 conv-1 的答复待复核\",\"transcript\":\"答非所问\"}");

        assertThat(countBySource("DEGRADE")).isEqualTo(degradeBefore + 1);
        assertThat(countBySource("FEEDBACK_REVIEW")).isPositive();
        assertThat(totalTickets("T001")).as("新来源进的是同一张表，不另起一张").isEqualTo(ticketsBefore + 2);
        assertThat(countBySource("T999")).as("分母按来源分租户计").isZero();
    }

    // --- helpers ---------------------------------------------------------------------------

    private JsonNode createTicket(String tenantId, String customerId, String reason, String priority) throws Exception {
        String body = "{\"customerId\":\"" + customerId + "\",\"reason\":\"" + reason
                + "\",\"userQuery\":\"q\",\"transcript\":\"t\""
                + (priority == null ? "" : ",\"priority\":\"" + priority + "\"") + "}";
        return send("POST", "/api/tickets", tenantId, customerId, body);
    }

    /** 只看状态码的发送：跨租户那些用例要断言的是 404，不是响应体。 */
    private ResponseEntity<String> get(String path, String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", TOKEN);
        headers.set("X-Tenant-Id", tenantId);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static int statusOf(ResponseEntity<String> response) {
        return response.getStatusCode().value();
    }

    private long countBySource(String source) {
        Long count = jdbc.queryForObject("select count(*) from tickets where source = ?", Long.class, source);
        return count == null ? 0L : count;
    }

    private long totalTickets(String tenantId) {
        Long count = jdbc.queryForObject("select count(*) from tickets where tenant_id = ?", Long.class, tenantId);
        return count == null ? 0L : count;
    }

    private JsonNode send(String method, String path, String tenantId, String customerId, String body) {
        return send(method, path, tenantId, customerId, body, null);
    }

    private JsonNode send(String method, String path, String tenantId, String customerId, String body, String agent) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", TOKEN);
        headers.set("X-Tenant-Id", tenantId);
        if (customerId != null) {
            headers.set("X-Customer-Id", customerId);
        }
        if (agent != null) {
            headers.set("X-Agent", agent);
        }
        ResponseEntity<String> response = rest.exchange(path, HttpMethod.valueOf(method),
                new HttpEntity<>(body == null ? "" : body, headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("请求应成功，实际 %s: %s", response.getStatusCode(), response.getBody()).isTrue();
        // 动作端点回 204 No Content，响应体是空的——解析一个空 body 会炸，所以这里给个空对象。
        // 调用方要断言的是「动作成没成」，那看状态码；正文本来就没有。
        if (response.getBody() == null || response.getBody().isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(response.getBody());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}