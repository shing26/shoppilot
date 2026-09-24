package com.shoppilot.gateway.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ollama 侧请求体（ADR 0044 票 50）。
 *
 * <p>本仓此前没有任何用例碰过 `OllamaLlmClient` 的请求体——`local` 档的采样参数等于没人看。
 * 这里用一个假 Ollama 收原始请求体，钉住两件事：输出上限按 `options.num_predict` 下发（不是
 * `max_tokens`，那是 OpenAI 兼容面的字段名），以及 0 值走"不发字段"而不是发 `num_predict: 0`
 * （后者在 Ollama 里等于最多生成 0 个 token）。
 */
class OllamaLlmClientTest {

    private static final String REPLY = "{\"message\":{\"content\":\"好的\"},\"prompt_eval_count\":3,\"eval_count\":2}";

    private HttpServer server;
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();
    private String baseUrl;

    @BeforeEach
    void startFakeOllama() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                received.add(new ObjectMapper().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
            byte[] body = REPLY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(null);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopFakeOllama() {
        server.stop(0);
    }

    @Test
    @DisplayName("输出上限按 options.num_predict 下发（不是 max_tokens）")
    void maxOutputTokensGoesToNumPredict() {
        client(512).complete(request());

        JsonNode sent = received.get(0);
        assertThat(sent.path("options").path("num_predict").asInt()).isEqualTo(512);
        // 字段名跟着端点走：Ollama 不认 OpenAI 兼容面的 max_tokens
        assertThat(sent.has("max_tokens")).isFalse();
        assertThat(sent.path("options").path("temperature").asDouble()).isEqualTo(0.2d);
    }

    @Test
    @DisplayName("输出上限 0 = 不限制：options 里不出现 num_predict")
    void zeroMaxOutputTokensOmitsNumPredict() {
        client(0).complete(request());

        JsonNode options = received.get(0).path("options");
        assertThat(options.has("num_predict")).isFalse();
        assertThat(options.path("temperature").asDouble()).isEqualTo(0.2d);
    }

    @Test
    @DisplayName("流式路径用同一份 payload 构造：上限同样下发")
    void streamingCarriesTheSameCap() {
        client(256).stream(request(), token -> {
        });

        assertThat(received.get(0).path("stream").asBoolean()).isTrue();
        assertThat(received.get(0).path("options").path("num_predict").asInt()).isEqualTo(256);
    }

    private static LlmTypes.Request request() {
        return new LlmTypes.Request(List.of(LlmTypes.Message.user("在吗")), List.of(), 0.2d);
    }

    private OllamaLlmClient client(int maxOutputTokens) {
        GatewayProperties.Llm config = new GatewayProperties.Llm("local", "http://127.0.0.1:1", "unused",
                "unused-cloud-model", 0.2d, Duration.ofSeconds(2), Duration.ofSeconds(10), 0L,
                baseUrl, "qwen2.5:3b", Duration.ofMillis(1), Duration.ofMillis(1), maxOutputTokens);
        return new OllamaLlmClient(java.net.http.HttpClient.newHttpClient(), new ObjectMapper(), config);
    }
}
