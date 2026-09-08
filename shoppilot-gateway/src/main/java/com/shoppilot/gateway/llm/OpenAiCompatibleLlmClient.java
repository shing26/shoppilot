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
        StringBuilder accumulated = new StringBuilder();
        int[] usage = new int[2];
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
                lines.forEach(line -> consume(line, tokenSink, accumulated, usage));
            }
            return new LlmTypes.Reply(accumulated.toString(), List.of(), usage[0], usage[1], null);
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

    private void consume(String line, Consumer<String> sink, StringBuilder accumulated, int[] usage) {
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
            JsonNode delta = chunk.path("choices").path(0).path("delta").path("content");
            if (delta.isTextual() && !delta.asText().isEmpty()) {
                accumulated.append(delta.asText());
                sink.accept(delta.asText());
            }
        } catch (Exception unparsableHeartbeat) {
            // 心跳与注释行按协议本就该被忽略
        }
    }

    private Map<String, Object> payload(LlmTypes.Request request, boolean stream) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.model());
        payload.put("temperature", request.temperature() <= 0 ? config.temperature() : request.temperature());
        payload.put("messages", messages(request.messages()));
        payload.put("stream", stream);
        if (stream) {
            // 不请求增量 usage 就拿不到节约率的真实分母
            payload.put("stream_options", Map.of("include_usage", true));
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            payload.put("tools", request.tools());
            payload.put("tool_choice", "auto");
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
                Map<String, Object> arguments = new LinkedHashMap<>();
                String rawArguments = function.path("arguments").asText("");
                if (!rawArguments.isBlank()) {
                    try {
                        arguments = mapper.readValue(rawArguments, Map.class);
                    } catch (Exception malformedArguments) {
                        arguments = Map.of("_unparsable", rawArguments);
                    }
                }
                calls.add(new LlmTypes.ToolCall(call.path("id").asText("call-" + calls.size()),
                        function.path("name").asText(), arguments));
            }
        }
        JsonNode usage = root.path("usage");
        return new LlmTypes.Reply(content, calls,
                usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0), root.toString());
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300);
    }
}
