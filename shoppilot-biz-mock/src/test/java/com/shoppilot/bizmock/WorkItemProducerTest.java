package com.shoppilot.bizmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.bizmock.audit.AuditChannel;
import com.shoppilot.bizmock.audit.InMemoryAuditChannel;
import com.shoppilot.bizmock.workitem.InMemoryWorkItemClient;
import com.shoppilot.tool.workitem.TicketSource;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务侧的两个工单生产者（round23 票 72）。
 *
 * <p>工单数据搬到工单服务之后，本模块**不再持有** {@code tickets} 表——它连一个本地写入点都没有。
 * 所以这里钉的是**边界那一侧**：退款受理与反馈点踩仍然会开单，开的是哪一种来源、payload 指回了谁、
 * 回指列有没有写上，以及工单服务不可达时钱动作照不照走。
 *
 * <p>走真实 HTTP：租户上下文来自 Header，跳过 HTTP 等于把要验的隔离当成前提用掉。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:work-item-producer;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "shoppilot.bizmock.seed.orders=200",
        "shoppilot.bizmock.seed.customers=10",
        "shoppilot.bizmock.internal-token=test-internal"
})
class WorkItemProducerTest {

    private static final String TOKEN = "test-internal";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    InMemoryWorkItemClient workItems;

    @TestConfiguration
    static class StubWorkItems {
        /** 工单服务本轮起不来（裁定 A），所以生产实现换成测试替身——形状照旧，回指照样产生。 */
        @Bean
        @Primary
        InMemoryWorkItemClient workItemClient() {
            return new InMemoryWorkItemClient();
        }

        @Bean
        @Primary
        AuditChannel auditChannel() {
            return new InMemoryAuditChannel();
        }
    }

    @Test
    @DisplayName("退款受理即开 REFUND_APPROVAL 工作项，并把工单号回指到退款行")
    void refundApprovalOpensAWorkItemAndLinksBack() throws Exception {
        long refundId = applyRefund();
        String ticketId = jdbc.queryForObject("select ticket_id from refunds where id = ?", String.class, refundId);

        assertThat(ticketId).as("受理即开单，且回指写在 refunds.ticket_id 上").isNotBlank();
        InMemoryWorkItemClient.Created created = workItems.lastCreated();
        assertThat(created).isNotNull();
        assertThat(created.source()).isEqualTo(TicketSource.REFUND_APPROVAL);
        assertThat(created.reason()).isEqualTo("REFUND_APPROVAL");
        assertThat(created.payload()).contains("\"refundId\":\"" + refundId + "\"");
        assertThat(created.ticketId()).isEqualTo(ticketId);
    }

    @Test
    @DisplayName("点踩才开 FEEDBACK_REVIEW 工作单，点赞不开")
    void downFeedbackOpensReviewWorkItemButUpDoesNot() throws Exception {
        createFeedback("DOWN");
        assertThat(workItems.lastCreated().source()).isEqualTo(TicketSource.FEEDBACK_REVIEW);
        assertThat(workItems.lastCreated().payload()).contains("\"feedbackId\":\"");

        int before = workItems.created().size();
        createFeedback("UP");
        assertThat(workItems.created()).as("UP 只计不审，没有人工要处理的事").hasSize(before);
    }

    @Test
    @DisplayName("工单服务不可达时钱动作照走：退款受理成功，只是没人接单——并留 warn 的缺口")
    void refundStillGoesThroughWhenTheTicketServiceIsDown() throws Exception {
        workItems.unreachable();
        try {
            long refundId = applyRefund();
            assertThat(jdbc.queryForObject("select status from refunds where id = ?", String.class, refundId))
                    .as("审核的真源是 refunds.PENDING_REVIEW，不是那张工作项").isEqualTo("PENDING_REVIEW");
            assertThat(jdbc.queryForObject("select ticket_id from refunds where id = ?", String.class, refundId))
                    .as("开单失败就不写回指：指向一个不存在的号比空着更糟").isNull();
        } finally {
            rest.exchange("/api/admin/demo/reset", HttpMethod.POST, new HttpEntity<>(headers("T001", null)),
                    String.class);
        }
    }

    @Test
    @DisplayName("biz-mock 不再持有工单表：数据随服务搬走了")
    void ticketTablesAreGoneFromThisService() {
        List<String> tables = jdbc.queryForList(
                "select lower(table_name) from information_schema.tables where table_schema = 'PUBLIC'",
                String.class);
        assertThat(tables).as("V6 把随数据搬走的两张表删掉了").doesNotContain("tickets", "routing_rules");
        assertThat(tables).as("退款表的回指列留着——它是字符串引用，不是外键").contains("refunds");
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
                        headersWithToken(tenantId, customerId, "producer-" + System.nanoTime())), String.class);
        assertThat(applied.getStatusCode().is2xxSuccessful()).isTrue();
        return JSON.readTree(applied.getBody()).path("payload").path("refundId").asLong();
    }

    private void createFeedback(String verdict) throws Exception {
        ResponseEntity<String> created = rest.exchange("/api/feedback", HttpMethod.POST,
                new HttpEntity<>("{\"customerId\":\"C001\",\"conversationId\":\"conv-" + System.nanoTime()
                        + "\",\"verdict\":\"" + verdict + "\",\"reason\":\"答非所问\"}", headers("T001", "C001")),
                String.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
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
}