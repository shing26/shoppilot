package com.shoppilot.gateway.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量化踩踏保护（2026-09-08 mix80 压测暴露的缺陷）。
 *
 * <p>那一次压测里 91812 个请求只有 36 种问法，进程内向量缓存却一次都没热起来：
 * Ollama 首次调用要几十秒载模型，这期间所有并发请求全部 cache miss、全部自己发一次远程请求，
 * 把队列撑爆后集体 5 秒超时；失败不进缓存，于是下一批继续全量打，踩踏自己维持自己。
 * 合并同问法之后，一次压测的真实远程调用次数应当接近"问法种类数"，而不是请求数。
 */
class EmbeddingSingleFlightTest {

    private static final int DIM = 4;
    private static final String VECTOR_JSON = "{\"embeddings\":[[0.1,0.2,0.3,0.4]]}";

    private HttpServer server;
    private AtomicInteger upstreamCalls;
    private volatile int status = 200;
    private volatile long upstreamDelayMillis = 120;
    private String baseUrl;

    @BeforeEach
    void startFakeOllama() throws IOException {
        upstreamCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            upstreamCalls.incrementAndGet();
            try {
                if (upstreamDelayMillis > 0) {
                    Thread.sleep(upstreamDelayMillis);
                }
                byte[] body = (status == 200 ? VECTOR_JSON : "{\"error\":\"boom\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopFakeOllama() {
        server.stop(0);
    }

    @Test
    @DisplayName("同一问法的并发请求只打一次上游，其余复用同一结果")
    void concurrentIdenticalQueriesShareOneUpstreamCall() throws Exception {
        EmbeddingClient client = client(true);
        int callers = 24;

        List<float[]> results = gather(client, callers, "发什么快递");

        assertThat(upstreamCalls).hasValue(1);
        assertThat(results).allMatch(vector -> vector.length == DIM);
        assertThat(results.stream().allMatch(vector -> vector[0] == 0.1f)).isTrue();
    }

    @Test
    @DisplayName("不同问法各自向量化，合并不会把语义不同的问题揉成一个向量")
    void distinctQueriesAreNotMerged() throws Exception {
        EmbeddingClient client = client(true);

        gather(client, 8, "发什么快递");
        gather(client, 8, "生鲜坏了怎么赔");
        gather(client, 8, "跨店满减怎么算");

        assertThat(upstreamCalls).hasValue(3);
    }

    @Test
    @DisplayName("上游失败时只失败一次，等待者拿到异常而不是挂死")
    void failureIsSharedAndNotCached() {
        status = 500;
        EmbeddingClient client = client(true);

        assertThatThrownBy(() -> gather(client, 6, "发什么快递")).hasCauseInstanceOf(IllegalStateException.class);

        assertThat(upstreamCalls).hasValue(1);
        // 失败结果绝不进缓存：否则一次抖动会把这条问法永久毒化成"无法向量化"
        status = 200;
        assertThat(client.embed("发什么快递")).hasSize(DIM);
        assertThat(upstreamCalls).hasValue(2);
    }

    @Test
    @DisplayName("归因开关关掉去重层时，每个请求都真打上游")
    void dedupeLayerCanBeSwitchedOffForAttribution() throws Exception {
        upstreamDelayMillis = 0;
        EmbeddingClient client = client(false);

        gather(client, 5, "发什么快递");

        assertThat(upstreamCalls).hasValue(5);
    }

    @Test
    @DisplayName("上游已恢复时等待者不会跟着超时：合并的是结果不是排队")
    void waiterReturnsAsSoonAsLeaderFinishes() throws Exception {
        upstreamDelayMillis = 200;
        EmbeddingClient client = client(true);
        long started = System.nanoTime();

        gather(client, 32, "发什么快递");

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(upstreamCalls).hasValue(1);
        // 32 个请求若各排一次队，串行要 6.4 秒；合并后只需要一次上游耗时
        assertThat(elapsedMillis).isLessThan(1_500L);
    }

    private List<float[]> gather(EmbeddingClient client, int callers, String query) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<float[]>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return client.embed(query);
                }));
            }
            start.countDown();
            List<float[]> results = new ArrayList<>();
            for (Future<float[]> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private EmbeddingClient client(boolean inProcessCache) {
        MeterRegistry registry = new SimpleMeterRegistry();
        GatewayProperties properties = new GatewayProperties(
                null,
                new GatewayProperties.Embedding(baseUrl, "bge-m3", DIM,
                        Duration.ofSeconds(5), Duration.ofSeconds(60), inProcessCache),
                null, null, null, null, null, null, null, null);
        return new EmbeddingClient(java.net.http.HttpClient.newHttpClient(), new ObjectMapper(), properties, registry);
    }
}
