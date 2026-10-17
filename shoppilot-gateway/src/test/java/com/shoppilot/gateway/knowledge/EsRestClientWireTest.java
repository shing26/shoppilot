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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ES REST 客户端的线上行为（对着假服务跑，不打真集群）。
 *
 * <p>这个文件存在的理由是一条 round33 真机复现的回归：`ensureIndex` 原来「索引已存在就 return」，
 * 于是老索引缺 `domain` 映射，重新入库时 ES 走动态映射把它当 `text`，`term` 过滤永远不命中——
 * 检索会静默地把跨行业条款放进来。所以这里钉的是**发出去的请求长什么样**：
 * 表不存在就带 mapping 建表，表已存在就把 mapping 合并一次，两种路径的 body 都必须含 `domain`。
 */
class EsRestClientWireTest {

    private HttpServer server;
    private String baseUrl;
    private final Map<String, String> requestBodies = new LinkedHashMap<>();
    private volatile Map<String, Integer> statusOverrides = Map.of();

    @BeforeEach
    void startFakeElasticsearch() throws IOException {
        requestBodies.clear();
        statusOverrides = Map.of();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (requestBodies) {
                requestBodies.put(key, body);
            }
            int status = statusOverrides.getOrDefault(key, 200);
            byte[] bytes = "{\"acknowledged\":true}".getBytes(StandardCharsets.UTF_8);
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

    private EsRestClient client() {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.retrieval()).thenReturn(new GatewayProperties.Retrieval(
                "http://127.0.0.1:1", baseUrl, "rules", "answer_cache", "policy_rules", 10, 10, 5, 60));
        return new EsRestClient(HttpClient.newHttpClient(), new ObjectMapper(), properties);
    }

    private String bodyOf(String key) {
        synchronized (requestBodies) {
            return requestBodies.getOrDefault(key, "");
        }
    }

    @Test
    @DisplayName("索引不存在：带 domain 映射建表（PUT /policy_rules）")
    void createsIndexWithDomainMappingWhenAbsent() {
        statusOverrides = Map.of("GET /policy_rules", 404);

        assertThatCode(() -> client().ensureIndex("policy_rules")).doesNotThrowAnyException();

        assertThat(bodyOf("PUT /policy_rules")).contains("\"domain\"").contains("\"keyword\"");
    }

    @Test
    @DisplayName("索引已存在：把 domain 映射合并进去（PUT /policy_rules/_mapping），不许直接 return")
    void mergesDomainMappingIntoExistingIndex() {
        statusOverrides = Map.of("GET /policy_rules", 200);

        assertThatCode(() -> client().ensureIndex("policy_rules")).doesNotThrowAnyException();

        assertThat(bodyOf("PUT /policy_rules/_mapping")).contains("\"domain\"").contains("\"keyword\"");
    }
}
