package com.shoppilot.gateway.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * dev 模式那条路的契约测试（ADR 0012）：DashScope 的 compatible-mode 端点走的就是这个客户端。
 *
 * <p>用 JDK 自带的 HttpServer 起本地端点，不需要任何云端 key，但"发出去的请求长什么样、
 * 回来的流怎么攒、对面 500/超时算什么异常"三件事从此有机器证据。之前整个 dev 路径
 * 一个用例都没有，key 一填上就是拿真金白银去试没测过的代码。
 */
class OpenAiCompatibleLlmClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer server;
    private static String baseUrl;

    /** 每个用例把想让对方回的东西放这里：状态码、内容类型、若干行正文、回之前睡多久。 */
    private static final AtomicReference<Stubbed> STUB = new AtomicReference<>();
    private static final List<Recorded> RECEIVED = Collections.synchronizedList(new ArrayList<>());

    private record Stubbed(int status, String contentType, List<String> bodyLines, long delayMillis) {
    }

    private record Recorded(String path, String authorization, String contentType, JsonNode body) {
    }

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/v1/chat/completions", OpenAiCompatibleLlmClientTest::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Stubbed stub = STUB.get();
        try {
            RECEIVED.add(new Recorded(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    MAPPER.readTree(raw)));
        } catch (Exception unparsableBody) {
            RECEIVED.add(new Recorded(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), null));
        }
        // 读超时那个用例里连接会先被客户端掐掉，这里写响应就会抛：忽略，本端已经拿到想要的异常
        try {
            if (stub.delayMillis() > 0) {
                Thread.sleep(stub.delayMillis());
            }
            exchange.getResponseHeaders().add("Content-Type", stub.contentType());
            if (stub.contentType().startsWith("text/event-stream")) {
                // 逐行 flush：让客户端真的按"一行一行到货"去解析，而不是一把拿到整包
                exchange.sendResponseHeaders(stub.status(), 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (String line : stub.bodyLines()) {
                        out.write(line.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
                return;
            }
            byte[] payload = String.join("", stub.bodyLines()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(stub.status(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        } catch (Exception clientGoneAway) {
            // 见上
        }
    }

    private static void stubJson(String json) {
        STUB.set(new Stubbed(200, "application/json", List.of(json), 0));
    }

    private static void stubStream(String... lines) {
        STUB.set(new Stubbed(200, "text/event-stream", List.of(lines), 0));
    }

    /** 一行 SSE：{@code data:{"choices":[{"delta":{...}}]}}，用 Jackson 拼，免得手写转义骗过测试。 */
    private static String dataLine(Map<String, Object> delta, Map<String, Object> usage) {
        Map<String, Object> root = new LinkedHashMap<>();
        if (delta != null) {
            root.put("choices", List.of(Map.of("delta", delta)));
        }
        if (usage != null) {
            root.put("usage", usage);
        }
        try {
            return "data:" + MAPPER.writeValueAsString(root) + "\n";
        } catch (Exception unserializable) {
            throw new IllegalStateException(unserializable);
        }
    }

    private static Map<String, Object> toolCallDelta(int index, String id, String name, String argumentsFragment) {
        Map<String, Object> function = new LinkedHashMap<>();
        if (name != null) {
            function.put("name", name);
        }
        function.put("arguments", argumentsFragment);
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("index", index);
        if (id != null) {
            call.put("id", id);
        }
        call.put("function", function);
        return call;
    }

    private static OpenAiCompatibleLlmClient client(Duration readTimeout) {
        return client(readTimeout, 1024);
    }

    private static OpenAiCompatibleLlmClient client(Duration readTimeout, int maxOutputTokens) {
        GatewayProperties.Llm llm = new GatewayProperties.Llm("dev", baseUrl, "test-key", "qwen-max",
                0.2d, Duration.ofSeconds(2), readTimeout, 200_000L, "http://127.0.0.1:11434", "qwen2.5:3b",
                Duration.ofMillis(1), Duration.ofMillis(1), maxOutputTokens);
        return new OpenAiCompatibleLlmClient(HttpClient.newHttpClient(), MAPPER, llm);
    }

    private static LlmTypes.Request ask(String query, List<Map<String, Object>> tools, double temperature) {
        return new LlmTypes.Request(List.of(LlmTypes.Message.system("你是客服"), LlmTypes.Message.user(query)),
                tools, temperature);
    }

    @Test
    @DisplayName("非流式：请求带 Bearer 与 tools，回来的 tool_calls 解析成结构化调用")
    void completeSendsToolsAndParsesToolCalls() {
        RECEIVED.clear();
        stubJson("""
                {"choices":[{"message":{"content":null,"tool_calls":[
                 {"id":"call_1","function":{"name":"queryOrderDetail","arguments":"{\\"orderNo\\":\\"10023\\"}"}}]}}],
                 "usage":{"prompt_tokens":11,"completion_tokens":5}}""");

        LlmTypes.Reply reply = client(Duration.ofSeconds(10)).complete(
                ask("订单 10023 发货没", List.of(Map.of("type", "function",
                        "function", Map.of("name", "queryOrderDetail"))), 0.0d));

        Recorded sent = RECEIVED.get(RECEIVED.size() - 1);
        assertThat(sent.path()).isEqualTo("/v1/chat/completions");
        assertThat(sent.authorization()).isEqualTo("Bearer test-key");
        assertThat(sent.contentType()).isEqualTo("application/json");
        assertThat(sent.body().path("model").asText()).isEqualTo("qwen-max");
        assertThat(sent.body().path("stream").asBoolean()).isFalse();
        // 没给温度（<=0）才回落到配置值
        assertThat(sent.body().path("temperature").asDouble()).isEqualTo(0.2d);
        // 输出上限显式下发（ADR 0044 票 50）：这是稳定性护栏，不是成本优化
        assertThat(sent.body().path("max_tokens").asInt()).isEqualTo(1024);
        assertThat(sent.body().path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(sent.body().path("tools").get(0).path("function").path("name").asText())
                .isEqualTo("queryOrderDetail");
        assertThat(sent.body().path("tool_choice").asText()).isEqualTo("auto");
        // 状态机每轮只派发一个工具：请求层必须显式关掉并行返回（票 41）
        assertThat(sent.body().path("parallel_tool_calls").isBoolean()).isTrue();
        assertThat(sent.body().path("parallel_tool_calls").asBoolean()).isFalse();
        assertThat(reply.wantsTool()).isTrue();
        assertThat(reply.toolCalls().get(0).name()).isEqualTo("queryOrderDetail");
        assertThat(reply.toolCalls().get(0).arguments()).containsEntry("orderNo", "10023");
        assertThat(reply.promptTokens()).isEqualTo(11);
        assertThat(reply.completionTokens()).isEqualTo(5);
    }

    @Test
    @DisplayName("工具往返消息按协议序列化：assistant 的 arguments 是 JSON 字符串，tool 消息带 tool_call_id")
    void serializesToolRoundTripMessages() {
        RECEIVED.clear();
        stubJson("{\"choices\":[{\"message\":{\"content\":\"已发货\"}}]}");
        var call = new LlmTypes.ToolCall("call_9", "queryOrderDetail", Map.of("orderNo", "10023"));

        LlmTypes.Reply reply = client(Duration.ofSeconds(10)).complete(new LlmTypes.Request(
                List.of(LlmTypes.Message.user("订单 10023 发货没"),
                        LlmTypes.Message.assistant("", List.of(call)),
                        LlmTypes.Message.tool("call_9", "{\"status\":\"SHIPPED\"}")),
                List.of(), 0.7d));

        JsonNode body = RECEIVED.get(RECEIVED.size() - 1).body();
        JsonNode assistant = body.path("messages").get(1);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        assertThat(assistant.path("tool_calls").get(0).path("id").asText()).isEqualTo("call_9");
        assertThat(assistant.path("tool_calls").get(0).path("function").path("arguments").asText())
                .isEqualTo("{\"orderNo\":\"10023\"}");
        JsonNode tool = body.path("messages").get(2);
        assertThat(tool.path("role").asText()).isEqualTo("tool");
        assertThat(tool.path("tool_call_id").asText()).isEqualTo("call_9");
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.7d);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(1024);
        // 没有 tools 就不该把 tools/tool_choice 发出去
        assertThat(body.has("tools")).isFalse();
        assertThat(reply.content()).isEqualTo("已发货");
        assertThat(reply.wantsTool()).isFalse();
    }

    @Test
    @DisplayName("输出上限 0 = 不限制：请求体里不出现 max_tokens（ADR 0044 票 50 的向后兼容口）")
    void zeroMaxOutputTokensOmitsTheField() {
        RECEIVED.clear();
        stubJson("{\"choices\":[{\"message\":{\"content\":\"好的\"}}]}");

        client(Duration.ofSeconds(10), 0).complete(new LlmTypes.Request(
                List.of(LlmTypes.Message.user("在吗")), List.of(), 0.2d));

        JsonNode body = RECEIVED.get(RECEIVED.size() - 1).body();
        // 0 必须走"不发这个字段"，而不是发 max_tokens:0 —— 后者在多数端点上等于"最多生成 0 个 token"
        assertThat(body.has("max_tokens")).isFalse();
    }

    @Test
    @DisplayName("流式：逐块吐正文，尾部 usage 记账，心跳与空行忽略；不请求 tools 就不带 stream_options 之外的字段")
    void streamAccumulatesDeltasAndUsage() {
        RECEIVED.clear();
        stubStream(": ping\n",
                "\n",
                dataLine(Map.of("content", "你的"), null),
                dataLine(Map.of("content", "订单"), null),
                dataLine(Map.of("content", "已发货"), null),
                dataLine(null, Map.of("prompt_tokens", 7, "completion_tokens", 3)),
                "data:[DONE]\n");
        List<String> pushed = new ArrayList<>();

        LlmTypes.Reply reply = client(Duration.ofSeconds(10)).stream(
                ask("查订单", List.of(), 0.0d), pushed::add);

        assertThat(pushed).containsExactly("你的", "订单", "已发货");
        assertThat(reply.content()).isEqualTo("你的订单已发货");
        assertThat(reply.promptTokens()).isEqualTo(7);
        assertThat(reply.completionTokens()).isEqualTo(3);
    }

    @Test
    @DisplayName("流式的 tool_calls 增量按 index 拼回来：曾经这里直接丢弃，工具调用静默消失")
    void streamAssemblesToolCallArgumentFragments() {
        stubStream(
                dataLine(Map.of("tool_calls", List.of(
                        toolCallDelta(0, "call_7", "applyRefund", "{\"order"))), null),
                dataLine(Map.of("tool_calls", List.of(
                        toolCallDelta(0, null, null, "No\":\"10023\""))), null),
                dataLine(Map.of("tool_calls", List.of(
                        toolCallDelta(0, null, null, ",\"amount\":59}"))), null),
                dataLine(Map.of("tool_calls", List.of(
                        toolCallDelta(1, "call_8", "queryOrderDetail", "{}"))), null),
                dataLine(null, Map.of("prompt_tokens", 9, "completion_tokens", 6)),
                "data:[DONE]\n");

        LlmTypes.Reply reply = client(Duration.ofSeconds(10)).stream(
                ask("退掉 10023", List.of(Map.of("type", "function")), 0.0d), piece -> {
                });

        assertThat(reply.toolCalls()).hasSize(2);
        assertThat(reply.toolCalls().get(0).id()).isEqualTo("call_7");
        assertThat(reply.toolCalls().get(0).name()).isEqualTo("applyRefund");
        assertThat(reply.toolCalls().get(0).arguments())
                .containsEntry("orderNo", "10023")
                .containsEntry("amount", 59);
        // 第二个调用不串到第一个槽位上，缺 id 也要有稳定值
        assertThat(reply.toolCalls().get(1).id()).isEqualTo("call_8");
        assertThat(reply.toolCalls().get(1).name()).isEqualTo("queryOrderDetail");
        assertThat(reply.totalTokens()).isEqualTo(15);
        // 流式带工具时必须声明要增量 usage，否则节约率没有分母
        assertThat(RECEIVED.get(RECEIVED.size() - 1).body().path("stream_options").path("include_usage").asBoolean())
                .isTrue();
    }

    @Test
    @DisplayName("参数解析不出来时原文留在 _unparsable，不静默变成空调用")
    void keepsUnparsableArgumentsVerbatim() {
        stubJson("{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"c1\","
                + "\"function\":{\"name\":\"applyRefund\",\"arguments\":\"{order\"}}]}}]}");

        LlmTypes.Reply reply = client(Duration.ofSeconds(10)).complete(ask("退款", List.of(), 0.0d));

        assertThat(reply.toolCalls()).hasSize(1);
        assertThat(reply.toolCalls().get(0).arguments()).containsEntry("_unparsable", "{order");
    }

    @Test
    @DisplayName("对面非 2xx 记成 UNAVAILABLE：降级要落 LLM_* 工单，不是 500 直穿用户")
    void nonSuccessStatusBecomesUnavailable() {
        STUB.set(new Stubbed(500, "application/json", List.of("{\"error\":\"boom\"}"), 0));

        assertThatThrownBy(() -> client(Duration.ofSeconds(10)).complete(ask("查订单", List.of(), 0.0d)))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).kind())
                .isEqualTo(LlmException.Kind.UNAVAILABLE);
    }

    @Test
    @DisplayName("流式非 2xx 同样记成 UNAVAILABLE")
    void streamNonSuccessStatusBecomesUnavailable() {
        STUB.set(new Stubbed(429, "application/json", List.of("{\"error\":\"throttled\"}"), 0));

        assertThatThrownBy(() -> client(Duration.ofSeconds(10)).stream(ask("查订单", List.of(), 0.0d), piece -> {
        }))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).kind())
                .isEqualTo(LlmException.Kind.UNAVAILABLE);
    }

    @Test
    @DisplayName("读超时记成 TIMEOUT，与超时注入走同一个异常类型")
    void readTimeoutBecomesTimeoutKind() {
        STUB.set(new Stubbed(200, "application/json", List.of("{}"), 900));

        assertThatThrownBy(() -> client(Duration.ofMillis(200)).complete(ask("查订单", List.of(), 0.0d)))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).kind())
                .isEqualTo(LlmException.Kind.TIMEOUT);
    }
}
