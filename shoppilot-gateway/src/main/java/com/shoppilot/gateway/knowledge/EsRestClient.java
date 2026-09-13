package com.shoppilot.gateway.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
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
 * Elasticsearch REST 客户端，提供 BM25 一路召回（ADR 0010）。
 *
 * <p>停在"够用级"：不自定义分词器、不做同义词词典、不上精排。
 * 30 篇政策语料喂不出这些优化的价值，这是判断不是妥协。
 */
@Component
public class EsRestClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public EsRestClient(HttpClient http, ObjectMapper mapper, GatewayProperties properties) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = properties.retrieval().esUrl();
    }

    public void ensureIndex(String index) {
        String existing = trySend("GET", "/" + index);
        if (existing != null) {
            return;
        }
        Map<String, Object> mappings = new LinkedHashMap<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("text", Map.of("type", "text"));
        properties.put("title", Map.of("type", "text"));
        properties.put("ruleType", Map.of("type", "keyword"));
        properties.put("applicableCategory", Map.of("type", "keyword"));
        properties.put("scope", Map.of("type", "keyword"));
        properties.put("tenantId", Map.of("type", "keyword"));
        properties.put("intent", Map.of("type", "keyword"));
        properties.put("kbEpoch", Map.of("type", "long"));
        properties.put("sourceDoc", Map.of("type", "keyword"));
        properties.put("headingPath", Map.of("type", "keyword"));
        properties.put("effectiveFrom", Map.of("type", "keyword"));
        mappings.put("properties", properties);
        Map<String, Object> body = Map.of("settings", Map.of("number_of_shards", 1, "number_of_replicas", 0),
                "mappings", mappings);
        send("PUT", "/" + index, body);
    }

    public void index(String index, String docId, Map<String, Object> document) {
        send("PUT", "/" + index + "/_doc/" + docId + "?refresh=true", document);
    }

    public List<Hit> search(String index, String query, Map<String, String> termFilters, int size) {
        List<Map<String, Object>> must = new ArrayList<>();
        must.add(Map.of("multi_match", Map.of(
                "query", query,
                "fields", List.of("text^2", "title"),
                "type", "best_fields")));
        List<Map<String, Object>> filter = new ArrayList<>();
        termFilters.forEach((key, value) -> filter.add(Map.of("term", Map.of(key, value))));
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", must);
        if (!filter.isEmpty()) {
            bool.put("filter", filter);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", size);
        body.put("_source", List.of("ruleId", "text", "title", "ruleType", "applicableCategory", "scope", "headingPath"));
        body.put("query", Map.of("bool", bool));
        String response = send("POST", "/" + index + "/_search", body);
        List<Hit> hits = new ArrayList<>();
        try {
            JsonNode buckets = mapper.readTree(response).path("hits").path("hits");
            for (JsonNode node : buckets) {
                JsonNode source = node.path("_source");
                hits.add(new Hit(node.path("_id").asText(), node.path("_score").asDouble(),
                        source.path("text").asText(""), source.path("title").asText(""),
                        source.path("ruleType").asText(""), source.path("applicableCategory").asText(""),
                        source.path("scope").asText("")));
            }
        } catch (Exception failure) {
            throw new IllegalStateException("解析 ES 检索结果失败", failure);
        }
        return hits;
    }

    /** 暴露一次原始查询，供混合检索自己组装 bool 查询（可见范围是"本店铺 + 平台"两个 term）。 */
    public List<Hit> searchRaw(String index, Map<String, Object> body) {
        String response = send("POST", "/" + index + "/_search", body);
        List<Hit> hits = new ArrayList<>();
        try {
            JsonNode buckets = mapper.readTree(response).path("hits").path("hits");
            for (JsonNode node : buckets) {
                JsonNode source = node.path("_source");
                hits.add(new Hit(node.path("_id").asText(), node.path("_score").asDouble(),
                        source.path("text").asText(""), source.path("title").asText(""),
                        source.path("ruleType").asText(""), source.path("applicableCategory").asText(""),
                        source.path("scope").asText("")));
            }
        } catch (Exception failure) {
            throw new IllegalStateException("解析 ES 检索结果失败", failure);
        }
        return hits;
    }

    public long count(String index) {
        String response = trySend("POST", "/" + index + "/_count");
        if (response == null) {
            return 0;
        }
        try {
            return mapper.readTree(response).path("count").asLong(0);
        } catch (Exception failure) {
            return 0;
        }
    }

    private String trySend(String method, String path) {
        try {
            return send(method, path, null);
        } catch (RuntimeException notFound) {
            return null;
        }
    }

    /**
     * 词法引擎可达性探针（ADR 0026 的 deps 组用）。与 Qdrant 那边同名同语义。
     *
     * <p>不能用 {@link #count(String)} 代：它在"索引是空的"与"服务没起来"两种情况下都返回 0，
     * 而 deps 要答的正是这两者里的那一个——索引空了该由知识库那一格去说。
     */
    public boolean ping() {
        return trySend("GET", "/") != null;
    }

    private String send(String method, String path, Object body) {
        try {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .method(method, publisher)
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("ES " + method + " " + path + " -> "
                        + response.statusCode() + " " + response.body());
            }
            return response.body();
        } catch (RuntimeException known) {
            throw known;
        } catch (Exception failure) {
            throw new IllegalStateException("ES 调用失败 " + path + ": " + failure.getMessage(), failure);
        }
    }

    public record Hit(String ruleId, double score, String text, String title, String ruleType,
                      String applicableCategory, String scope) {
    }
}
