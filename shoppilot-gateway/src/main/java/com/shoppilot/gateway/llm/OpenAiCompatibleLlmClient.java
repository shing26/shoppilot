package com.shoppilot.gateway.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * OpenAI 兼容协议客户端，dev 模式指向 DashScope 的 compatible-mode 端点（ADR 0012）。
 *
 * <p>用 JDK HttpClient 而非厂商 SDK：换供应商只改 base-url 与 model，代码零改动。
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final GatewayProperties.Llm config;

    public OpenAiCompatibleLlmClient(HttpClient http, ObjectMapper mapper, GatewayProperties.Llm config) {
        this.http = http;
        this.mapper = mapper;
        this.config = config;
    }

    @Override
    public LlmTypes.Reply complete(LlmTypes.Request request) {
        try {
            String body = mapper.writeValueAsString(payload(request, false));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/chat/completions"))
                    .timeout(config.readTimeout())
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw LlmException.unavailable("模型返回 " + response.statusCode() + ": " + truncate(response.body()), null);
            }
            return parse(mapper.readTree(response.body()));
        } catch (LlmException known) {
            throw known;
        } catch (HttpTimeoutException timedOut) {
            throw LlmException.timeout("模型调用超时", timedOut);
        } catch (Exception failure) {
            throw LlmException.unavailable("模型调用失败: " + failure.getMessage(), failure);
        }
    }

    @Override
    public LlmTypes.Reply stream(LlmTypes.Request request, Consumer<String> tokenSink) {
        StreamState state = new StreamState(tokenSink);
        try {
            String body = mapper.writeValueAsString(payload(request, true));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/chat/completions"))
                    .timeout(config.readTimeout())
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Stream<String>> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() / 100 != 2) {
                throw LlmException.unavailable("模型流式返回 " + response.statusCode(), null);
            }
            try (Stream<String> lines = response.body()) {
                lines.forEach(state::consume);
            }
            return state.build();
        } catch (LlmException known) {
            throw known;
        } catch (HttpTimeoutException timedOut) {
            throw LlmException.timeout("模型流式调用超时", timedOut);
        } catch (Exception failure) {
            throw LlmException.unavailable("模型流式调用失败: " + failure.getMessage(), failure);
        }
    }

    @Override
    public String mode() {
        return "dev";
    }

    private Map<String, Object> payload(LlmTypes.Request request, boolean stream) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.model());
        payload.put("temperature", request.temperature() <= 0 ? config.temperature() : request.temperature());
        // 输出上限（ADR 0044 票 50）：这是**稳定性护栏**不是成本优化——成本护栏是 ADR 0012 的日预算熔断。
        // 0 = 不限制（向后兼容：本字段引入前所有请求都是无上限的）
        if (config.maxOutputTokens() > 0) {
            payload.put("max_tokens", config.maxOutputTokens());
        }
        payload.put("messages", messages(request.messages()));
        payload.put("stream", stream);
        if (stream) {
            // 不请求增量 usage 就拿不到节约率的真实分母
            payload.put("stream_options", Map.of("include_usage", true));
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            payload.put("tools", request.tools());
            payload.put("tool_choice", "auto");
            // 状态机每轮只派发一个工具（ADR 0008 串行预算）：从请求层关掉并行返回；
            // 宽松端点忽略此字段时的残余风险由状态机的多调用防御分支兜底（票 41）
            payload.put("parallel_tool_calls", false);
        }
        return payload;
    }

    private List<Map<String, Object>> messages(List<LlmTypes.Message> messages) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (LlmTypes.Message message : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.role());
            item.put("content", message.content() == null ? "" : message.content());
            if (message.toolCallId() != null) {
                item.put("tool_call_id", message.toolCallId());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                List<Map<String, Object>> calls = new ArrayList<>();
                for (LlmTypes.ToolCall call : message.toolCalls()) {
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", call.name());
                    try {
                        function.put("arguments", mapper.writeValueAsString(call.arguments()));
                    } catch (Exception unserializable) {
                        function.put("arguments", "{}");
                    }
                    calls.add(Map.of("id", call.id(), "type", "function", "function", function));
                }
                item.put("tool_calls", calls);
            }
            serialized.add(item);
        }
        return serialized;
    }

    private LlmTypes.Reply parse(JsonNode root) {
        JsonNode message = root.path("choices").path(0).path("message");
        String content = message.path("content").isTextual() ? message.path("content").asText() : null;
        List<LlmTypes.ToolCall> calls = new ArrayList<>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                JsonNode function = call.path("function");
                calls.add(new LlmTypes.ToolCall(call.path("id").asText("call-" + calls.size()),
                        function.path("name").asText(), argumentsOf(function.path("arguments").asText(""))));
            }
        }
        JsonNode usage = root.path("usage");
        return new LlmTypes.Reply(content, calls,
                usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0), root.toString());
    }

    /**
     * 工具参数解析：非空却解析不出来时，原文留在 {@code _unparsable} 里。
     *
     * <p>不能静默变成空参数——那会把"模型给了参数但形状不对"伪装成"模型没给参数"，
     * 前者该看 prompt，后者该走追问槽位，排查方向完全相反。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> argumentsOf(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(rawArguments, Map.class);
        } catch (Exception malformedArguments) {
            return Map.of("_unparsable", rawArguments);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    /** 一次流式响应要攒的东西：正文增量、按 index 分槽的工具调用增量、尾部 usage。 */
    private final class StreamState {

        private final Consumer<String> sink;
        private final StringBuilder text = new StringBuilder();
        private final List<ToolCallBuilder> calls = new ArrayList<>();
        private final int[] usage = new int[2];

        private StreamState(Consumer<String> sink) {
            this.sink = sink;
        }

        private void consume(String line) {
            if (line == null || !line.startsWith("data:")) {
                return;
            }
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) {
                return;
            }
            try {
                JsonNode chunk = mapper.readTree(data);
                JsonNode usageNode = chunk.path("usage");
                if (!usageNode.isMissingNode()) {
                    usage[0] = usageNode.path("prompt_tokens").asInt(usage[0]);
                    usage[1] = usageNode.path("completion_tokens").asInt(usage[1]);
                }
                JsonNode deltas = chunk.path("choices").path(0).path("delta");
                String piece = deltas.path("content").asText("");
                if (!piece.isEmpty()) {
                    text.append(piece);
                    sink.accept(piece);
                }
                JsonNode callDeltas = deltas.path("tool_calls");
                if (callDeltas.isArray()) {
                    accumulateToolCalls(callDeltas);
                }
            } catch (Exception unparsableHeartbeat) {
                // 心跳与注释行按协议本就该被忽略
            }
        }

        private LlmTypes.Reply build() {
            List<LlmTypes.ToolCall> assembled = new ArrayList<>();
            for (int i = 0; i < calls.size(); i++) {
                ToolCallBuilder call = calls.get(i);
                assembled.add(new LlmTypes.ToolCall(
                        call.id == null ? "call-" + i : call.id,
                        call.name == null ? "" : call.name,
                        argumentsOf(call.arguments.toString())));
            }
            return new LlmTypes.Reply(text.toString(), assembled, usage[0], usage[1], null);
        }

        private void accumulateToolCalls(JsonNode deltas) {
            for (JsonNode call : deltas) {
                int index = call.path("index").asInt(calls.size());
                while (calls.size() <= index) {
                    calls.add(new ToolCallBuilder());
                }
                calls.get(index).absorb(call);
            }
        }
    }

    /**
     * 单个工具调用的增量累加器。
     *
     * <p>id 与 name 只在首块出现，arguments 是 JSON 文本被切碎后的逐段拼接——
     * 少拼一个逗号就变成 unparsable，所以这里只做拼接，解析留到最后一次做。
     */
    private static final class ToolCallBuilder {

        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private void absorb(JsonNode call) {
            String callId = call.path("id").asText("");
            if (!callId.isEmpty()) {
                id = callId;
            }
            JsonNode function = call.path("function");
            String fnName = function.path("name").asText("");
            if (!fnName.isEmpty()) {
                name = fnName;
            }
            arguments.append(function.path("arguments").asText(""));
        }
    }
}
