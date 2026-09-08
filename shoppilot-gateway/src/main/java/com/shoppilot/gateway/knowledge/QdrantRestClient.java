package com.shoppilot.gateway.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qdrant REST 客户端。
 *
 * <p>用 REST + JDK HttpClient 而非官方 gRPC client：省掉一整套 grpc 依赖，
 * 也让"跨租户隔离靠 payload filter 而不是靠 collection 爆炸"这条决策在代码里一眼可见（ADR 0005）。
 */
@Component
public class QdrantRestClient {

    private static final Logger log = LoggerFactory.getLogger(QdrantRestClient.class);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public QdrantRestClient(HttpClient http, ObjectMapper mapper, GatewayProperties properties) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = properties.retrieval().qdrantUrl();
    }

    public void ensureCollection(String collection, int dimension, List<String> keywordFields,
                                 List<String> integerFields) {
        Map<String, Object> vectors = new LinkedHashMap<>();
        vectors.put("size", dimension);
        vectors.put("distance", "Cosine");
        Map<String, Object> body = Map.of("vectors", vectors);
        // 不存在的 collection 是 404，不是异常；拿 send() 的返回值判存在会把"没有"当成"坏了"
        if (collectionExists(collection)) {
            log.debug("collection {} 已存在", collection);
        } else {
            send("PUT", "/collections/" + collection, body);
        }
        for (String field : keywordFields) {
            createIndex(collection, field, "keyword");
        }
        for (String field : integerFields) {
            // kb_epoch 存的是数字，按 keyword 建索引会建出一个空索引，纪元过滤反而全表扫
            createIndex(collection, field, "integer");
        }
    }

    private void createIndex(String collection, String field, String dataType) {
        try {
            send("PUT", "/collections/" + collection + "/index?wait=true",
                    Map.of("field_name", field, "data_type", dataType));
        } catch (RuntimeException alreadyIndexed) {
            log.debug("collection {} 字段 {} 索引已存在: {}", collection, field, alreadyIndexed.getMessage());
        }
    }

    public void upsert(String collection, List<Point> points) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (Point point : points) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", point.id());
            item.put("vector", point.vector());
            item.put("payload", point.payload());
            serialized.add(item);
        }
        send("PUT", "/collections/" + collection + "/points?wait=true", Map.of("points", serialized));
    }

    public List<Hit> search(String collection, float[] vector, Map<String, Object> filter, int limit, Double scoreThreshold) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", vector);
        body.put("limit", limit);
        body.put("with_payload", true);
        if (filter != null && !filter.isEmpty()) {
            body.put("filter", filter);
        }
        if (scoreThreshold != null) {
            body.put("score_threshold", scoreThreshold);
        }
        String response = send("POST", "/collections/" + collection + "/points/search", body);
        List<Hit> hits = new ArrayList<>();
        try {
            JsonNode result = mapper.readTree(response).path("result");
            if (result.isArray()) {
                for (JsonNode node : result) {
                    hits.add(new Hit(node.path("id").asText(), node.path("score").asDouble(),
                            mapper.convertValue(node.path("payload"), Map.class)));
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("解析 Qdrant 检索结果失败", failure);
        }
        return hits;
    }

    public long deleteByFilter(String collection, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return 0;
        }
        String response = send("POST", "/collections/" + collection + "/points/delete?wait=true",
                Map.of("filter", filter));
        try {
            return mapper.readTree(response).path("result").path("deleted").asLong(0);
        } catch (Exception failure) {
            return 0;
        }
    }

    public long count(String collection, Map<String, Object> filter) {
        Map<String, Object> body = filter == null || filter.isEmpty() ? Map.of() : Map.of("filter", filter);
        String response = send("POST", "/collections/" + collection + "/points/count", body);
        try {
            return mapper.readTree(response).path("result").path("count").asLong(0);
        } catch (Exception failure) {
            return 0;
        }
    }

    public long count(String collection) {
        return count(collection, null);
    }

    /** 只判断服务在不在，不假设某个 collection 已经建好。 */
    public boolean ping() {
        try {
            return sendRaw("GET", "/collections", null).statusCode() / 100 == 2;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** filter 的 must 子句：全部为 keyword 精确匹配，跨租户隔离靠这里。 */
    public static Map<String, Object> mustFilter(Map<String, String> equality) {
        List<Map<String, Object>> must = new ArrayList<>();
        equality.forEach((key, value) -> must.add(Map.of("key", key, "match", Map.of("value", value))));
        return must.isEmpty() ? Map.of() : Map.of("must", must);
    }

    private boolean collectionExists(String collection) {
        try {
            HttpResponse<String> response = sendRaw("GET", "/collections/" + collection, null);
            return response.statusCode() / 100 == 2;
        } catch (RuntimeException transportFailure) {
            throw new IllegalStateException("Qdrant 不可达 " + collection + ": " + transportFailure.getMessage(),
                    transportFailure);
        }
    }

    private HttpResponse<String> sendRaw(String method, String path, Object body) {
        try {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .method(method, publisher)
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (RuntimeException known) {
            throw known;
        } catch (Exception failure) {
            throw new IllegalStateException("Qdrant 调用失败 " + path + ": " + failure.getMessage(), failure);
        }
    }

    private String send(String method, String path, Object body) {
        HttpResponse<String> response = sendRaw(method, path, body);
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Qdrant " + method + " " + path + " -> "
                    + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    public record Point(String id, float[] vector, Map<String, Object> payload) {
    }

    public record Hit(String id, double score, Map<String, Object> payload) {
    }
}
