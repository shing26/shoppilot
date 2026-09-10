package com.shoppilot.bizmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 两道否决项的可运行反证：跨租户与跨买家越权一律 NOT_FOUND；同幂等 token 并发退款只落一行。
 *
 * <p>这里走真实 HTTP 而不是直接调 service：隔离的第一道防线是 Header 到
 * {@code TenantContextHolder} 的传递，跳过 HTTP 就等于把要验的东西当成前提用掉了。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shoppilot.bizmock.seed.orders=300",
        "shoppilot.bizmock.seed.customers=10",
        "shoppilot.bizmock.internal-token=test-internal"
})
class TenantIsolationAndIdempotencyTest {

    private static final String TOKEN = "test-internal";
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("没有内部 token 的工具调用一律 401")
    void rejectsCallsWithoutInternalToken() throws Exception {
        // 用 JDK HttpClient 而不是 TestRestTemplate：后者底层的 HttpURLConnection 在流式模式下
        // 遇到 401 会抛 HttpRetryException，把"被正确拒绝"变成一次 I/O 错误
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/tools/queryOrderDetail"))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", "T001")
                .POST(HttpRequest.BodyPublishers.ofString("{\"orderNo\":\"10001\"}"))
                .build();

        HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("订单归属双条件：换店不可见，换人也不可见，且都归入 NOT_FOUND")
    void orderIsVisibleOnlyToItsOwner() throws Exception {
        assertThat(call("/api/tools/queryOrderDetail", "{\"orderNo\":\"10001\"}", "T001", "C001", null)
                .path("status").asText()).isEqualTo("OK");
        assertThat(call("/api/tools/queryOrderDetail", "{\"orderNo\":\"10001\"}", "T002", "C001", null)
                .path("status").asText()).isEqualTo("NOT_FOUND");
        assertThat(call("/api/tools/queryOrderDetail", "{\"orderNo\":\"10001\"}", "T001", "C002", null)
                .path("status").asText()).isEqualTo("NOT_FOUND");
    }

