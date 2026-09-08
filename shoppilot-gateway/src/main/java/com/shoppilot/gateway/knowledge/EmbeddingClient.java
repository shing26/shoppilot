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
    private final Map<String, float[]> cache = new ConcurrentHashMap<>();

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
    }

    public float[] embed(String text) {
        String normalized = text == null ? "" : text.trim();
        float[] cached = cache.get(normalized);
        if (cached != null) {
            cacheHitCounter.increment();
            return cached;
        }
        remoteCounter.increment();
        float[] vector = request(normalized, config.timeout());
        if (cache.size() > CACHE_MAX) {
            cache.clear();
        }
        cache.put(normalized, vector);
        return vector;
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
