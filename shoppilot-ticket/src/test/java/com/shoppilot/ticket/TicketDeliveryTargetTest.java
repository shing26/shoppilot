package com.shoppilot.ticket;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单带渠道与投递目标（round26 票 86 / ADR 0059 第 2 条）。
 *
 * <p>在这之前，买家从哪个渠道来、回我地址是多少，只以 {@code transcript} 里一句
 * 「【回执渠道】{contact}」的人类可读文本存在。拿文本当投递目标，等于让一次文案改动
 * 决定消息发不发——所以本票把这两格变成列。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shoppilot.ticket.internal-token=test-internal"
})
class TicketDeliveryTargetTest {

    private static final String TOKEN = "test-internal";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("带渠道与目标的工单：两列都落库，且能从视图读回")
    void channelAndContactAreStoredAndReadBack() throws Exception {
        JsonNode created = json(create(
                "{\"customerId\":\"C001\",\"reason\":\"TOOL_UNAVAILABLE\",\"userQuery\":\"q\",\"transcript\":\"t\","
                        + "\"channel\":\"webhook\",\"contact\":\"https://shop.example/hook\"}"));

        assertThat(created.path("channel").asText()).isEqualTo("webhook");
        assertThat(created.path("contact").asText()).isEqualTo("https://shop.example/hook");
        // 读回同一张：两格不是只在创建响应里出现过
        assertThat(json(get(created.path("id").asText())).path("contact").asText())
                .isEqualTo("https://shop.example/hook");
    }

    @Test
    @DisplayName("两格都为空是最常见的形态，不影响任何既有行为（复核单/退款审批单/web 降级单）")
    void missingChannelIsTheCommonCase() throws Exception {
        JsonNode created = json(create(
                "{\"customerId\":\"C001\",\"reason\":\"FEEDBACK_REVIEW\",\"userQuery\":\"q\",\"transcript\":\"t\","
                        + "\"source\":\"FEEDBACK_REVIEW\"}"));

        assertThat(emptyOf(created, "channel")).as("没有渠道").isTrue();
        assertThat(emptyOf(created, "contact")).as("没有投递目标").isTrue();
        assertThat(created.path("status").asText()).as("既有字段一字未改").isEqualTo("OPEN");
        assertThat(created.path("source").asText()).isEqualTo("FEEDBACK_REVIEW");
    }

    @Test
    @DisplayName("空白串按没有处理：不给库里塞一串空格的渠道名")
    void blankChannelBecomesNull() throws Exception {
        JsonNode created = json(create(
                "{\"customerId\":\"C001\",\"reason\":\"TOOL_UNAVAILABLE\",\"userQuery\":\"q\",\"transcript\":\"t\","
                        + "\"channel\":\"  \",\"contact\":\"  \"}"));

        assertThat(emptyOf(created, "channel")).isTrue();
        assertThat(emptyOf(created, "contact")).isTrue();
    }

    @Test
    @DisplayName("投递目标不改变隔离口径：跨租户读不到别店那张单")
    void contactDoesNotWeakenTenantIsolation() throws Exception {
        JsonNode created = json(create(
                "{\"customerId\":\"C001\",\"reason\":\"TOOL_UNAVAILABLE\",\"userQuery\":\"q\",\"transcript\":\"t\","
                        + "\"channel\":\"email\",\"contact\":\"buyer@example.com\"}"));

        ResponseEntity<String> crossTenant = rest.exchange("/api/tickets/" + created.path("id").asText(),
                HttpMethod.GET, new HttpEntity<>(headers("T999")), String.class);

        assertThat(crossTenant.getStatusCode().value()).as("有投递目标也不跨租户").isEqualTo(404);
    }

    private ResponseEntity<String> create(String body) {
        return rest.exchange("/api/tickets", HttpMethod.POST, new HttpEntity<>(body, headers("T001")), String.class);
    }

    private ResponseEntity<String> get(String id) {
        return rest.exchange("/api/tickets/" + id, HttpMethod.GET, new HttpEntity<>(headers("T001")), String.class);
    }

    private HttpHeaders headers(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", TOKEN);
        headers.set("X-Tenant-Id", tenantId);
        headers.set("X-Customer-Id", "C001");
        return headers;
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("请求应成功，实际 %s: %s", response.getStatusCode(), response.getBody()).isTrue();
        return JSON.readTree(response.getBody());
    }

    /**
     * 「这一格是空的」，而不断言它长什么样。
     *
     * <p>本服务配了 {@code spring.jackson.default-property-inclusion: non_null}，
     * 所以 null 字段**整格不出现在 JSON 里**——{@code path("x").isNull()} 断的是序列化器的
     * 一个实现细节（那个格子干脆不存在，{@code path()} 给回 MissingNode），而不是业务语义。
     * {@code asText()} 对 null 与 missing 都给空串，两种序列化策略下这条断言都成立。
     */
    private static boolean emptyOf(JsonNode node, String field) {
        return node.path(field).asText().isEmpty();
    }
}