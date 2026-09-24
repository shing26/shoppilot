package com.shoppilot.gateway.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final int CACHE_MAX = 20000;
    /**
     * 预热与入库的尝试次数。Ollama 的推理子进程会被系统回收后再拉起，那几秒里上游会回 400
     * 或直接把连接掐断——一次瞬时失败不该废掉整场离线入库（09-09 16:57 那次全量验收的 stack
     * 步就是这么红的）。运行期请求不享受这个重试，理由见 {@link #embed(String)} 的时延预算。
     */
    private static final int WARMUP_ATTEMPTS = 3;
    private static final long WARMUP_BACKOFF_MILLIS = 2_000L;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final GatewayProperties.Embedding config;
    private final Counter failureCounter;
    private final Counter remoteCounter;
    private final Counter cacheHitCounter;
    private final Counter dedupeMergedCounter;
    private final Counter warmupRetryCounter;
    /**
     * 向量化耗时，桶与 {@link #remoteCounter} 等三个计数器**同分法**（ADR 0044 票 47）。
     *
     * <p>没有这个计时器之前，`scripts/ttft_attribution.py` 只能自己探针量一次远程向量化再放进
     * 算式——它是未命中 TTFT 里占比最大的一段（本机 311 / 725 ms），却只能靠估算。三桶同分法的
     * 用处是「计数 × 均值」能逐桶对上：进程内缓存命中按 {@link Duration#ZERO} 记，因为它本来就
     * 没有外部往返，记零是事实而不是省略。
     *
     * <p>失败的调用也照记（Micrometer 的 {@code record(Supplier)} 走 finally）：调用方等到的就是
     * 那个耗时，把它排除掉会让「慢」这件事从延迟曲线上消失，而失败次数另有
     * {@link #failureCounter} 单独计数。
     */
    private final Timer remoteTimer;
    private final Timer cacheHitTimer;
    private final Timer dedupeMergedTimer;
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
        this.remoteTimer = Timer.builder("shoppilot_embedding_latency_seconds")
                .tag("result", "remote")
                .description("向量化耗时；result 分法与 shoppilot_embedding_calls_total 一致")
                .register(registry);
        this.cacheHitTimer = Timer.builder("shoppilot_embedding_latency_seconds")
                .tag("result", "in-process-cache").register(registry);
        this.dedupeMergedTimer = Timer.builder("shoppilot_embedding_latency_seconds")
                .tag("result", "singleflight-merge").register(registry);
        this.warmupRetryCounter = Counter.builder("shoppilot_embedding_warmup_retry_total")
                .description("离线预热/入库路径上的向量化重试次数（运行期请求不重试）")
                .register(registry);
    }

    public float[] embed(String text) {
        String normalized = text == null ? "" : text.trim();
        if (!config.inProcessCache()) {
            // 归因实验（no-embedding-cache profile）：去重层整体关掉，每个请求真打一次 bge-m3。
            remoteCounter.increment();
            return remoteTimer.record(() -> request(normalized, config.timeout()));
        }
        float[] cached = cache.get(normalized);
        if (cached != null) {
            cacheHitCounter.increment();
            cacheHitTimer.record(Duration.ZERO);
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
            // 等待者的耗时记在 singleflight-merge 桶：它等的是发起者那一趟上游往返，不是自己发起的
            return dedupeMergedTimer.record(() -> awaitLeader(existing));
        }
        remoteCounter.increment();
        try {
            float[] vector = remoteTimer.record(() -> request(normalized, config.timeout()));
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
     * 等并发发起者的结果。等待者多给 1 秒：发起者超时后会把异常广播过来，等待者不该再排一轮队。
     *
     * <p>抽成方法是为了让调用点能用 {@code Timer.record(Supplier)} 计时——计时器不接受受检异常。
     */
    private float[] awaitLeader(CompletableFuture<float[]> existing) {
        try {
            return existing.get(config.timeout().plusSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待向量化结果时被中断", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("向量化失败（并发发起者未成功，已复用其结果）", failure);
        }
    }

    /**
     * 预热与离线入库专用：放宽到 warmup-timeout。
     *
     * <p>Ollama 首次调用需要把模型载入内存，几十秒是正常的；运行期请求不能享受这个放宽，
     * 否则一次向量化卡住就会击穿 TTFT 预算。
     */
    public float[] embedWarmup(String text) {
        String normalized = text == null ? "" : text.trim();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= WARMUP_ATTEMPTS; attempt++) {
            try {
                return request(normalized, config.warmupTimeout());
            } catch (RuntimeException failure) {
                last = failure;
                if (attempt == WARMUP_ATTEMPTS) {
                    break;
                }
                warmupRetryCounter.increment();
                log.warn("向量化第 {}/{} 次尝试失败，{} ms 后重试：{}",
                        attempt, WARMUP_ATTEMPTS, WARMUP_BACKOFF_MILLIS, failure.getMessage());
                sleepQuietly(WARMUP_BACKOFF_MILLIS);
            }
        }
        throw last;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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
