package com.shoppilot.bizmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.bizmock.audit.AuditChannel;
import com.shoppilot.bizmock.audit.InMemoryAuditChannel;
import com.shoppilot.bizmock.workitem.InMemoryWorkItemClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款在办唯一性（2026-10-22 评审 P0）。
 *
 * <p>此前 {@code applyRefund} 在事务外读订单判 refundable()，而唯一键只有
 * (order_id, idempotency_token)：不同 token 的并发请求都能各插一笔退款。
 * 这里**绕过网关的 Redisson 订单锁**，直接打 biz-mock，证明数据库层自己是守得住的。
 *
 * <p>同时钉「工单调用移出事务」：工单服务不可达时退款仍提交，ticketId 为空，
 * 缺失计数 +1；不能因为开不出单让买家的退款卡在事务里。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:refund-invariant;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "shoppilot.bizmock.seed.orders=400",
        "shoppilot.bizmock.seed.customers=10",
        "shoppilot.bizmock.internal-token=test-internal"
})
class RefundInvariantTest {

    private static final String TOKEN = "test-internal";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    InMemoryWorkItemClient workItems;
    @Autowired
    MeterRegistry registry;

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
    }

    @Test
    @DisplayName("并发不同 token：一单只落一笔退款，另一路 STATE_NOT_ALLOWED")
    void concurrentRefundsWithDifferentTokensOnlyOneWins() throws Exception {
        List<RefundTarget> targets = refundableOrders(5);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (RefundTarget target : targets) {
                CountDownLatch start = new CountDownLatch(1);
                List<Callable<JsonNode>> calls = List.of(
                        () -> applyAfter(start, target, "race-a-" + System.nanoTime()),
                        () -> applyAfter(start, target, "race-b-" + System.nanoTime()));
                List<Future<JsonNode>> futures = new ArrayList<>();
                for (Callable<JsonNode> call : calls) {
                    futures.add(pool.submit(call));
                }
                start.countDown();
                JsonNode first = futures.get(0).get();
                JsonNode second = futures.get(1).get();
                List<String> statuses = List.of(first.path("status").asText(), second.path("status").asText());

                assertThat(statuses).as("订单 %s 的两路结果", target.orderNo())
                        .containsExactlyInAnyOrder("PENDING_APPROVAL", "STATE_NOT_ALLOWED");
                assertThat(refundCount(target)).as("订单 %s 的退款行数", target.orderNo()).isEqualTo(1);
                assertThat(activeOrderId(target)).as("订单 %s 的在办唯一键", target.orderNo())
                        .isEqualTo(target.orderNo());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("工单服务不可达：退款仍提交，ticketId 为空，缺失计数 +1")
    void workItemFailureDoesNotBlockRefund() throws Exception {
        RefundTarget target = refundableOrders(1).get(0);
        double before = registry.get("shoppilot_refund_ticket_missing_total").counter().count();
        workItems.unreachable();

        JsonNode applied = apply(target, "workitem-down-" + System.nanoTime());

        assertThat(applied.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForObject(
                "select ticket_id from refunds where order_id = ? and tenant_id = ?",
                String.class, target.orderNo(), target.tenantId())).isNull();
        assertThat(registry.get("shoppilot_refund_ticket_missing_total").counter().count())
                .isGreaterThan(before);
    }

    private JsonNode applyAfter(CountDownLatch start, RefundTarget target, String token) throws Exception {
        start.await();
        return apply(target, token);
    }

    private JsonNode apply(RefundTarget target, String token) throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/tools/applyRefund", HttpMethod.POST,
                new HttpEntity<>("{\"orderNo\":\"" + target.orderNo() + "\"}", headers(target, token)),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return JSON.readTree(response.getBody());
    }

    private int refundCount(RefundTarget target) {
        Integer count = jdbc.queryForObject(
                "select count(*) from refunds where order_id = ? and tenant_id = ?",
                Integer.class, target.orderNo(), target.tenantId());
        return count == null ? 0 : count;
    }

    private String activeOrderId(RefundTarget target) {
        return jdbc.queryForObject(
                "select active_order_id from refunds where order_id = ? and tenant_id = ?",
                String.class, target.orderNo(), target.tenantId());
    }

    private List<RefundTarget> refundableOrders(int limit) {
        List<RefundTarget> found = jdbc.query(
                "select id, tenant_id, customer_id from orders"
                        + " where status in ('PAID','SHIPPED','DELIVERED') and created_at > ? limit ?",
                (rs, rowNum) -> new RefundTarget(rs.getString("tenant_id"), rs.getString("id"),
                        rs.getString("customer_id")),
                Instant.now().minusSeconds(6 * 24 * 3600), limit);
        assertThat(found).as("种子数据里应存在可退款订单").hasSizeGreaterThanOrEqualTo(limit);
        return found;
    }

    private HttpHeaders headers(RefundTarget target, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", TOKEN);
        headers.set("X-Tenant-Id", target.tenantId());
        headers.set("X-Customer-Id", target.customerId());
        headers.set("X-Actor", "agent-001");
        headers.set("X-Actor-Authenticated", "true");
        headers.set("Idempotency-Token", token);
        return headers;
    }

    private record RefundTarget(String tenantId, String orderNo, String customerId) {
    }
}
