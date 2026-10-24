package com.shoppilot.ticket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** H2：跨进程 blocked Handoff 工单必须保留编排 payload。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shoppilot.ticket.internal-token=test-internal"
})
class BlockedHandoffPayloadTest {

    private static final String TOKEN = "test-internal";

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("source=DEGRADE 时 payload JSON 落库并从坐席视图返回")
    void storesOrchestrationPayloadWhenSourceIsNamed() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", TOKEN);
        headers.set("X-Tenant-Id", "T001");
        headers.set("X-Customer-Id", "C001");
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        Map<String, Object> orchestration = Map.of(
                "agentRole", "AFTER_SALES",
                "blockedReason", "DEPTH_EXHAUSTED",
                "confirmedSlots", Map.of("orderNo", "90002"));
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("source", "DEGRADE");
        request.put("customerId", "C001");
        request.put("reason", "INTENT_UNRESOLVED");
        request.put("userQuery", "帮我查订单 90002 的物流");
        request.put("transcript", "handoff blocked reason=DEPTH_EXHAUSTED");
        request.put("payload", orchestration);
        request.put("channel", "web");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        request.put("payload", mapper.writeValueAsString(orchestration));
        String body = mapper.writeValueAsString(request);
        ResponseEntity<String> created = rest.exchange("/api/tickets", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody()).contains("\\\"blockedReason\\\":\\\"DEPTH_EXHAUSTED\\\"");
        assertThat(created.getBody()).contains("90002");
    }
}
