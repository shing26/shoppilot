package com.shoppilot.gateway.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Qdrant REST 客户端的线上行为（对着假服务跑，不打真集群）。
 *
 * <p>这个文件存在的理由是一条 mock 测试放过去的回归：把 flush 改成 {@code recreate=true} 那次，
 * 单元测试验的是"调了 recreateCollection"，全部通过；真机上 {@code PUT /collections/x?recreate=true}
 * 在表已存在时回 409，于是 {@code /ops/cache/flush} 整个 500，验收矩阵一次红了八步。
 * 所以这里钉的是**发出去的请求长什么样、非 2xx 怎么分类**，不是"调没调某个方法"。
 * 404 响应体是从 Qdrant 1.12.4 上原样抄下来的。
 */
class QdrantRestClientWireTest {

    /** 真机抓取：PUT /collections/answer_cache?recreate=true&wait=true（表存在时）。 */
    private static final String QDRANT_409_ALREADY_EXISTS =
            "{\"status\":{\"error\":\"Wrong input: Collection `answer_cache` already exists!\",\"time\":0.000263}}";
    /** 真机抓取：POST /collections/answer_cache/points/search（表被删掉之后）。 */
    private static final String QDRANT_404_MISSING_TABLE =
            "{\"status\":{\"error\":\"Not found: Collection `answer_cache` doesn't exist!\",\"time\":4.0e-6}}";

    private HttpServer server;
    private String baseUrl;
    private final List<String> requests = new ArrayList<>();
    private volatile Map<String, Integer> statusOverrides = Map.of();
    private volatile Map<String, String> bodyOverrides = Map.of();

    @BeforeEach
    void startFakeQdrant() throws IOException {
        requests.clear();
        statusOverrides = Map.of();
        bodyOverrides = Map.of();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI();
            synchronized (requests) {
                requests.add(key);
            }
            exchange.getRequestBody().readAllBytes();
            int status = statusOverrides.getOrDefault(key, 200);
            String body = bodyOverrides.getOrDefault(key, "{\"result\":true,\"status\":\"ok\"}");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private QdrantRestClient client() {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.retrieval()).thenReturn(new GatewayProperties.Retrieval(baseUrl,
                "http://127.0.0.1:1", "policy_rules", "answer_cache", "rules", 10, 10, 5, 60));
        return new QdrantRestClient(HttpClient.newHttpClient(), new ObjectMapper(), properties);
    }

    private List<String> sent() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    @Test
    @DisplayName("ensureCollection 直接 PUT 建表并带上 wait=true，不先 GET 判存在")
    void ensureCreatesWithWaitAndNoExistenceProbe() {
        assertThatCode(() -> client().ensureCollection("answer_cache", 1024,
                List.of("tenant_id"), List.of("kb_epoch"))).doesNotThrowAnyException();

        assertThat(sent()).contains(
                "PUT /collections/answer_cache?wait=true",
                "PUT /collections/answer_cache/index?wait=true");
        assertThat(sent()).noneMatch(line -> line.startsWith("GET "));
    }

    @Test
    @DisplayName("表已存在的 409 算就绪，不是失败（flush 500 回归的直接来源）")
    void alreadyExistsCountsAsReady() {
        statusOverrides = Map.of("PUT /collections/answer_cache?wait=true", 409);
        bodyOverrides = Map.of("PUT /collections/answer_cache?wait=true", QDRANT_409_ALREADY_EXISTS);

        QdrantRestClient client = client();
        assertThatCode(() -> client.ensureCollection("answer_cache", 1024, List.of("tenant_id"),
                List.of("kb_epoch"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非 409 的建表失败照样抛，不静默吞掉")
    void otherCreateFailuresStillThrow() {
        statusOverrides = Map.of("PUT /collections/answer_cache?wait=true", 500);
        bodyOverrides = Map.of("PUT /collections/answer_cache?wait=true", "{\"status\":{\"error\":\"boom\"}}");

        assertThatThrownBy(() -> client().ensureCollection("answer_cache", 1024, List.of("tenant_id"),
                List.of("kb_epoch")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("deleteCollection 带 wait=true，表本来不存在（404）算成功")
    void deleteWaitsAndTreatsMissingAsSuccess() {
        QdrantRestClient client = client();
        assertThat(client.deleteCollection("answer_cache")).isTrue();
        assertThat(sent()).contains("DELETE /collections/answer_cache?wait=true");

        statusOverrides = Map.of("DELETE /collections/answer_cache?wait=true", 404);
        assertThat(client.deleteCollection("answer_cache")).isTrue();
    }

    @Test
    @DisplayName("检索撞到 Qdrant 原样 404 时，消息能被认成「表不存在」")
    void missingTableIsRecognisedFromRealPayload() {
        statusOverrides = Map.of("POST /collections/answer_cache/points/search", 404);
        bodyOverrides = Map.of("POST /collections/answer_cache/points/search", QDRANT_404_MISSING_TABLE);

        QdrantRestClient client = client();
        assertThatThrownBy(() -> client.search("answer_cache", new float[]{0.1f}, Map.of(), 1, 0.95d))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertThat(
                        QdrantRestClient.isMissingCollection(failure)).isTrue());
    }

    @Test
    @DisplayName("超时/不可达不算「表不存在」")
    void transportFailureIsNotMissingTable() {
        server.stop(0);
        QdrantRestClient client = client();

        assertThatThrownBy(() -> client.search("answer_cache", new float[]{0.1f}, Map.of(), 1, 0.95d))
                .isInstanceOf(RuntimeException.class)
                .satisfies(failure -> assertThat(QdrantRestClient.isMissingCollection(failure)).isFalse());
    }

    @Test
    @DisplayName("mustFilter 只给等值条件，跨租户隔离靠它")
    void mustFilterShape() {
        assertThat(QdrantRestClient.mustFilter(Map.of("tenant_id", "T001")))
                .isEqualTo(Map.of("must", List.of(Map.of("key", "tenant_id",
                        "match", Map.of("value", "T001")))));
        assertThat(QdrantRestClient.mustFilter(Map.of())).isEqualTo(Map.of());
    }
}
