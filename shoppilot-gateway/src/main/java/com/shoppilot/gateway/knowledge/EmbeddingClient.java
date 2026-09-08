package com.shoppilot.gateway.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 本地 bge-m3 稠密向量（ADR 0001）。
 *
 * <p>刻意不走云端 embedding API：L2 语义缓存要求每次提问先向量化，这一步走网络
 * 会直接击穿 TP99 &lt; 30ms。同时提供进程内向量缓存——同一句话在压测里会被问很多次。
 */
@Component
public class EmbeddingClient {

    private static final int CACHE_MAX = 20000;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final GatewayProperties.Embedding config;
    private final Counter failureCounter;
    private final Counter remoteCounter;
    private final Counter cacheHitCounter;
    private final Counter dedupeMergedCounter;
    private final Map<String, float[]> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<float[]>> inflight = new ConcurrentHashMap<>();

    public EmbeddingClient(HttpClient http, ObjectMapper mapper, GatewayProperties properties, MeterRegistry registry) {
        this.http = http;
        this.mapper = mapper;
        this.config = properties.embedding();
        this.failureCounter = Counter.builder("shoppilot_embedding_failure_total").register(registry);
        // "命中路径零 embedding 调用"这条断言要能证伪，就得把进程内命中与真打 Ollama 分开记
        this.remoteCounter = Counter.builder("shoppilot_embedding_calls_total")
                .tag("result", "remote").register(registry);
        this.cacheHitCounter = Counter.builder("shoppilot_embedding_calls_total")
                .tag("result", "in-process-cache").register(registry);
        this.dedupeMergedCounter = Counter.builder("shoppilot_embedding_calls_total")
                .tag("result", "singleflight-merge").register(registry);
    }

    public float[] embed(String text) {
        String normalized = text == null ? "" : text.trim();
        if (!config.inProcessCache()) {
            // 归因实验（no-embedding-cache profile）：去重层整体关掉，每个请求真打一次 bge-m3。
            remoteCounter.increment();
            return request(normalized, config.timeout());
        }
        float[] cached = cache.get(normalized);
        if (cached != null) {
            cacheHitCounter.increment();
            return cached;
        }
        return loadOnce(normalized);
    }

    /**
     * 同一句话的并发请求只放一个去 Ollama，其余等待者复用它的结果。
     *
     * <p>压测里踩到的坑：模型冷启动那几十秒里所有并发请求全部 cache miss、全部自己发一次远程向量化，
     * 把 Ollama 队列撑爆后集体 5 秒超时；失败的结果不进缓存，于是下一批继续全量打——踩踏自己维持自己。
     * 合并之后一次压测的真实远程调用数接近"问法种类数"，而不是请求数；要失败也只失败一次。
     */
    private float[] loadOnce(String normalized) {
        CompletableFuture<float[]> leader = new CompletableFuture<>();
        CompletableFuture<float[]> existing = inflight.putIfAbsent(normalized, leader);
        if (existing != null) {
            dedupeMergedCounter.increment();
            try {
                // 等待者多给 1 秒：发起者超时后会把异常广播过来，等待者不该再排一轮队
                return existing.get(config.timeout().plusSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待向量化结果时被中断", interrupted);
            } catch (ExecutionException | TimeoutException failure) {
                throw new IllegalStateException("向量化失败（并发发起者未成功，已复用其结果）", failure);
            }
        }
        remoteCounter.increment();
        try {
            float[] vector = request(normalized, config.timeout());
            leader.complete(vector);
            if (cache.size() > CACHE_MAX) {
                cache.clear();
            }
            cache.put(normalized, vector);
            return vector;
        } catch (RuntimeException failure) {
            leader.completeExceptionally(failure);
            throw failure;
        } finally {
            inflight.remove(normalized, leader);
        }
    }

    /**
     * 预热与离线入库专用：放宽到 warmup-timeout。
     *
     * <p>Ollama 首次调用需要把模型载入内存，几十秒是正常的；运行期请求不能享受这个放宽，
     * 否则一次向量化卡住就会击穿 TTFT 预算。
     */
    public float[] embedWarmup(String text) {
        return request(text == null ? "" : text.trim(), config.warmupTimeout());
    }

    /** 压测与降级演练用：绕开 embedding 推理，只测编排层。 */
    public float[] embedDeterministic(String text) {
        float[] vector = new float[config.dimension()];
        int hash = text == null ? 0 : text.hashCode();
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) Math.sin((hash + i) * 0.001d) / (float) Math.sqrt(vector.length);
        }
        return vector;
    }

    private float[] request(String text, Duration timeout) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.model());
        payload.put("input", text);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/embed"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("embedding 返回 " + response.statusCode() + ": " + response.body());
            }
            JsonNode embeddings = mapper.readTree(response.body()).path("embeddings");
            JsonNode first = embeddings.isArray() && embeddings.size() > 0 ? embeddings.get(0) : embeddings;
            float[] vector = new float[first.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) first.get(i).asDouble();
            }
            if (vector.length != config.dimension()) {
                throw new IllegalStateException("向量维度 " + vector.length + " 与配置 " + config.dimension() + " 不一致");
            }
            return vector;
        } catch (Exception failure) {
            failureCounter.increment();
            throw new IllegalStateException("向量化失败（Ollama 是否已启动并 pull 了 " + config.model() + "？）: "
                    + failure.getMessage(), failure);
        }
    }

    public int dimension() {
        return config.dimension();
    }

    public Duration timeout() {
        return config.timeout();
    }
}
