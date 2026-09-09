package com.shoppilot.gateway.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入库路径的向量化重试（09-09 全量验收 stack 步暴露）。
 *
 * <p>那一次是 Ollama 自己的推理子进程被回收后重启，几秒内上游回 400 "connection forcibly closed"，
 * 90 块里第 50 多块失败就把整场入库与 up.ps1 一起带红。离线入库没有时延预算，重试是白捡的健壮性；
 * 运行期请求反过来——那里重试等于把一次抖动放大成击穿 TTFT 的排队，所以两边必须分开钉住。
 */
class EmbeddingWarmupRetryTest {

    private static final int DIM = 4;
    private static final String VECTOR_JSON = "{\"embeddings\":[[0.1,0.2,0.3,0.4]]}";

    private HttpServer server;
    private AtomicInteger upstreamCalls;
    private volatile int failuresBeforeSuccess;
    private volatile boolean alwaysFail;
    private String baseUrl;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void startFakeOllama() throws IOException {
        upstreamCalls = new AtomicInteger();
        failuresBeforeSuccess = 0;
        alwaysFail = false;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            int seen = upstreamCalls.incrementAndGet();
            boolean shouldFail = alwaysFail || seen <= failuresBeforeSuccess;
            String body = shouldFail ? "{\"error\":\"do embedding request: connection reset\"}" : VECTOR_JSON;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(shouldFail ? 400 : 200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void stopFakeOllama() {
        server.stop(0);
    }

    @Test
    @DisplayName("上游先失败两次：预热路径重试到第三次并拿到向量")
    void warmupRetriesThroughTransientUpstreamFailure() {
        failuresBeforeSuccess = 2;

        assertThat(client().embedWarmup("生鲜坏了怎么赔")).hasSize(DIM);

        assertThat(upstreamCalls).hasValue(3);
        assertThat(registry.get("shoppilot_embedding_warmup_retry_total").counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("上游一直失败：按次数上限放弃并抛出，不无限重试")
    void warmupGivesUpAfterBoundedAttempts() {
        alwaysFail = true;

        assertThatThrownBy(() -> client().embedWarmup("生鲜坏了怎么赔"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(upstreamCalls).hasValue(3);
        assertThat(registry.get("shoppilot_embedding_warmup_retry_total").counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("运行期请求不重试：一次失败立刻抛出，时延预算优先")
    void onlinePathNeverRetries() {
        alwaysFail = true;

        assertThatThrownBy(() -> client().embed("生鲜坏了怎么赔"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(upstreamCalls).hasValue(1);
    }

    private EmbeddingClient client() {
        GatewayProperties properties = new GatewayProperties(
                null,
                new GatewayProperties.Embedding(baseUrl, "bge-m3", DIM,
                        Duration.ofSeconds(5), Duration.ofSeconds(30), true),
                null, null, null, null, null, null, null, null);
        return new EmbeddingClient(HttpClient.newHttpClient(), new ObjectMapper(), properties, registry);
    }
}
