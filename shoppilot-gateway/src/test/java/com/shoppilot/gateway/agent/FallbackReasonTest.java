package com.shoppilot.gateway.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.llm.LlmException;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 降级原因必须可枚举、可落单（ticket 14、ADR 0009）。
 *
 * <p>这里守住两件事：七种 reason 一个都不能少、每一种都真的往业务侧落一张可查工单。
 * "只推事件不落单"是最容易蒙混过关的假功能，所以用工单端点的真实 HTTP 契约来验。
 */
class FallbackReasonTest {

    private static final List<String> REQUIRED_REASONS = List.of(
            "LLM_TIMEOUT", "LLM_CIRCUIT_OPEN", "LLM_BUDGET_EXCEEDED", "TOOL_UNAVAILABLE",
            "INTENT_UNRESOLVED", "RATE_LIMITED", "SLOT_UNRESOLVED");

    private static HttpServer bizMock;
    private static final List<String> received = new ArrayList<>();
    private static String baseUrl;

    private final ObjectMapper mapper = new ObjectMapper();
    private FallbackService service;

    @BeforeAll
    static void startFakeBizMock() throws Exception {
        bizMock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bizMock.createContext("/api/tickets", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(body);
            byte[] response = "{\"id\":\"TK-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        bizMock.start();
        baseUrl = "http://127.0.0.1:" + bizMock.getAddress().getPort();
    }

    @AfterAll
    static void stopFakeBizMock() {
        bizMock.stop(0);
    }

    @BeforeEach
    void setUp() {
        received.clear();
        GatewayProperties properties = new GatewayProperties(null, null, null, null,
                new GatewayProperties.BizMock(baseUrl, "t", Duration.ofSeconds(1), Duration.ofSeconds(2)),
                null, null, null, null, null);
        service = new FallbackService(HttpClient.newHttpClient(), mapper, properties, new SimpleMeterRegistry(),
                mock(StringRedisTemplate.class));
        TenantContext.set(new TenantContext.Identity("T001", "C001", "conv-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void 七种降级原因一个都不能少() {
        List<String> declared = new ArrayList<>();
        for (FallbackReason reason : FallbackReason.values()) {
            declared.add(reason.name());
        }
        assertThat(declared).containsAll(REQUIRED_REASONS);
    }

    @Test
    void 每种降级原因都真的落一张可查工单() throws Exception {
        for (FallbackReason reason : FallbackReason.values()) {
            received.clear();
            Optional<String> ticketId = service.escalate(reason, "订单怎么还没发货", "detail-" + reason.name());
            assertThat(ticketId).as(reason.name() + " 必须回执工单号").isPresent();
            assertThat(received).as(reason.name() + " 必须落一次工单").hasSize(1);
            JsonNode payload = mapper.readTree(received.get(0));
            assertThat(payload.path("reason").asText()).isEqualTo(reason.name());
            assertThat(payload.path("userQuery").asText()).isEqualTo("订单怎么还没发货");
            assertThat(payload.path("customerId").asText()).isEqualTo("C001");
        }
    }

    @Test
    void 每种降级原因都有面向用户的话术() {
        for (FallbackReason reason : FallbackReason.values()) {
            assertThat(reason.userMessage()).as(reason.name() + " 不能只有一句占位").isNotBlank();
        }
    }

    @Test
    void 模型三类失败各自映射到不同降级原因() {
        assertThat(AgentStateMachine.mapLlmFailure(LlmException.timeout("slow", null)))
                .isEqualTo(FallbackReason.LLM_TIMEOUT);
        assertThat(AgentStateMachine.mapLlmFailure(LlmException.unavailable("circuit open", null)))
                .isEqualTo(FallbackReason.LLM_CIRCUIT_OPEN);
        assertThat(AgentStateMachine.mapLlmFailure(LlmException.budgetExceeded(200_001L, 200_000L)))
                .isEqualTo(FallbackReason.LLM_BUDGET_EXCEEDED);
    }

    @Test
    void 限流工单在窗口内合并成一张() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        // 第一次：无在办工单 -> 落单；第二次：命中去重键 -> 只加计数
        when(values.get(anyString())).thenReturn(null).thenReturn("TK-EXISTING");
        GatewayProperties properties = new GatewayProperties(null, null, null, null,
                new GatewayProperties.BizMock(baseUrl, "t", Duration.ofSeconds(1), Duration.ofSeconds(2)),
                null, null, null, null, null);
        FallbackService deduping = new FallbackService(HttpClient.newHttpClient(), mapper, properties,
                new SimpleMeterRegistry(), redis);

        Optional<String> first = deduping.escalateRateLimited("帮我查订单", "T001", "C001");
        Optional<String> second = deduping.escalateRateLimited("帮我查订单", "T001", "C001");

        assertThat(first).get().isEqualTo("TK-1");
        assertThat(second).get().isEqualTo("TK-EXISTING");
        assertThat(received).hasSize(1);
    }

    @Test
    void 去重存储不可用时仍然落单而不是丢掉降级痕迹() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        GatewayProperties properties = new GatewayProperties(null, null, null, null,
                new GatewayProperties.BizMock(baseUrl, "t", Duration.ofSeconds(1), Duration.ofSeconds(2)),
                null, null, null, null, null);
        FallbackService service = new FallbackService(HttpClient.newHttpClient(), mapper, properties,
                new SimpleMeterRegistry(), redis);

        assertThat(service.escalateRateLimited("帮我查订单", "T001", "C001")).isPresent();
        assertThat(received).hasSize(1);
    }

}
