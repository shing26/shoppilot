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
 * local 模式：Ollama 上的 qwen2.5:3b。
 *
 * <p>定位是降级链路验证，不用于准确率评测（ADR 0001）——3B 模型的参数抽取撑不起 95%。
 */
public class OllamaLlmClient implements LlmClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final GatewayProperties.Llm config;

    public OllamaLlmClient(HttpClient http, ObjectMapper mapper, GatewayProperties.Llm config) {
        this.http = http;
        this.mapper = mapper;
        this.config = config;
    }

    @Override
    public LlmTypes.Reply complete(LlmTypes.Request request) {
        try {
            String body = mapper.writeValueAsString(payload(request, false));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(ollamaUrl() + "/api/chat"))
                    .timeout(config.readTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw LlmException.unavailable("本地模型返回 " + response.statusCode(), null);
            }
            return parse(mapper.readTree(response.body()));
        } catch (LlmException known) {
            throw known;
        } catch (HttpTimeoutException timedOut) {
            throw LlmException.timeout("本地模型调用超时", timedOut);
        } catch (Exception failure) {
            throw LlmException.unavailable("本地模型不可用: " + failure.getMessage()
                    + "（Ollama 是否已启动？需要 " + ollamaUrl() + "）", failure);
        }
    }

    @Override
    public LlmTypes.Reply stream(LlmTypes.Request request, Consumer<String> tokenSink) {
        StringBuilder accumulated = new StringBuilder();
        try {
            String body = mapper.writeValueAsString(payload(request, true));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(ollamaUrl() + "/api/chat"))
                    .timeout(config.readTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Stream<String>> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() / 100 != 2) {
                throw LlmException.unavailable("本地模型流式返回 " + response.statusCode(), null);
            }
            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> consume(line, tokenSink, accumulated));
            }
            return LlmTypes.Reply.text(accumulated.toString());
        } catch (LlmException known) {
            throw known;
        } catch (HttpTimeoutException timedOut) {
            throw LlmException.timeout("本地模型流式调用超时", timedOut);
        } catch (Exception failure) {
            throw LlmException.unavailable("本地模型流式调用失败: " + failure.getMessage(), failure);
        }
    }

    @Override
    public String mode() {
        return "local";
    }

    private String ollamaUrl() {
        return config.localBaseUrl();
    }

    private void consume(String line, Consumer<String> sink, StringBuilder accumulated) {
        if (line == null || line.isBlank()) {
            return;
        }
        try {
            JsonNode chunk = mapper.readTree(line);
            JsonNode content = chunk.path("message").path("content");
            if (content.isTextual() && !content.asText().isEmpty()) {
                accumulated.append(content.asText());
                sink.accept(content.asText());
            }
        } catch (Exception unparsable) {
            // 忽略非 JSON 行
        }
    }

    private Map<String, Object> payload(LlmTypes.Request request, boolean stream) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (LlmTypes.Message message : request.messages()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.role());
            item.put("content", message.content() == null ? "" : message.content());
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                List<Map<String, Object>> calls = new ArrayList<>();
                for (LlmTypes.ToolCall call : message.toolCalls()) {
                    calls.add(Map.of("function", Map.of("name", call.name(), "arguments", call.arguments())));
                }
                item.put("tool_calls", calls);
            }
            messages.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.localModel());
        payload.put("messages", messages);
        payload.put("stream", stream);
        // 输出上限（ADR 0044 票 50）：Ollama 侧的等价字段是 options.num_predict；0 = 不限制
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", config.temperature());
        if (config.maxOutputTokens() > 0) {
            options.put("num_predict", config.maxOutputTokens());
        }
        payload.put("options", options);
        if (request.tools() != null && !request.tools().isEmpty()) {
            payload.put("tools", request.tools());
        }
        return payload;
    }

    private LlmTypes.Reply parse(JsonNode root) {
        JsonNode message = root.path("message");
        String content = message.path("content").isTextual() ? message.path("content").asText() : null;
        List<LlmTypes.ToolCall> calls = new ArrayList<>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                JsonNode function = call.path("function");
                Map<String, Object> arguments = new LinkedHashMap<>();
                JsonNode args = function.path("arguments");
                if (args.isObject()) {
                    arguments = mapper.convertValue(args, Map.class);
                }
                calls.add(new LlmTypes.ToolCall("call-" + calls.size(), function.path("name").asText(), arguments));
            }
        }
        return new LlmTypes.Reply(content, calls,
                root.path("prompt_eval_count").asInt(0), root.path("eval_count").asInt(0), root.toString());
    }
}