    /**
     * 两个读工具共用同一道归属闸门，所以要各验一次。
     *
     * <p>动机不是重复劳动：ticket 20 把「到哪了」这类问句的合格答案集放开成
     * {queryOrderDetail, queryLogistics} 之后，越权探针可能由物流这一侧打出去，而评测侧
     * 当年并未真正观测到这两条的 NOT_FOUND（判据在期望工具缺失时静默给 True，见 ADR 0021）。
     * 归属校验下沉到 JVM 才钉得住。
     *
     * <p>正向只断"不是 NOT_FOUND"：10001 的状态由随机种子决定，未发货时它该回
     * STATE_NOT_ALLOWED，而不是对归属者谎称查不到这单（ticket 03 修过这个谎）。
     * 三条合起来使"归属谓词被拿掉"时这条用例必然变红。
     */
    @Test
    @DisplayName("物流查询与订单详情共用归属闸门：换店、换人都归入 NOT_FOUND")
    void logisticsSharesTheSameOwnershipGate() throws Exception {
        assertThat(call("/api/tools/queryLogistics", "{\"orderNo\":\"10001\"}", "T001", "C001", null)
                .path("status").asText()).isNotEqualTo("NOT_FOUND");
        assertThat(call("/api/tools/queryLogistics", "{\"orderNo\":\"10001\"}", "T002", "C001", null)
                .path("status").asText()).isEqualTo("NOT_FOUND");
        assertThat(call("/api/tools/queryLogistics", "{\"orderNo\":\"10001\"}", "T001", "C002", null)
                .path("status").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("订单存在但没有物流轨迹时不许回 NOT_FOUND；退款只报订单号也能落库")
    void logisticsNeverClaimsAnExistingOrderIsMissing() throws Exception {
        // 只给订单号的退款：reason 与 amountFen 都是可选项，biz-mock 侧必须补默认值，
        // 否则网关判成"参数缺失"，顾客报过订单号还会被反问一遍（dev 评测 ACT-RFD-17 实测）。
        RefundTarget target = findRefundableOrder();
        String refundToken = "norefund-args-" + System.nanoTime();
        JsonNode refund = call("/api/tools/applyRefund", "{\"orderNo\":\"" + target.orderNo() + "\"}",
                target.tenantId(), target.customerId(), refundToken);
        assertThat(refund.path("status").asText()).isEqualTo("OK");
        // RefundView 不回传 reason，落库侧验默认值才算数
        List<String> stored = jdbc.queryForList(
                "select reason || '|' || amount_fen from refunds where order_id = ? and idempotency_token = ?",
                String.class, target.orderNo(), refundToken);
        assertThat(stored).containsExactly("买家主观原因|" + target.amountFen());

        // 退款把订单推到 REFUNDING：既不是未发货那条 STATE_NOT_ALLOWED，也不会有轨迹节点。
        // 这一步以前回 NOT_FOUND，助手因此对顾客说"可能不是本店下单"——同一句判据在 ACT-ORD-17 上抓到过。
        JsonNode logistics = call("/api/tools/queryLogistics", "{\"orderNo\":\"" + target.orderNo() + "\"}",
                target.tenantId(), target.customerId(), null);
        assertThat(logistics.path("status").asText()).isNotEqualTo("NOT_FOUND");
        assertThat(logistics.path("message").asText()).contains(target.orderNo());
    }

    @Test
    @DisplayName("并发 50 次同 token 退款只生成 1 条记录")
    void concurrentRefundWithSameTokenCreatesSingleRow() throws Exception {
        RefundTarget target = findRefundableOrder();
        String idempotencyToken = "it-" + System.nanoTime();
        String body = "{\"orderNo\":\"" + target.orderNo() + "\",\"reason\":\"生鲜破损\",\"amountFen\":"
                + target.amountFen() + "}";

        int attempts = 50;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<String> statuses = new ArrayList<>();
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                tasks.add(() -> call("/api/tools/applyRefund", body, target.tenantId(), target.customerId(),
                        idempotencyToken).path("status").asText());
            }
            for (Future<String> result : pool.invokeAll(tasks)) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses.stream().filter("OK"::equals).count()).isEqualTo(1);
        assertThat(statuses.stream().filter("IDEMPOTENT_REPLAY"::equals).count()).isEqualTo(attempts - 1L);
        Integer rows = jdbc.queryForObject(
                "select count(*) from refunds where order_id = ? and idempotency_token = ?", Integer.class,
                target.orderNo(), idempotencyToken);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("唯一约束是最后一道防线：绕过服务层直接重复插入同样被数据库拦下")
    void databaseConstraintRejectsDuplicateRefund() {
        RefundTarget target = findRefundableOrder();
        String token = "direct-" + System.nanoTime();
        insertRefundDirectly(target, token);

        assertThrows(DataIntegrityViolationException.class, () -> insertRefundDirectly(target, token));
    }

    private void insertRefundDirectly(RefundTarget target, String token) {
        jdbc.update("insert into refunds (tenant_id, order_id, customer_id, amount_fen, reason, idempotency_token,"
                        + " status, created_at) values (?,?,?,?,?,?,?,?)",
                target.tenantId(), target.orderNo(), target.customerId(), target.amountFen(), "并发测试", token,
                "PROCESSING", Instant.now());
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

    private JsonNode call(String path, String body, String tenantId, String customerId, String idempotencyToken)
            throws Exception {
        ResponseEntity<String> response = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers(idempotencyToken, tenantId, customerId)), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return JSON.readTree(response.getBody());
    }

    private HttpHeaders headers(String idempotencyToken, String tenantId, String customerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", TOKEN);
        headers.set("X-Tenant-Id", tenantId);
        headers.set("X-Customer-Id", customerId);
        if (idempotencyToken != null) {
            headers.set("Idempotency-Token", idempotencyToken);
        }
        return headers;
    }

    private record RefundTarget(String tenantId, String orderNo, String customerId, long amountFen) {
    }
}
