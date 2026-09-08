package com.shoppilot.gateway.triage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.knowledge.EmbeddingClient;
import com.shoppilot.tool.Intent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * T1 质心层：每个意图配一组样例句取质心，做余弦最近邻（ADR 0007）。
 *
 * <p>样例句集与 T0 规则表一样，是需要长期维护的资产，都要进版本控制。
 * 质心在后台构建并落本地缓存文件，运行期只算余弦，成本约 10ms。
 */
@Component
public class T1CentroidLayer {

    private static final Logger log = LoggerFactory.getLogger(T1CentroidLayer.class);

    /** 低于这个相似度就不敢定案，交给下一级或直接 fail-closed。 */
    private static final double MIN_CONFIDENCE = 0.82d;

    private final EmbeddingClient embedding;
    private final ObjectMapper mapper;
    private final GatewayProperties properties;
    private final Environment environment;
    /** 整体替换而不是原地写：构建在后台线程跑，读侧要么看到完整质心表要么看到空表。 */
    private volatile Map<Intent, float[]> centroids = Map.of();

    public T1CentroidLayer(EmbeddingClient embedding, ObjectMapper mapper, GatewayProperties properties,
                           Environment environment) {
        this.embedding = embedding;
        this.mapper = mapper;
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    public void build() {
        if (Arrays.stream(environment.getActiveProfiles()).anyMatch("ingest"::equals)) {
            // 离线入库不需要意图质心，别在后台抢那 90 次向量化
            return;
        }
        // 90 条样本要 90 次向量化，压在启动路径上会让网关十几秒不可用；放后台，就绪前 T1 弃权
        Thread.ofVirtual().name("intent-centroid-build").start(this::buildCentroids);
    }

    void buildCentroids() {
        try {
            String raw = readResource();
            String fingerprint = fingerprint(raw);
            Map<Intent, float[]> cached = readCache(fingerprint);
            if (cached != null) {
                this.centroids = cached;
                log.info("T1 意图质心从本地缓存就绪：{} 个意图", cached.size());
                return;
            }
            Map<String, List<String>> samples = mapper.readValue(raw, new TypeReference<>() {
            });
            Map<Intent, float[]> built = new EnumMap<>(Intent.class);
            for (Map.Entry<String, List<String>> entry : samples.entrySet()) {
                built.put(Intent.valueOf(entry.getKey()), centroid(entry.getValue()));
            }
            writeCache(fingerprint, built);
            this.centroids = built;
            log.info("T1 意图质心构建完成：{} 个意图", built.size());
        } catch (Exception failure) {
            // 质心没建起来不影响启动：T1 直接放弃判定权，退化为 T0 + T2
            log.warn("构建意图质心失败，T1 层将不参与判定: {}", failure.getMessage());
        }
    }

    private String readResource() throws Exception {
        try (InputStream input = new ClassPathResource("intent-samples.json").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 样本一改，缓存指纹就变，不需要手动清缓存文件。 */
    private String fingerprint(String raw) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest(raw.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private Path cachePath() {
        return Path.of(properties.triage().centroidCache());
    }

    private Map<Intent, float[]> readCache(String fingerprint) {
        try {
            Path path = cachePath();
            if (!Files.exists(path)) {
                return null;
            }
            CachedCentroids stored = mapper.readValue(Files.readString(path, StandardCharsets.UTF_8),
                    CachedCentroids.class);
            if (!fingerprint.equals(stored.fingerprint())) {
                return null;
            }
            Map<Intent, float[]> result = new EnumMap<>(Intent.class);
            stored.centroids().forEach((name, vector) -> result.put(Intent.valueOf(name), toFloats(vector)));
            return result.isEmpty() ? null : result;
        } catch (Exception failure) {
            log.debug("读取质心缓存失败，改为重新构建: {}", failure.getMessage());
            return null;
        }
    }

    private void writeCache(String fingerprint, Map<Intent, float[]> built) {
        try {
            Map<String, List<Double>> serialized = new LinkedHashMap<>();
            built.forEach((intent, vector) -> {
                List<Double> values = new ArrayList<>(vector.length);
                for (float value : vector) {
                    values.add((double) value);
                }
                serialized.put(intent.name(), values);
            });
            Path path = cachePath();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, mapper.writeValueAsString(new CachedCentroids(fingerprint, serialized)),
                    StandardCharsets.UTF_8);
        } catch (Exception failure) {
            log.debug("写入质心缓存失败，下次启动重建: {}", failure.getMessage());
        }
    }

    private static float[] toFloats(List<Double> values) {
        float[] vector = new float[values.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = values.get(i).floatValue();
        }
        return vector;
    }

    private record CachedCentroids(String fingerprint, Map<String, List<Double>> centroids) {
    }

    public Optional<TriageResult> classify(String query, float[] queryVector) {
        if (centroids.isEmpty() || queryVector == null) {
            return Optional.empty();
        }
        // 质心是归一化后的均值，查询向量也必须归一化，否则下面的点积不是余弦而是模长加权的相似度
        float[] normalized = normalize(queryVector);
        Intent best = null;
        double bestScore = -1;
        double runnerUp = -1;
        for (Map.Entry<Intent, float[]> entry : centroids.entrySet()) {
            double score = cosine(normalized, entry.getValue());
            if (score > bestScore) {
                runnerUp = bestScore;
                bestScore = score;
                best = entry.getKey();
            } else if (score > runnerUp) {
                runnerUp = score;
            }
        }
        // 两个意图咬得很近时不硬判，交给下一级
        if (best == null || bestScore < MIN_CONFIDENCE || bestScore - runnerUp < 0.03d) {
            return Optional.empty();
        }
        if (best == Intent.ESCALATE) {
            return Optional.of(TriageResult.dynamic(best, "T1", false));
        }
        if (best.isAction()) {
            return Optional.of(TriageResult.dynamic(best, "T1", false));
        }
        return Optional.of(TriageResult.policy(best, "T1", bestScore));
    }

    private float[] centroid(List<String> sentences) {
        List<float[]> vectors = new ArrayList<>();
        for (String sentence : sentences) {
            vectors.add(embedding.embedWarmup(sentence));
        }
        int dimension = vectors.get(0).length;
        float[] mean = new float[dimension];
        for (float[] vector : vectors) {
            for (int i = 0; i < dimension; i++) {
                mean[i] += vector[i];
            }
        }
        for (int i = 0; i < dimension; i++) {
            mean[i] /= vectors.size();
        }
        return normalize(mean);
    }

    private static float[] normalize(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            return vector;
        }
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = (float) (vector[i] / norm);
        }
        return result;
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return -1;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}
