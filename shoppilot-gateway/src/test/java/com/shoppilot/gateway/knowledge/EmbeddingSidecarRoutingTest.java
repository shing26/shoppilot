package com.shoppilot.gateway.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
 * 运行期查询向量化的 sidecar 切换（ADR 0085 选项 A / 票 212）。
 *
 * <p>切换面是刻意的、两边都要钉住：embed()（HybridRetriever / CacheService / TriageEngine 三个
 * 调用点共用的运行期路径）在配了 {@code query-base-url} 时先打 sidecar、失败回落原 base-url 且
 * 回落事件单独计数；embedWarmup()（入库 / 预热 / T1 质心）**永远打 base-url**——sidecar 是新的
 * 常驻进程，离线链路的可靠性不押在它上面。sidecar 协议与 Ollama 的 {@code /api/embed} 相同，
 * 所以 request() 的解析零改动，这里用两个假上游分别数调用次数来钉住指向。
 *
 * <p>分账不变式不因切换面而变：sidecar 成功的调用仍记 {@code result=remote} 桶——口径没换，
 * 换的只是远程的指向；回落成功的耗时（sidecar 一趟 + base-url 一趟）也记在 remote 桶，
 * 因为那就是调用方实际等到的耗时。
 */
class EmbeddingSidecarRoutingTest {

    private static final int DIM = 4;
    private static final String SIDECAR_VECTOR_JSON = "{\"embeddings\":[[0.1,0.2,0.3,0.4]]}";
    private static final String OLLAMA_VECTOR_JSON = "{\"embeddings\":[[0.5,0.6,0.7,0.8]]}";
    private static final String FALLBACK = "shoppilot_embedding_fallback_total";

