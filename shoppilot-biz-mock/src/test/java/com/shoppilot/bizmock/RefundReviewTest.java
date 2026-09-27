package com.shoppilot.bizmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * 退款审批闸门（ADR 0047）：受理与放行拆成两态，资金放行只能由审核动作推进。
 *
 * <p>走真实 HTTP（与 {@link TenantIsolationAndIdempotencyTest} 同理）：审核端点的租户上下文来自
 * Header → {@code TenantContextHolder}，跳过 HTTP 等于把要验的隔离当成前提用掉。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // 独立 H2：本用例会真的把订单推成 REFUNDING / 回滚，共用默认库会影响同包其它用例
        // （findRefundableOrder 取"第一条可退单"，池子被本类改掉即换单）。SlowQueryPlanTest 同做法。
        "spring.datasource.url=jdbc:h2:mem:refund-review;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "shoppilot.bizmock.seed.orders=300",
        "shoppilot.bizmock.seed.customers=10",
        "shoppilot.bizmock.internal-token=test-internal"
})
class RefundReviewTest {

    private static final String TOKEN = "test-internal";
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("申请退款落 PENDING_REVIEW 并回 PENDING_APPROVAL；订单受理即冻结")
    void applyingRefundLandsInPendingReview() throws Exception {
        RefundTarget target = findRefundableOrder();
        JsonNode refund = applyRefund(target, "review-apply-" + System.nanoTime());

        assertThat(refund.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(refund.path("payload").path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(orderStatus(target.orderNo(), target.tenantId())).isEqualTo("REFUNDING");
    }

    @Test
    @DisplayName("待审核的申请出现在审核队列里")
    void pendingRefundShowsUpInQueue() throws Exception {
        RefundTarget target = findRefundableOrder();
        long refundId = applyRefund(target, "review-queue-" + System.nanoTime()).path("payload").path("refundId").asLong();

        List<String> ids = pendingIds(target.tenantId());
        assertThat(ids).contains(String.valueOf(refundId));
    }

    @Test
    @DisplayName("放行后进入 PROCESSING，订单保持 REFUNDING（资金已放行）")
    void approvingMovesRefundToProcessing() throws Exception {
        RefundTarget target = findRefundableOrder();
        long refundId = applyRefund(target, "review-ok-" + System.nanoTime()).path("payload").path("refundId").asLong();

        JsonNode reviewed = review(target.tenantId(), refundId, "APPROVE", null);
        assertThat(reviewed.path("status").asText()).isEqualTo("OK");
        assertThat(reviewed.path("payload").path("status").asText()).isEqualTo("PROCESSING");
        assertThat(orderStatus(target.orderNo(), target.tenantId())).isEqualTo("REFUNDING");
    }

    @Test
    @DisplayName("驳回按时间戳推导回滚订单，买家可以再申请")
    void rejectingRollsBackTheOrderAndAllowsReapply() throws Exception {
        RefundTarget target = findRefundableOrder();
        String originalStatus = orderStatus(target.orderNo(), target.tenantId());
        long refundId = applyRefund(target, "review-reject-" + System.nanoTime()).path("payload").path("refundId").asLong();

        JsonNode reviewed = review(target.tenantId(), refundId, "REJECT", "凭证不符");
        assertThat(reviewed.path("payload").path("status").asText()).isEqualTo("REJECTED");
        // 先前状态无损推导：回到申请前的那个状态
        assertThat(orderStatus(target.orderNo(), target.tenantId())).isEqualTo(originalStatus);

        // 回滚后可再申请：refundable() 重新为真，第二次受理成功
        JsonNode second = applyRefund(target, "review-reapply-" + System.nanoTime());
        assertThat(second.path("status").asText()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("已审过的申请再审一律 409（STATE_NOT_ALLOWED）")
    void reviewingTwiceIsRejected() throws Exception {
        RefundTarget target = findRefundableOrder();
        long refundId = applyRefund(target, "review-twice-" + System.nanoTime()).path("payload").path("refundId").asLong();
        review(target.tenantId(), refundId, "APPROVE", null);

        ResponseEntity<String> again = rest.exchange(reviewUrl(refundId), HttpMethod.POST,
                new HttpEntity<>("{\"decision\":\"REJECT\"}", headers(target.tenantId())), String.class);

        assertThat(again.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("跨租户看不到、也审不了别店的退款申请（404）")
    void reviewIsTenantScoped() throws Exception {
        RefundTarget target = findRefundableOrder();
        long refundId = applyRefund(target, "review-tenant-" + System.nanoTime()).path("payload").path("refundId").asLong();

        ResponseEntity<String> crossTenant = rest.exchange(reviewUrl(refundId), HttpMethod.POST,
                new HttpEntity<>("{\"decision\":\"APPROVE\"}", headers("T999")), String.class);

        assertThat(crossTenant.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("不存在的申请号 404；未知审核决定 400")
    void unknownRefundAndDecision() throws Exception {
        ResponseEntity<String> missing = rest.exchange(reviewUrl(999_999_999L), HttpMethod.POST,
                new HttpEntity<>("{\"decision\":\"APPROVE\"}", headers("T001")), String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);

        RefundTarget target = findRefundableOrder();
        long refundId = applyRefund(target, "review-bad-" + System.nanoTime()).path("payload").path("refundId").asLong();
        ResponseEntity<String> badDecision = rest.exchange(reviewUrl(refundId), HttpMethod.POST,
                new HttpEntity<>("{\"decision\":\"MAYBE\"}", headers(target.tenantId())), String.class);
        assertThat(badDecision.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("买家读回：queryOrderDetail 带退款审核态，三态可分辨且写明到账边界（票 60）")
    void buyerReadbackDistinguishesTheThreeRefundStates() throws Exception {
        RefundTarget pendingTarget = findRefundableOrder();
        long pendingId = applyRefund(pendingTarget, "readback-pending-" + System.nanoTime())
                .path("payload").path("refundId").asLong();
        JsonNode pending = orderDetail(pendingTarget);
        assertThat(pending.path("payload").path("refundReview").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(pending.path("message").asText()).contains("等待人工审核").contains("支付渠道");

        RefundTarget releasedTarget = findRefundableOrder();
        long releasedId = applyRefund(releasedTarget, "readback-released-" + System.nanoTime())
                .path("payload").path("refundId").asLong();
        review(releasedTarget.tenantId(), releasedId, "APPROVE", null);
        JsonNode released = orderDetail(releasedTarget);
        assertThat(released.path("payload").path("refundReview").asText()).isEqualTo("RELEASED");
        // 到账边界必须写下来：这是 REFUNDED 终态不推进之后，买家唯一能拿到的出口
        assertThat(released.path("message").asText()).contains("已放行").contains("支付渠道");

        RefundTarget rejectedTarget = findRefundableOrder();
        long rejectedId = applyRefund(rejectedTarget, "readback-rejected-" + System.nanoTime())
                .path("payload").path("refundId").asLong();
        review(rejectedTarget.tenantId(), rejectedId, "REJECT", null);
        JsonNode rejected = orderDetail(rejectedTarget);
        assertThat(rejected.path("payload").path("refundReview").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("message").asText()).contains("驳回");
    }

    private JsonNode orderDetail(RefundTarget target) throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/tools/queryOrderDetail", HttpMethod.POST,
                new HttpEntity<>("{\"orderNo\":\"" + target.orderNo() + "\"}",
                        headers(target.tenantId(), target.customerId(), null)),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return JSON.readTree(response.getBody());
    }

    private JsonNode applyRefund(RefundTarget target, String token) throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/tools/applyRefund", HttpMethod.POST,
                new HttpEntity<>("{\"orderNo\":\"" + target.orderNo() + "\"}",
                        headers(target.tenantId(), target.customerId(), token)),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return JSON.readTree(response.getBody());
    }

    private JsonNode review(String tenantId, long refundId, String decision, String note) throws Exception {
        String body = "{\"decision\":\"" + decision + "\"" + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
        ResponseEntity<String> response = rest.exchange(reviewUrl(refundId), HttpMethod.POST,
                new HttpEntity<>(body, headers(tenantId)), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return JSON.readTree(response.getBody());
    }

    private List<String> pendingIds(String tenantId) throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/refunds/pending", HttpMethod.GET,
                new HttpEntity<>(headers(tenantId)), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode array = JSON.readTree(response.getBody());
        List<String> ids = new java.util.ArrayList<>();
        array.forEach(node -> ids.add(node.path("refundId").asText()));
        return ids;
    }

    private String orderStatus(String orderNo, String tenantId) {
        return jdbc.queryForObject("select status from orders where id = ? and tenant_id = ?", String.class,
                orderNo, tenantId);
    }

    private String reviewUrl(long refundId) {
        return "/api/refunds/" + refundId + "/review";
    }

    private HttpHeaders headers(String tenantId) {
        return headers(tenantId, null, null);
    }

    private HttpHeaders headers(String tenantId, String customerId, String idempotencyToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", TOKEN);
        headers.set("X-Tenant-Id", tenantId);
        if (customerId != null) {
            headers.set("X-Customer-Id", customerId);
        }
        if (idempotencyToken != null) {
            headers.set("Idempotency-Token", idempotencyToken);
        }
        return headers;
    }

    private RefundTarget findRefundableOrder() {
        List<RefundTarget> found = jdbc.query(
                "select id, tenant_id, customer_id, amount_fen from orders"
                        + " where status in ('PAID','SHIPPED','DELIVERED') and created_at > ? limit 5",
                (rs, rowNum) -> new RefundTarget(rs.getString("tenant_id"), rs.getString("id"),
                        rs.getString("customer_id"), rs.getLong("amount_fen")),
                Instant.now().minusSeconds(6 * 24 * 3600));
        assertThat(found).as("种子数据里应存在可退款订单").isNotEmpty();
        return found.get(0);
    }

    private record RefundTarget(String tenantId, String orderNo, String customerId, long amountFen) {
    }
}
