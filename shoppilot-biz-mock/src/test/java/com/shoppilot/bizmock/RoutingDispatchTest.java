package com.shoppilot.bizmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.bizmock.domain.TicketPriority;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分流规则表与 SLA 计时（round23 票 70 / ADR 0055）。
 *
 * <p>本类钉三件事：分派是**确定性**的（同输入同结果，规则表改才变）、规则**只能抬高**
 * 优先级（情绪升级与资金动作不会被泛化规则压下去）、SLA 超时**只打戳不改状态**。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:routing-dispatch;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "shoppilot.bizmock.seed.orders=200",
        "shoppilot.bizmock.seed.customers=10",
        "shoppilot.bizmock.internal-token=test-internal"
})
class RoutingDispatchTest {

    private static final String TOKEN = "test-internal";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TOLERANCE_MINUTES = 2;

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    /**
     * 规则表是**全局的**（不按租户切数据），所以本类插进去的行会命中同类的其它用例。
     * 收尾删掉自己写的行，别让一个用例的选择偷走另一个用例的断言。
     */
    @AfterEach
    void removeRulesAddedByThisTest() {
        jdbc.update("delete from routing_rules where updated_by = 'test'");
    }

    @Test
    @DisplayName("四种来源各按规则表落进自己的队列，SLA 截止按分钟数给到")
    void eachSourceLandsInItsOwnQueue() throws Exception {
        assertThat(createTicket("T001", "C001", "TOOL_UNAVAILABLE", null).path("queue").asText())
                .as("普通降级 → ESCALATION 队列，8 小时").isEqualTo("ESCALATION");
        assertThat(minutesUntilDeadline("T001", "TOOL_UNAVAILABLE", null)).isCloseTo(480L,
                org.assertj.core.data.Offset.offset(TOLERANCE_MINUTES));

        assertThat(createTicket("T001", "C001", "EMAIL_REPLY", null).path("queue").asText())
                .as("渠道回执 → REPLY 队列").isEqualTo("REPLY");

        JsonNode downFeedback = createFeedback("T001", "C001", "DOWN");
        assertThat(json(get("/api/tickets/" + workItemIdOf(downFeedback.path("id").asText()), "T001", "C001"))
                .path("queue").asText()).as("点踩复核 → REVIEW 队列").isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("退款审批单落 REFUND 队列且优先级是 money——基线来自来源，不依赖有人配过规则")
    void refundApprovalGetsMoneyBaselineFromSource() throws Exception {
        JsonNode ticket = workItemForRefund();

        assertThat(ticket.path("queue").asText()).isEqualTo("REFUND");
        assertThat(ticket.path("priority").asText())
                .as("存储字面量仍是既有契约，领域名才是 URGENT_EMOTION/MONEY/NORMAL")
                .isEqualTo(TicketPriority.MONEY.literal());
        assertThat(ticket.path("payload").asText()).contains("\"refundId\"");
    }

    @Test
    @DisplayName("情绪升级单即使命中泛化规则也不会被压到 normal 后面")
    void rulesCanRaisePriorityButNeverLowerIt() throws Exception {
        JsonNode escalation = createTicket("T001", "C001", "EMOTION_ESCALATION", "high");

        assertThat(escalation.path("priority").asText())
                .as("泛化的 DEGRADE/* 规则说 normal，但请求自带 high，取更高那档")
                .isEqualTo(TicketPriority.URGENT_EMOTION.literal());
        assertThat(TicketPriority.URGENT_EMOTION.outranks(TicketPriority.MONEY)).isTrue();
        assertThat(TicketPriority.MONEY.outranks(TicketPriority.NORMAL)).isTrue();
        assertThat(TicketPriority.of("high")).isEqualTo(TicketPriority.URGENT_EMOTION);
        assertThat(TicketPriority.of("MONEY")).isEqualTo(TicketPriority.MONEY);
        assertThat(TicketPriority.of("读不懂的值")).as("读不懂就低标，不虚标").isEqualTo(TicketPriority.NORMAL);
    }

    @Test
    @DisplayName("租户级规则覆盖通配规则，且只影响那一家")
    void tenantRuleOverridesWildcardWithoutLeaking() throws Exception {
        jdbc.update("insert into routing_rules (tenant_id, source, reason, queue, priority, sla_minutes, enabled,"
                + " updated_at, updated_by) values ('T001', 'DEGRADE', '*', 'ESCALATION_T1', 'money', 30, true,"
                + " timestamp with time zone '2026-10-01 00:00:00+00', 'test')");

        JsonNode mine = createTicket("T001", "C001", "TOOL_UNAVAILABLE", null);
        JsonNode theirs = createTicket("T002", "C001", "TOOL_UNAVAILABLE", null);

        assertThat(mine.path("queue").asText()).as("租户精确命中权重最高").isEqualTo("ESCALATION_T1");
        assertThat(mine.path("priority").asText()).isEqualTo(TicketPriority.MONEY.literal());
        assertThat(minutesUntilDeadline(mine.path("id").asText())).isCloseTo(30L,
                org.assertj.core.data.Offset.offset(TOLERANCE_MINUTES));

        assertThat(theirs.path("queue").asText()).as("别家仍走通配规则").isEqualTo("ESCALATION");
        assertThat(theirs.path("priority").asText()).isEqualTo(TicketPriority.NORMAL.literal());
    }

    @Test
    @DisplayName("分派可复现：同样两张单拿到同样的队列/优先级，SLA 落在同一分钟档")
    void assignmentIsReproducible() throws Exception {
        JsonNode first = createTicket("T001", "C001", "TOOL_UNAVAILABLE", null);
        JsonNode second = createTicket("T001", "C001", "TOOL_UNAVAILABLE", null);

        assertThat(second.path("queue").asText()).isEqualTo(first.path("queue").asText());
        assertThat(second.path("priority").asText()).isEqualTo(first.path("priority").asText());
        // 截止时间是逐张按 now + slaMinutes 算的，所以比的是分钟档不是同一瞬间
        assertThat(Math.abs(minutesUntilDeadline(first.path("id").asText())
                - minutesUntilDeadline(second.path("id").asText())))
                .as("同一份规则表 → 同一个 SLA 分钟数").isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("SLA 超时只打戳：状态不动、队列不动、闭环不自动发生")
    void overdueOnlyGetsStamped() throws Exception {
        JsonNode ticket = createTicket("T001", "C001", "TOOL_UNAVAILABLE", null);
        String id = ticket.path("id").asText();
        jdbc.update("update tickets set sla_deadline = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1))), id);

        assertThat(json(get("/api/tickets/" + id, "T001", "C001")).path("escalatedAt").asText())
                .as("列表读路径触发打戳").isNotBlank();
        JsonNode after = json(get("/api/tickets/" + id, "T001", "C001"));
        assertThat(after.path("status").asText()).as("超时不是关闭").isEqualTo("OPEN");
        assertThat(after.path("queue").asText()).isEqualTo("ESCALATION");

        jdbc.update("update tickets set status = 'RESOLVED' where id = ?", id);
        jdbc.update("update tickets set escalated_at = null, sla_deadline = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1))), id);
        get("/api/tickets", "T001", "C001");
        assertThat(json(get("/api/tickets/" + id, "T001", "C001")).path("escalatedAt").asText(""))
                .as("终态不回溯标超时：已经结掉的单不该再被算进超时").isEmpty();
    }

    @Test
    @DisplayName("规则表可查：枚举名与存储字面量并排给人看，写入口本轮刻意不做")
    void ruleTableIsReadableButNotWritable() throws Exception {
        JsonNode rules = json(get("/api/routing-rules", "T001", "C001"));
        assertThat(rules.size()).as("V4 播了 6 条初始规则").isGreaterThanOrEqualTo(6);
        assertThat(rules.get(0).path("priorityName").asText()).isNotEmpty();
        assertThat(rules.get(0).path("priorityLiteral").asText()).isNotEmpty();

        ResponseEntity<String> write = rest.exchange("/api/routing-rules", HttpMethod.POST,
                new HttpEntity<>("{\"tenantId\":\"*\",\"source\":\"*\",\"reason\":\"*\"}", headers("T001", "C001")),
                String.class);
        assertThat(write.getStatusCode().value())
                .as("写入口随票 71 的事件骨干一起上——在那之前规则只能走迁移变更")
                .isEqualTo(405);
    }

    // --- helpers -------------------------------------------------------------------------

    private JsonNode workItemForRefund() throws Exception {
        List<String> found = jdbc.query(
                "select id from orders where status in ('PAID','SHIPPED','DELIVERED') and created_at > ? limit 1",
                (rs, rowNum) -> rs.getString("id"), Instant.now().minusSeconds(6 * 24 * 3600));
        assertThat(found).as("种子数据里应存在可退款订单").isNotEmpty();
        String orderNo = found.get(0);
        String customerId = jdbc.queryForObject("select customer_id from orders where id = ?", String.class, orderNo);
        String tenantId = jdbc.queryForObject("select tenant_id from orders where id = ?", String.class, orderNo);

        ResponseEntity<String> applied = rest.exchange("/api/tools/applyRefund", HttpMethod.POST,
                new HttpEntity<>("{\"orderNo\":\"" + orderNo + "\"}",
                        headersWithToken(tenantId, customerId, "routing-" + System.nanoTime())),
                String.class);
        assertThat(applied.getStatusCode().is2xxSuccessful()).isTrue();
        long refundId = JSON.readTree(applied.getBody()).path("payload").path("refundId").asLong();
        String ticketId = jdbc.queryForObject("select ticket_id from refunds where id = ?", String.class, refundId);
        return json(get("/api/tickets/" + ticketId, tenantId, customerId));
    }

    /** 点踩会同时开一张复核工单；返回它要用 payload 里的 feedbackId 反查，这里按时间序取最新的那张。 */
    private String workItemIdOf(String feedbackId) {
        return jdbc.queryForObject(
                "select id from tickets where payload like ? order by created_at desc fetch first 1 row only",
                String.class, "%\"" + feedbackId + "\"%");
    }

    private long minutesUntilDeadline(String tenantId, String reason, String priority) throws Exception {
        return minutesUntilDeadline(createTicket(tenantId, "C001", reason, priority).path("id").asText());
    }

    private long minutesUntilDeadline(String ticketId) {
        // H2 把 timestamp with time zone 取回来是 "yyyy-MM-dd HH:mm:ss.SSSSSSS+00"，不是 ISO 的 T 分隔；
        // 直接 Instant.parse 会炸，所以走 OffsetDateTime 让驱动自己转。
        java.time.OffsetDateTime deadline = jdbc.queryForObject(
                "select sla_deadline from tickets where id = ?", java.time.OffsetDateTime.class, ticketId);
        assertThat(deadline).as("分派当场给 SLA 截止，不存在「这张单还没有队列」的中间态").isNotNull();
        return Duration.between(Instant.now(), deadline.toInstant()).toMinutes();
    }

    private JsonNode createTicket(String tenantId, String customerId, String reason, String priority) throws Exception {
        String body = "{\"customerId\":\"" + customerId + "\",\"reason\":\"" + reason
                + "\",\"userQuery\":\"q\",\"transcript\":\"t\""
                + (priority == null ? "" : ",\"priority\":\"" + priority + "\"") + "}";
        return json(post("/api/tickets", tenantId, customerId, body));
    }

    private JsonNode createFeedback(String tenantId, String customerId, String verdict) throws Exception {
        return json(post("/api/feedback", tenantId, customerId,
                "{\"customerId\":\"" + customerId + "\",\"conversationId\":\"conv-" + System.nanoTime()
                        + "\",\"verdict\":\"" + verdict + "\",\"reason\":\"答得不对\"}"));
    }

    private ResponseEntity<String> post(String path, String tenantId, String customerId, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(tenantId, customerId)), String.class);
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