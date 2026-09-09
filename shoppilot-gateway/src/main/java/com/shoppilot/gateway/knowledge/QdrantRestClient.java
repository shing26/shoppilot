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
        // 这里故意不做"先 GET 判存在"：判完再建是两次往返，中间插入的另一次 flush 会让两边都以为对方建好了。
        // 直接 PUT，把"已存在"当成就绪状态，见 createCollection。
        createCollection(collection, dimension);
        createIndexes(collection, keywordFields, integerFields);
    }

    private void createCollection(String collection, int dimension) {
        Map<String, Object> vectors = new LinkedHashMap<>();
        vectors.put("size", dimension);
        vectors.put("distance", "Cosine");
        // wait=true：建表要等落定。不带它，PUT 返回时表可能还没就绪，flush 之后紧跟的第一次检索会撞进来
        HttpResponse<String> response = sendRaw("PUT", "/collections/" + collection + "?wait=true",
                Map.of("vectors", vectors));
        if (response.statusCode() == 409) {
            // "已存在"对 ensure 语义来说就是目标达成：启动时表本来就在，或者并发的那路先建好了
            log.debug("collection {} 已存在，跳过建表", collection);
        } else if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Qdrant PUT /collections/" + collection + " -> "
                    + response.statusCode() + " " + response.body());
        }
    }

    private void createIndexes(String collection, List<String> keywordFields, List<String> integerFields) {
        for (String field : keywordFields) {
            createIndex(collection, field, "keyword");
        }
        for (String field : integerFields) {
            // kb_epoch 存的是数字，按 keyword 建索引会建出一个空索引，纪元过滤反而全表扫
            createIndex(collection, field, "integer");
        }
    }

    /**
     * 这个失败是"表还没建出来"还是"存储坏了"？两者对使用者的语义完全不同：前者等于空缓存，
     * 该安静地当 miss；后者才值得 WARN。Qdrant 的不存在是 404 + "Not found: Collection"，
     * 走的是 {@link #send} 抛出的信息串。
     */
    public static boolean isMissingCollection(Throwable failure) {
        String message = failure.getMessage();
        return message != null
                && (message.contains("Not found: Collection") || message.contains("doesn't exist"));
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

    /**
     * 整表清空用删表重建，不用 delete-by-filter：Qdrant 不接受空 filter，硬凑一个恒真条件太脆，
     * 而"说要清空却漏下带旧答案的点"在本项目里是缓存投毒，比多一次往返严重得多。
     *
     * <p>{@code wait=true} 是必须的：不带它，DELETE 只是"已受理"，表在几毫秒后才真的消失。
     * 即便如此，删与建之间仍有一段"表确实不存在"的窗口，落在里面的 L2 检索会拿 404——本机把 flush
     * 与 6 路并发问答混打 12 轮，窗口漏出 48 行「L2 检索失败，按未命中处理」。本来可以并成一步的
     * {@code recreate=true} 在 Qdrant 1.12.4 上不生效（表存在时照样 409；新版 Table API 路径直接 404），
     * 所以这个窗口留着，由读路径按"空缓存"分类并自愈补建，见 {@link #isMissingCollection}。
     */
    public boolean deleteCollection(String collection) {
        HttpResponse<String> response = sendRaw("DELETE", "/collections/" + collection + "?wait=true", null);
        // 404 = 表本来就不存在，对"清空"这个语义来说就是成功
        return response.statusCode() / 100 == 2 || response.statusCode() == 404;
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
