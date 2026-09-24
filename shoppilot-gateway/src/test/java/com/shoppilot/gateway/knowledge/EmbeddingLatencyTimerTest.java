package com.shoppilot.gateway.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
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

/**
 * 向量化分段耗时（ADR 0044 票 47）。
 *
 * <p>补的是一个真实的观测盲区：TTFT 的分段里 `shoppilot_ttft_seconds`、
 * `shoppilot_retrieve_dense_seconds`、`shoppilot_retrieve_lexical_seconds`、
 * `shoppilot_llm_latency_seconds` 都有服务端计时器，只有 embedding 没有——而它恰好是未命中
 * TTFT 里占比最大的一段（本机 311 / 725 ms），此前只能靠 `scripts/ttft_attribution.py`
 * 自己探针量一次放进去。
 *
 * <p>本用例钉两件事：三个桶与三个计数器**同分法**（逐桶计数能对上），以及「进程内缓存命中
 * 不打远程」这条既有承诺在加计时器之后仍然成立。
 */
class EmbeddingLatencyTimerTest {

    private static final int DIM = 4;
    private static final String VECTOR_JSON = "{\"embeddings\":[[0.1,0.2,0.3,0.4]]}";
    private static final String CALLS = "shoppilot_embedding_calls_total";
    private static final String LATENCY = "shoppilot_embedding_latency_seconds";

    private HttpServer server;
    private AtomicInteger upstreamCalls;
    private volatile long upstreamDelayMillis = 150;
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
                byte[] body = VECTOR_JSON.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
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
    @DisplayName("真打远程时 result=remote 桶记到耗时，且均值反映上游延迟而不是零")
    void remoteCallIsTimed() {
        Fixture fixture = fixture(true);

        fixture.client().embed("发什么快递");

        Timer remote = timer(fixture.registry(), "remote");
        assertThat(remote.count()).isEqualTo(1L);
        // 上游睡 150 ms，均值必须落在同一量级；若标签写错或计时包错位置，这里会掉到 0
        assertThat(remote.mean(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(50.0d);
        assertThat(counter(fixture.registry(), "remote").count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("进程内缓存命中记在 in-process-cache 桶，且绝不打远程")
    void cacheHitIsTimedWithoutRemoteCall() {
        Fixture fixture = fixture(true);
        fixture.client().embed("发什么快递");

        fixture.client().embed("发什么快递");

        assertThat(upstreamCalls).hasValue(1);
        assertThat(timer(fixture.registry(), "in-process-cache").count()).isEqualTo(1L);
        // 缓存命中没有外部往返，记零是事实：它让「三桶之和 = 总耗时」这条算式成立
        assertThat(timer(fixture.registry(), "in-process-cache").totalTime(TimeUnit.NANOSECONDS))
                .isZero();
        assertThat(timer(fixture.registry(), "remote").count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("并发等待者记在 singleflight-merge 桶：一次上游往返 + N-1 次等待")
    void mergedWaitersAreTimed() throws Exception {
        Fixture fixture = fixture(true);

        gather(fixture.client(), 8, "发什么快递");

        assertThat(upstreamCalls).hasValue(1);
        assertThat(timer(fixture.registry(), "remote").count()).isEqualTo(1L);
        assertThat(timer(fixture.registry(), "singleflight-merge").count()).isEqualTo(7L);
    }

    @Test
    @DisplayName("三桶计数之和与三个计数器之和逐桶一致（同分法的机器证据）")
    void bucketsStayAlignedWithCounters() throws Exception {
        Fixture fixture = fixture(true);
        fixture.client().embed("发什么快递");
        fixture.client().embed("发什么快递");
        gather(fixture.client(), 6, "生鲜坏了怎么赔");
        fixture.client().embed("生鲜坏了怎么赔");

        double counterSum = 0;
        double timerSum = 0;
        for (String result : List.of("remote", "in-process-cache", "singleflight-merge")) {
            Counter bucketCounter = counter(fixture.registry(), result);
            Timer bucketTimer = timer(fixture.registry(), result);
            assertThat(bucketTimer.count())
                    .as("桶 %s 的计时器计数与计数器必须相等", result)
                    .isEqualTo((long) bucketCounter.count());
            counterSum += bucketCounter.count();
            timerSum += bucketTimer.count();
        }
        // 计数器侧没有 warmup 桶，计时器侧也不该多出来——多出来说明有人给离线路径也计了时
        assertThat(timerSum).isEqualTo(counterSum);
    }

    @Test
    @DisplayName("关掉去重层（归因档）时每个请求都记 remote，缓存桶不出现")
    void dedupeOffRecordsEveryRequestAsRemote() {
        Fixture fixture = fixture(false);

        fixture.client().embed("发什么快递");
        fixture.client().embed("发什么快递");

        assertThat(upstreamCalls).hasValue(2);
        assertThat(timer(fixture.registry(), "remote").count()).isEqualTo(2L);
        // 三个桶在构造期就注册好了，所以判据是「零样本」而不是「不存在」——存在但恒零的桶
        // 与恒绿的夹具一样是摆设，这里要的是「这条路径一次都没走过」。
        assertThat(timer(fixture.registry(), "in-process-cache").count()).isZero();
        assertThat(counter(fixture.registry(), "in-process-cache").count()).isZero();
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

    private static Timer timer(MeterRegistry registry, String result) {
        Timer found = registry.find(LATENCY).tag("result", result).timer();
        assertThat(found).as("计时器 %s{result=%s} 必须已登记", LATENCY, result).isNotNull();
        return found;
    }

    private static Counter counter(MeterRegistry registry, String result) {
        Counter found = registry.find(CALLS).tag("result", result).counter();
        assertThat(found).as("计数器 %s{result=%s} 必须已登记", CALLS, result).isNotNull();
        return found;
    }

    private Fixture fixture(boolean inProcessCache) {
        MeterRegistry registry = new SimpleMeterRegistry();
        GatewayProperties properties = new GatewayProperties(
                null,
                new GatewayProperties.Embedding(baseUrl, "bge-m3", DIM,
                        Duration.ofSeconds(5), Duration.ofSeconds(60), inProcessCache),
                null, null, null, null, null, null, null, null);
        EmbeddingClient client = new EmbeddingClient(
                java.net.http.HttpClient.newHttpClient(), new ObjectMapper(), properties, registry);
        return new Fixture(client, registry);
    }

    private record Fixture(EmbeddingClient client, MeterRegistry registry) {
    }
}