    private HttpServer sidecar;
    private HttpServer ollama;
    private AtomicInteger sidecarCalls;
    private AtomicInteger ollamaCalls;
    /** sidecar 的响应状态码与响应体，用例里按场景改写。 */
    private volatile int sidecarStatus = 200;
    private volatile String sidecarBody = SIDECAR_VECTOR_JSON;
    private volatile boolean ollamaAlwaysFail;
    private String sidecarUrl;
    private String ollamaUrl;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void startFakeUpstreams() throws IOException {
        sidecarCalls = new AtomicInteger();
        ollamaCalls = new AtomicInteger();
        sidecarStatus = 200;
        sidecarBody = SIDECAR_VECTOR_JSON;
        ollamaAlwaysFail = false;

        sidecar = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sidecar.createContext("/api/embed", exchange -> {
            sidecarCalls.incrementAndGet();
            byte[] body = sidecarBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(sidecarStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        sidecar.setExecutor(Executors.newFixedThreadPool(2));
        sidecar.start();
        sidecarUrl = "http://127.0.0.1:" + sidecar.getAddress().getPort();

        ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/embed", exchange -> {
            int seen = ollamaCalls.incrementAndGet();
            boolean fail = ollamaAlwaysFail || seen == -1;
            byte[] body = (fail ? "{\"error\":\"connection reset\"}" : OLLAMA_VECTOR_JSON)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fail ? 400 : 200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        ollama.setExecutor(Executors.newFixedThreadPool(2));
        ollama.start();
        ollamaUrl = "http://127.0.0.1:" + ollama.getAddress().getPort();
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void stopFakeUpstreams() {
        sidecar.stop(0);
        ollama.stop(0);
    }

    @Test
    @DisplayName("配了 sidecar 后 embed() 打 sidecar、不打 Ollama，返回的是 sidecar 的向量")
    void embedGoesToSidecarWhenConfigured() {
        EmbeddingClient client = client(true);

        float[] vector = client.embed("生鲜坏了怎么赔");

        assertThat(vector).hasSize(DIM).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        assertThat(sidecarCalls).hasValue(1);
        assertThat(ollamaCalls).hasValue(0);
    }

    @Test
    @DisplayName("sidecar 失败回落 Ollama：拿到 Ollama 的向量，fallback 计数 +1")
    void sidecarFailureFallsBackToOllamaAndCounts() {
        EmbeddingClient client = client(true);
        sidecarStatus = 500;
        sidecarBody = "{\"error\":\"boom\"}";

        float[] vector = client.embed("生鲜坏了怎么赔");

        assertThat(vector).hasSize(DIM).containsExactly(0.5f, 0.6f, 0.7f, 0.8f);
        assertThat(sidecarCalls).hasValue(1);
        assertThat(ollamaCalls).hasValue(1);
        assertThat(registry.get(FALLBACK).counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("sidecar 回的维度不对也算失败：回落 Ollama，同样计数")
    void sidecarDimensionMismatchFallsBack() {
        EmbeddingClient client = client(true);
        sidecarBody = "{\"embeddings\":[[0.1,0.2,0.3]]}";

        float[] vector = client.embed("生鲜坏了怎么赔");

        assertThat(vector).hasSize(DIM).containsExactly(0.5f, 0.6f, 0.7f, 0.8f);
        assertThat(sidecarCalls).hasValue(1);
        assertThat(ollamaCalls).hasValue(1);
        assertThat(registry.get(FALLBACK).counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("sidecar 不可达（连接拒绝）同样回落：指向一个没人监听的端口即可")
    void unreachableSidecarFallsBack() {
        EmbeddingClient client = client(true, "http://127.0.0.1:59998");

        float[] vector = client.embed("生鲜坏了怎么赔");

        assertThat(vector).hasSize(DIM);
        assertThat(ollamaCalls).hasValue(1);
        assertThat(registry.get(FALLBACK).counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("回落也失败才抛既有异常：sidecar 与 Ollama 各被打了一次，fallback 已计数")
    void fallbackFailureStillThrows() {
        EmbeddingClient client = client(true);
        sidecarStatus = 500;
        ollamaAlwaysFail = true;

        assertThatThrownBy(() -> client.embed("生鲜坏了怎么赔"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(sidecarCalls).hasValue(1);
        assertThat(ollamaCalls).hasValue(1);
        assertThat(registry.get(FALLBACK).counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("embedWarmup() 不受 sidecar 配置影响：预热/入库永远打 Ollama")
    void warmupIgnoresSidecar() {
        EmbeddingClient client = client(true);

        float[] vector = client.embedWarmup("生鲜坏了怎么赔");

        assertThat(vector).hasSize(DIM).containsExactly(0.5f, 0.6f, 0.7f, 0.8f);
        assertThat(ollamaCalls).hasValue(1);
        assertThat(sidecarCalls).hasValue(0);
    }

    @Test
    @DisplayName("sidecar 路径仍记 result=remote 桶：口径不变，只是指向变了")
    void sidecarPathStillRecordsRemoteBucket() {
        EmbeddingClient client = client(true);

        client.embed("生鲜坏了怎么赔");
        client.embed("生鲜坏了怎么赔");

        // 第二次命中进程内缓存：sidecar 仍只被打一次
        assertThat(sidecarCalls).hasValue(1);
        Timer remote = registry.get("shoppilot_embedding_latency_seconds").tag("result", "remote").timer();
        assertThat(remote.count()).isEqualTo(1L);
        assertThat(registry.get("shoppilot_embedding_calls_total").tag("result", "remote").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(FALLBACK).counter().count()).isZero();
    }

    @Test
    @DisplayName("singleflight 发起者也走 sidecar：缓存开启时并发同问法仍只有一次远程往返")
    void singleflightLeaderAlsoUsesSidecar() throws Exception {
        EmbeddingClient client = client(true);
        int callers = 8;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<float[]>> futures = new java.util.ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return client.embed("发什么快递");
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<float[]> future : futures) {
                future.get(20, java.util.concurrent.TimeUnit.SECONDS);
            }
        }

        assertThat(sidecarCalls).hasValue(1);
        assertThat(ollamaCalls).hasValue(0);
        assertThat(registry.get(FALLBACK).counter().count()).isZero();
    }

    private EmbeddingClient client(boolean inProcessCache) {
        return client(inProcessCache, sidecarUrl);
    }

    private EmbeddingClient client(boolean inProcessCache, String queryBaseUrl) {
        GatewayProperties properties = new GatewayProperties(
                null,
                new GatewayProperties.Embedding(ollamaUrl, "bge-m3", DIM,
                        Duration.ofSeconds(5), Duration.ofSeconds(30), inProcessCache, queryBaseUrl),
                null, null,
                null, null, null, null, null, null, null, null, null, null);
        return new EmbeddingClient(HttpClient.newHttpClient(), new ObjectMapper(), properties, registry);
    }
}
