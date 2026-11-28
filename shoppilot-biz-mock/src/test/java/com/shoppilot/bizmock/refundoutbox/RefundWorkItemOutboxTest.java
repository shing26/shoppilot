package com.shoppilot.bizmock.refundoutbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.bizmock.audit.AuditChannel;
import com.shoppilot.bizmock.audit.InMemoryAuditChannel;
import com.shoppilot.bizmock.tenant.TenantContextHolder;
import com.shoppilot.bizmock.workitem.InMemoryWorkItemClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * 退款审批工单 outbox（round63 票 234 / U3）。
 *
 * <p>钉的是跨进程两步之间的本地事实：受理事务内的意图、开单失败不回滚受理、
 * 提交后立即补偿、退避重试、重复处理不重复开单、审核后不再补开。
 *
 * <p>工单服务仍然用内存替身（本轮不起全栈）：替身只换传输，不换单号与回指，
 * 所以 {@code refunds.ticket_id} 的断言仍是真的。后台轮询在测试里被
 * {@code src/test/resources/application.properties} 关掉，由用例显式驱动补偿器。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:refund-outbox;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "shoppilot.bizmock.seed.orders=400",
        "shoppilot.bizmock.seed.customers=10",
        "shoppilot.bizmock.internal-token=test-internal"
})
class RefundWorkItemOutboxTest {

    private static final String TOKEN = "test-internal";
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    InMemoryWorkItemClient workItems;
    @Autowired
    RefundWorkItemProcessor processor;
    @Autowired
    MeterRegistry registry;
    @SpyBean
    RefundWorkItemOutboxRepository outbox;

    @TestConfiguration
    static class Stubs {
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

