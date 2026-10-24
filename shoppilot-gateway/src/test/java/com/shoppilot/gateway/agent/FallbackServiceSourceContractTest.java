package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.orchestration.AgentRole;
import com.shoppilot.gateway.orchestration.HandoffBlockReason;
import com.shoppilot.gateway.orchestration.HandoffGraph;
import com.shoppilot.gateway.orchestration.HandoffStep;
import com.shoppilot.gateway.orchestration.RouteContext;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** H2 回归：带编排事实的降级单必须显式发 source，否则工单服务会丢弃 payload。 */
class FallbackServiceSourceContractTest {

    private HttpServer server;
    private AtomicReference<String> requestBody;

    @BeforeEach
    void startCaptureServer() throws IOException {
        requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/tickets", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"id\":\"T-TEST\",\"status\":\"OPEN\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        RouteContext.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName("blocked fallback 发 source=DEGRADE，payload 里的编排字段才能落库")
    void sendsExplicitSourceWhenOrchestrationFactsExist() throws Exception {
        GatewayProperties properties = new GatewayProperties(null, null, null, null,
                new GatewayProperties.Ticket(server.getAddress().getPort() == 0 ? "" :
                        "http://127.0.0.1:" + server.getAddress().getPort(), "internal", Duration.ofSeconds(1),
                        Duration.ofSeconds(1)),
                null, null, null, null, null, null, null, null);
        FallbackService service = new FallbackService(java.net.http.HttpClient.newHttpClient(),
                new com.fasterxml.jackson.databind.ObjectMapper(), properties,
                new SimpleMeterRegistry(), null);

        TenantContext.set(new TenantContext.Identity("T001", "C001", "conv-1"));
        RouteContext.set(new RouteContext.Snapshot(AgentRole.AFTER_SALES,
                List.of(HandoffStep.allowed(AgentRole.PRE_SALES, AgentRole.AFTER_SALES, 0, 1L)),
                Map.of("orderNo", "90002"), "ACTION_LOGISTICS", HandoffBlockReason.DEPTH_EXHAUSTED,
                "v1.2.0", HandoffGraph.POLICY_VERSION));

        var ticket = service.escalate(FallbackReason.INTENT_UNRESOLVED, "帮我查订单 90002 的物流",
                "handoff blocked reason=DEPTH_EXHAUSTED");

        assertThat(ticket).contains("T-TEST");
        String body = requestBody.get();
        assertThat(body).contains("\"source\":\"DEGRADE\"");
        assertThat(body).contains("\\\"blockedReason\\\":\\\"DEPTH_EXHAUSTED\\\"");
        assertThat(body).contains("90002");
    }
}
