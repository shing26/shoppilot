package com.shoppilot.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.ticket.domain.TicketStatus;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单生命周期与坐席动作（round23 票 72 / ADR 0055）。
 *
 * <p>走真实 HTTP：租户上下文来自 Header，跳过 HTTP 等于把要验的隔离当成前提用掉。
 * 状态机与领取的语义一个字都没改，搬的是**进程**，不是行为。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shoppilot.ticket.internal-token=test-internal"
})
class TicketWorkflowTest {

    private static final String TOKEN = "test-internal";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("工单按 OPEN -> ASSIGNED -> RESOLVED 正常流转")
    void walksTheHappyPath() throws Exception {
        String id = createTicket("TOOL_UNAVAILABLE");

        assertThat(claim(id, "alice")).as("首次领取成功").isEqualTo(204);
        assertThat(statusOf(get(id))).isEqualTo("ASSIGNED");
        assertThat(resolve(id, "alice")).isEqualTo(204);
        assertThat(statusOf(get(id))).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("同一张单恰好一人领得到：并发领取只有一人 204，其余 409")
    void onlyOneAgentCanClaim() throws Exception {
        String id = createTicket("TOOL_UNAVAILABLE");

        List<Callable<Integer>> attempts = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                String agent = "agent-" + i;
                attempts.add(() -> claim(id, agent));
            }
            List<Future<Integer>> results = pool.invokeAll(attempts);
            int granted = 0;
            for (Future<Integer> result : results) {
                if (result.get() == 204) {
                    granted++;
                }
            }
            assertThat(granted).as("乐观锁的承重格：不能两个坐席都领到同一张单").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("只有领取人能结单；已结单不能再流转")
    void onlyTheClaimantCanResolve() throws Exception {
        String id = createTicket("SLOT_UNRESOLVED");
        claim(id, "alice");

        assertThat(resolve(id, "bob")).as("不是你的单，结不掉").isEqualTo(409);
        assertThat(resolve(id, "alice")).isEqualTo(204);
        assertThat(claim(id, "carol")).as("已结单不可再领").isEqualTo(409);
    }

    @Test
    @DisplayName("释放只允许释放自己领的那张")
    void onlyTheClaimantCanRelease() throws Exception {
        String id = createTicket("TOOL_UNAVAILABLE");
        claim(id, "alice");

        assertThat(release(id, "bob")).isEqualTo(409);
        assertThat(release(id, "alice")).isEqualTo(204);
        assertThat(statusOf(get(id))).as("释放回到 OPEN").isEqualTo("OPEN");
    }

    @Test
    @DisplayName("跨租户看不到也领不了别店的工单")
    void ticketsAreTenantScoped() throws Exception {
        String id = createTicket("TOOL_UNAVAILABLE");

        ResponseEntity<String> crossTenant = get("/api/tickets/" + id, "T999");
        assertThat(crossTenant.getStatusCode().value()).isEqualTo(404);
        assertThat(claim(id, "alice", "T999")).as("跨租户领取被拒").isEqualTo(404);
    }

    @Test
    @DisplayName("状态机本身：RESOLVED 是终态，OPEN 允许直接结单")
    void transitionTable() {
        assertThat(TicketStatus.OPEN.canTransitionTo(TicketStatus.ASSIGNED)).isTrue();
        assertThat(TicketStatus.OPEN.canTransitionTo(TicketStatus.RESOLVED)).isTrue();
        assertThat(TicketStatus.ASSIGNED.canTransitionTo(TicketStatus.OPEN)).isFalse();
        assertThat(TicketStatus.RESOLVED.canTransitionTo(TicketStatus.ASSIGNED)).isFalse();
        assertThat(TicketStatus.parse(" resolved ")).isEqualTo(TicketStatus.RESOLVED);
        assertThat(TicketStatus.parse("nonsense")).isNull();
    }

    // --- helpers ---------------------------------------------------------------------------

    private String createTicket(String reason) throws Exception {
        ResponseEntity<String> created = send("POST", "/api/tickets", "T001", "C001",
                "{\"customerId\":\"C001\",\"reason\":\"" + reason
                        + "\",\"userQuery\":\"order not found\",\"transcript\":\"t\"}");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return json(created).path("id").asText();
    }

    private int claim(String id, String agent) {
        return claim(id, agent, "T001");
    }

    private int claim(String id, String agent, String tenantId) {
        return send("POST", "/api/tickets/" + id + "/claim", tenantId, null, null, agent).getStatusCode().value();
    }

    private int release(String id, String agent) {
        return send("POST", "/api/tickets/" + id + "/release", "T001", null, null, agent).getStatusCode().value();
    }

    private int resolve(String id, String agent) {
        return send("POST", "/api/tickets/" + id + "/resolve", "T001", null, "{\"note\":\"处理完了\"}", agent)
                .getStatusCode().value();
    }

    private ResponseEntity<String> get(String path, String tenantId) {
        return send("GET", path, tenantId, null, null, null);
    }

    private ResponseEntity<String> get(String id) {
        return get("/api/tickets/" + id, "T001");
    }

    private ResponseEntity<String> send(String method, String path, String tenantId, String customerId, String body) {
        return send(method, path, tenantId, customerId, body, null);
    }

    /**
     * 动作端点走 JDK HttpClient 而不是 TestRestTemplate：后者底层 HttpURLConnection 不支持 PATCH，
     * 会把一次本该断言状态码的请求变成传输异常（同 biz-mock 侧的老教训）。
     * 这里是 POST，所以 TestRestTemplate 够用；保留这个重载只是为了 agent 头透传。
     */
    private ResponseEntity<String> send(String method, String path, String tenantId, String customerId, String body,
                                        String agent) {
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
        return rest.exchange(path, org.springframework.http.HttpMethod.valueOf(method),
                new HttpEntity<>(body == null ? "" : body, headers), String.class);
    }

    private static String statusOf(ResponseEntity<String> response) {
        try {
            return JSON.readTree(response.getBody()).path("status").asText();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return JSON.readTree(response.getBody());
    }
}