    @BeforeEach
    void resetStub() {
        workItems.reachable();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("AC1：outbox 写入失败时退款、订单冻结与意图一起回滚")
    void enqueueFailureRollsBackTheWholeAcceptance() throws Exception {
        RefundTarget target = refundableOrder();
        String statusBefore = orderStatus(target);
        doThrow(new IllegalStateException("outbox insert failed"))
                .when(outbox).enqueue(anyString(), anyLong(), anyString(), anyString(), any(Instant.class));

        ResponseEntity<String> response = rest.exchange("/api/tools/applyRefund", HttpMethod.POST,
                new HttpEntity<>("{\"orderNo\":\"" + target.orderNo() + "\"}", headers(target, newToken())),
                String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).as("受理失败必须是可见的失败，不能返回已受理").isFalse();
        assertThat(refundCount(target)).isZero();
        assertThat(outboxCount(target)).isZero();
        assertThat(orderStatus(target)).isEqualTo(statusBefore);
    }

    @Test
    @DisplayName("AC2：工单不可达时受理照常提交，outbox 保留 PENDING 并记录退避")
    void unreachableWorkItemKeepsTheAcceptanceAndSchedulesRetry() throws Exception {
        RefundTarget target = refundableOrder();
        double missingBefore = missingCounter();
        workItems.unreachable();
        Instant acceptedAfter = Instant.now();

        JsonNode applied = apply(target);
        long refundId = applied.path("payload").path("refundId").asLong();

        assertThat(applied.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(applied.path("payload").path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(orderStatus(target)).isEqualTo("REFUNDING");
        assertThat(refundTicketId(refundId)).isNull();

        OutboxRow row = outboxRow(refundId);
        assertThat(row.tenantId()).isEqualTo(target.tenantId());
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(row.ticketId()).isNull();
        assertThat(row.lastError()).isNotBlank();
        assertThat(row.nextAttemptAt()).isAfter(acceptedAfter);
        assertThat(missingCounter()).isGreaterThan(missingBefore);
    }

    @Test
    @DisplayName("AC3：事务提交后立即补偿，成功时回填 ticket_id 并标 SENT")
    void acceptanceCompensatesImmediately() throws Exception {
        RefundTarget target = refundableOrder();
        JsonNode applied = apply(target);
        long refundId = applied.path("payload").path("refundId").asLong();

        OutboxRow row = outboxRow(refundId);
        assertThat(row.status()).isEqualTo("SENT");
        assertThat(row.ticketId()).isNotBlank();
        assertThat(refundTicketId(refundId)).isEqualTo(row.ticketId());
        assertThat(createdFor(refundId)).hasSize(1);
        assertThat(createdFor(refundId).get(0).payload()).contains("\"refundId\":\"" + refundId + "\"");
    }

    @Test
    @DisplayName("AC4：退避到期后由补偿器补开同一笔退款，租户上下文用完即清")
    void retryAfterBackoffCompensatesTheSameRefund() throws Exception {
        RefundTarget target = refundableOrder();
        workItems.unreachable();
        long refundId = apply(target).path("payload").path("refundId").asLong();
        OutboxRow failed = outboxRow(refundId);
        assertThat(failed.status()).isEqualTo("PENDING");

        workItems.reachable();
        processor.processDue(Instant.now().plus(Duration.ofMinutes(1)));

        OutboxRow sent = outboxRow(refundId);
        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.attempts()).as("补开成功不重置失败次数，便于对账").isEqualTo(1);
        assertThat(sent.ticketId()).isNotBlank();
        assertThat(refundTicketId(refundId)).isEqualTo(sent.ticketId());
        assertThat(createdFor(refundId)).hasSize(1);
        assertThat(TenantContextHolder.tenantIdOrNull()).as("补偿线程不能把租户上下文泄漏到下一轮").isNull();
        assertThat(TenantContextHolder.customerId()).isNull();
    }

    @Test
    @DisplayName("AC5：同一退款被重复扫描时只开一张单，已 SENT 的行不再调工单服务")
    void reprocessingASentRowDoesNotCreateASecondWorkItem() throws Exception {
        RefundTarget target = refundableOrder();
        long refundId = apply(target).path("payload").path("refundId").asLong();
        assertThat(createdFor(refundId)).hasSize(1);

        processor.processDue(Instant.now().plus(Duration.ofMinutes(1)));
        processor.processDue(Instant.now().plus(Duration.ofMinutes(2)));
        processor.processRefund(refundId, target.tenantId(), Instant.now().plus(Duration.ofMinutes(3)));

        assertThat(createdFor(refundId)).as("SENT 行不能被再次发送").hasSize(1);
        assertThat(outboxCount(target)).isEqualTo(1);
        assertThat(outboxRow(refundId).status()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("AC6：退款已放行后，尚未处理的 outbox 不再补开工作项")
    void reviewedRefundIsNotCompensatedLater() throws Exception {
        RefundTarget target = refundableOrder();
        workItems.unreachable();
        long refundId = apply(target).path("payload").path("refundId").asLong();
        assertThat(outboxRow(refundId).status()).isEqualTo("PENDING");

        workItems.reachable();
        JsonNode reviewed = review(target, refundId, "APPROVE");
        assertThat(reviewed.path("payload").path("status").asText()).isEqualTo("PROCESSING");
        assertThat(outboxRow(refundId).status()).isEqualTo("CANCELLED");

        processor.processDue(Instant.now().plus(Duration.ofMinutes(1)));

        assertThat(createdFor(refundId)).isEmpty();
        assertThat(refundTicketId(refundId)).isNull();
        assertThat(outboxRow(refundId).status()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("AC6：补偿器已认领 SENDING 行时，退款已放行必须取消该行而不是无限回收")
    void claimedRowIsCancelledWhenRefundIsAlreadyReviewed() throws Exception {
        RefundTarget target = refundableOrder();
        workItems.unreachable();
        long refundId = apply(target).path("payload").path("refundId").asLong();
        OutboxRow pending = outboxRow(refundId);
        assertThat(pending.status()).isEqualTo("PENDING");

        // 模拟审核发生在补偿器认领之后：退款已放行，但 outbox 仍是 SENDING（审核的
        // cancelByRefundId 只收 PENDING，不能替在途线程做决定）。
        jdbc.update("update refunds set status = 'PROCESSING' where id = ?", refundId);
        assertThat(outbox.claim(pending.id(), Instant.now().plusSeconds(5))).isTrue();
        assertThat(outboxRow(refundId).status()).isEqualTo("SENDING");

        // 租约过期后回收 → 重新认领 → 读到 PROCESSING → 必须把这一行收成 CANCELLED。
        processor.processDue(Instant.now().plus(Duration.ofMinutes(3)));

        assertThat(outboxRow(refundId).status()).isEqualTo("CANCELLED");
        assertThat(createdFor(refundId)).isEmpty();

        // 再扫一轮不能把已取消的行复活。
        processor.processDue(Instant.now().plus(Duration.ofMinutes(10)));
        assertThat(outboxRow(refundId).status()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("AC6：退款已驳回后，尚未处理的 outbox 同样不再补开工作项")
    void rejectedRefundIsNotCompensatedLater() throws Exception {
        RefundTarget target = refundableOrder();
        workItems.unreachable();
        long refundId = apply(target).path("payload").path("refundId").asLong();
        assertThat(outboxRow(refundId).status()).isEqualTo("PENDING");

        workItems.reachable();
        JsonNode reviewed = review(target, refundId, "REJECT");
        assertThat(reviewed.path("payload").path("status").asText()).isEqualTo("REJECTED");
        assertThat(outboxRow(refundId).status()).isEqualTo("CANCELLED");

        processor.processDue(Instant.now().plus(Duration.ofMinutes(1)));

        assertThat(createdFor(refundId)).isEmpty();
        assertThat(outboxRow(refundId).status()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("补偿器在请求线程内被立即调用时，恢复调用方租户上下文而不是清空")
    void compensationRestoresTheCallersTenantContext() throws Exception {
        RefundTarget target = refundableOrder();
        workItems.unreachable();
        long refundId = apply(target).path("payload").path("refundId").asLong();
        workItems.reachable();

        TenantContextHolder.set("T999", "C999");
        try {
            processor.processDue(Instant.now().plus(Duration.ofMinutes(1)));

            assertThat(TenantContextHolder.tenantIdOrNull()).isEqualTo("T999");
            assertThat(TenantContextHolder.customerId()).isEqualTo("C999");
        } finally {
            TenantContextHolder.clear();
        }
        assertThat(outboxRow(refundId).status()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("成功补偿计数在 Prometheus 抓取端使用稳定导出名")
    void compensationCounterIsExportedWithStableName() throws Exception {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        prometheus.counter(RefundWorkItemProcessor.COMPENSATED_METRIC).increment();
        String exposition = prometheus.scrape();

        assertThat(exposition)
                .as("文档/看板按这个名字配置；带 `_created` 会被 Micrometer 剥掉")
                .contains("shoppilot_refund_ticket_outbox_compensated_total")
                .doesNotContain("shoppilot_refund_ticket_outbox_created_total");
    }

    private JsonNode apply(RefundTarget target) throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/tools/applyRefund", HttpMethod.POST,
                new HttpEntity<>("{\"orderNo\":\"" + target.orderNo() + "\"}", headers(target, newToken())),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return JSON.readTree(response.getBody());
    }

    private JsonNode review(RefundTarget target, long refundId, String decision) throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/refunds/" + refundId + "/review", HttpMethod.POST,
                new HttpEntity<>("{\"decision\":\"" + decision + "\"}", headers(target, null)), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return JSON.readTree(response.getBody());
    }

    private List<InMemoryWorkItemClient.Created> createdFor(long refundId) {
        String marker = "\"refundId\":\"" + refundId + "\"";
        return workItems.created().stream().filter(created -> created.payload().contains(marker)).toList();
    }

    private OutboxRow outboxRow(long refundId) {
        return jdbc.queryForObject("""
                        select id, tenant_id, status, attempts, ticket_id, last_error, next_attempt_at
                          from refund_work_item_outbox
                         where refund_id = ?
                        """,
                (rs, rowNum) -> new OutboxRow(
                        rs.getLong("id"),
                        rs.getString("tenant_id"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getString("ticket_id"),
                        rs.getString("last_error"),
                        rs.getTimestamp("next_attempt_at").toInstant()),
                refundId);
    }

    private int outboxCount(RefundTarget target) {
        Integer count = jdbc.queryForObject(
                "select count(*) from refund_work_item_outbox where tenant_id = ? and order_id = ?",
                Integer.class, target.tenantId(), target.orderNo());
        return count == null ? 0 : count;
    }

    private int refundCount(RefundTarget target) {
        Integer count = jdbc.queryForObject(
                "select count(*) from refunds where tenant_id = ? and order_id = ?",
                Integer.class, target.tenantId(), target.orderNo());
        return count == null ? 0 : count;
    }

    private String refundTicketId(long refundId) {
        return jdbc.queryForObject("select ticket_id from refunds where id = ?", String.class, refundId);
    }

    private String orderStatus(RefundTarget target) {
        return jdbc.queryForObject("select status from orders where id = ? and tenant_id = ?",
                String.class, target.orderNo(), target.tenantId());
    }

    private double missingCounter() {
        return registry.get("shoppilot_refund_ticket_missing_total").counter().count();
    }

    private RefundTarget refundableOrder() {
        List<RefundTarget> found = jdbc.query(
                "select o.id, o.tenant_id, o.customer_id from orders o"
                        + " where o.status in ('PAID','SHIPPED','DELIVERED') and o.created_at > ?"
                        // 本类里驳回用例会把订单放回可退款态；AC1 要证明「零退款行、零 outbox」，
                        // 不能捡一张有历史退款/outbox 的订单来验回滚。
                        + " and not exists (select 1 from refunds r where r.tenant_id = o.tenant_id"
                        + "                   and r.order_id = o.id)"
                        + " and not exists (select 1 from refund_work_item_outbox x where x.tenant_id = o.tenant_id"
                        + "                   and x.order_id = o.id)"
                        + " limit 1",
                (rs, rowNum) -> new RefundTarget(rs.getString("tenant_id"), rs.getString("id"),
                        rs.getString("customer_id")),
                Instant.now().minusSeconds(6 * 24 * 3600));
        assertThat(found).as("种子数据里应存在可退款订单").isNotEmpty();
        return found.get(0);
    }

    private HttpHeaders headers(RefundTarget target, String idempotencyToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", TOKEN);
        headers.set("X-Tenant-Id", target.tenantId());
        headers.set("X-Customer-Id", target.customerId());
        headers.set("X-Actor", "agent-001");
        headers.set("X-Actor-Authenticated", "true");
        if (idempotencyToken != null) {
            headers.set("Idempotency-Token", idempotencyToken);
        }
        return headers;
    }

    private static String newToken() {
        return "outbox-" + System.nanoTime();
    }

    private record RefundTarget(String tenantId, String orderNo, String customerId) {
    }

    private record OutboxRow(long id, String tenantId, String status, int attempts, String ticketId,
                             String lastError, Instant nextAttemptAt) {
    }
}
