package com.shoppilot.bizmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 买家订单详情与物流轨迹（round44 票 189）。
 *
 * <p>走真实 HTTP 而不是直接调 service：这里要验的第一件事就是"租户与买家来自
 * {@code InternalAuthFilter} 写入的上下文"，跳过 HTTP 等于把前提当结论。
 *
 * <p>越权一律断言 **404 + 空体**：状态码与响应体两处都不能透露"这单存在但不是你的"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shoppilot.bizmock.seed.orders=300",
        "shoppilot.bizmock.seed.customers=10",
        "shoppilot.bizmock.internal-token=test-internal"
})
class OrderDetailQueryTest {

    private static final String TOKEN = "test-internal";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TENANT = "T001";
    private static final String BUYER = "C001";
    private static final String MASKED_PHONE = "^\\d{3}\\*{4}\\d{4}$";

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("本人可见：详情字段齐全，手机号在出站前已脱敏")
    void detailIsVisibleToItsOwnerAndThePhoneIsMasked() throws Exception {
        OrderRef own = firstOrder("tenant_id = 'T001' and customer_id = 'C001'");
        String rawPhone = jdbc.queryForObject(
                "select receiver_phone from orders where id = ? and tenant_id = ?", String.class, own.id(), own.tenantId());
        assertThat(rawPhone).as("种子里的手机号应是 11 位，否则这条断言没有意义").hasSize(11);

        ResponseEntity<String> response = get("/api/orders/mine/" + own.id(), TENANT, BUYER);
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.path("orderNo").asText()).isEqualTo(own.id());
        assertThat(body.path("address").path("receiverPhone").asText()).matches(MASKED_PHONE);
        assertThat(body.path("addressVersion").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(body.path("address").path("receiverName").asText()).isNotBlank();
        assertThat(body.path("address").path("province").asText()).isNotBlank();
        assertThat(body.has("tenantId")).as("身份字段不出站").isFalse();
        assertThat(body.has("customerId")).as("身份字段不出站").isFalse();
        assertThat(response.getBody()).as("原文手机号不能出现在响应体里").doesNotContain(rawPhone);
    }

    @Test
    @DisplayName("同店他人：404，且响应体不泄露订单存在")
    void detailIs404ForAnotherBuyerInTheSameTenant() throws Exception {
        OrderRef own = firstOrder("tenant_id = 'T001' and customer_id = 'C001'");
        ResponseEntity<String> response = get("/api/orders/mine/" + own.id(), TENANT, "C002");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNullOrEmpty();
    }

    @Test
    @DisplayName("跨店：404，与同店他人同形")
    void detailIs404AcrossTenants() throws Exception {
        OrderRef own = firstOrder("tenant_id = 'T001' and customer_id = 'C001'");
        ResponseEntity<String> response = get("/api/orders/mine/" + own.id(), "T002", BUYER);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNullOrEmpty();
    }

    @Test
    @DisplayName("缺买家上下文：404，不回退成整店可查")
    void detailIs404WithoutBuyerContext() {
        OrderRef own = firstOrder("tenant_id = 'T001' and customer_id = 'C001'");
        ResponseEntity<String> response = rest.exchange("/api/orders/mine/" + own.id(), HttpMethod.GET,
                new HttpEntity<>(headers(TENANT, null)), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNullOrEmpty();
    }

    @Test
    @DisplayName("轨迹按发生时间倒序：最新节点在最前")
    void logisticsAreNewestFirst() throws Exception {
        OrderRef shipped = firstOrder("""
                tenant_id = 'T001' and customer_id = 'C001' and exists (
                    select 1 from logistics l
                    where l.order_id = orders.id and l.tenant_id = orders.tenant_id)
                """);

        ResponseEntity<String> response = get("/api/orders/mine/" + shipped.id() + "/logistics", TENANT, BUYER);
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        JsonNode body = JSON.readTree(response.getBody());
        JsonNode nodes = body.path("nodes");
        assertThat(nodes.isArray()).isTrue();
        assertThat(nodes.size()).as("种子给已发货订单至少落两个节点").isGreaterThanOrEqualTo(2);
        assertThat(body.path("cpName").asText()).isNotBlank();
        assertThat(body.path("trackingNo").asText()).isNotBlank();

        List<Instant> stamps = new ArrayList<>();
        nodes.forEach(node -> stamps.add(Instant.parse(node.path("occurredAt").asText())));
        for (int i = 1; i < stamps.size(); i++) {
            assertThat(stamps.get(i)).as("第 %d 个节点不应晚于前一个", i).isBeforeOrEqualTo(stamps.get(i - 1));
        }
        assertThat(Instant.parse(nodes.get(0).path("occurredAt").asText()))
                .as("首元素就是最新节点")
                .isEqualTo(stamps.stream().max(Instant::compareTo).orElseThrow());
    }

    @Test
    @DisplayName("订单存在但没有轨迹：200 + 空数组，不是 404")
    void logisticsReturnAnEmptyListRatherThan404() throws Exception {
        OrderRef withoutNodes = firstOrder("""
                tenant_id = 'T001' and customer_id = 'C001' and not exists (
                    select 1 from logistics l
                    where l.order_id = orders.id and l.tenant_id = orders.tenant_id)
                """);

        ResponseEntity<String> response = get("/api/orders/mine/" + withoutNodes.id() + "/logistics", TENANT, BUYER);
        assertThat(response.getStatusCode().value()).as("说查不到这单会让买家以为下错了店").isEqualTo(200);

        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.path("orderNo").asText()).isEqualTo(withoutNodes.id());
        assertThat(body.path("nodes").isArray()).isTrue();
        assertThat(body.path("nodes").size()).isZero();
        assertThat(body.path("cpName").isNull()).isTrue();
    }

    @Test
    @DisplayName("物流轨迹同样按买家归属过滤：他人 404")
    void logisticsAreScopedToTheVerifiedBuyer() {
        OrderRef shipped = firstOrder("""
                tenant_id = 'T001' and customer_id = 'C001' and exists (
                    select 1 from logistics l
                    where l.order_id = orders.id and l.tenant_id = orders.tenant_id)
                """);
        ResponseEntity<String> response = get("/api/orders/mine/" + shipped.id() + "/logistics", TENANT, "C002");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNullOrEmpty();
    }

    private OrderRef firstOrder(String where) {
        return jdbc.queryForObject(
                "select id, tenant_id, customer_id from orders where " + where + " limit 1",
                (rs, rowNum) -> new OrderRef(rs.getString("id"), rs.getString("tenant_id"), rs.getString("customer_id")));
    }

    private ResponseEntity<String> get(String path, String tenantId, String customerId) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(tenantId, customerId)), String.class);
    }

    private HttpHeaders headers(String tenantId, String customerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", TOKEN);
        headers.set("X-Tenant-Id", tenantId);
        if (customerId != null) {
            headers.set("X-Customer-Id", customerId);
        }
        return headers;
    }

    private record OrderRef(String id, String tenantId, String customerId) {
    }
}
