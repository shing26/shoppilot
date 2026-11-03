package com.shoppilot.gateway.llm;

import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.PromptCatalog;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.tool.ToolContracts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实模型 cassette 录制器（round48 票 201 / ADR 0083）。
 *
 * <p>类名刻意不以 {@code Test} 结尾：普通 {@code mvnw verify} 不会跑它——录制要花钱/要网络，
 * 不能混进 CI。由 {@code scripts/record_llm_cassettes.py} 用
 * {@code -Dtest=LlmCassetteRecorder} 显式拉起，并设置 {@code SHOPPILOT_RECORD_CASSETTES=1}。
 *
 * <p>录的是**单次 LLM 往返**（system prompt + 买家问题 + 工具 schema），不是整轮对话快照。
 * 因此回放门禁守的是：提示词版本、请求形状、工具 schema、模型当时的工具选择与答案；
 * 不覆盖检索、T0/T1、工具执行——那些由 JVM 用例与活体评测各自守。
 */
class LlmCassetteRecorder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与 {@link RecordingLlmClient} 同一条 LF 约定：入仓的索引不随录制机器换行尾。 */
    private static final PrettyPrinter PRINTER = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"));

    private record Fixture(String query, String expectedTool) {
    }

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("七天无理由退货从哪天开始算？", null),
            new Fixture("订单 90001 现在是什么状态？", "queryOrderDetail"),
            new Fixture("订单 90002 到哪了？", "queryLogistics"),
            new Fixture("订单 90001 帮我申请退款", "applyRefund"),
            new Fixture("订单 90003 我想改一下收货地址", "modifyDeliveryAddress"),
            new Fixture("你们店主要卖什么？", null));

    @Test
    void recordRealModelCassettes() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("SHOPPILOT_RECORD_CASSETTES")),
                "录制需要显式 SHOPPILOT_RECORD_CASSETTES=1；普通 verify 不跑");

        Map<String, String> env = loadEnv();
        String apiKey = firstNonBlank(System.getenv("SHOPPILOT_LLM_API_KEY"), env.get("SHOPPILOT_LLM_API_KEY"));
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "缺 SHOPPILOT_LLM_API_KEY，无法录制真实模型");

        String baseUrl = firstNonBlank(System.getenv("SHOPPILOT_LLM_BASE_URL"), env.get("SHOPPILOT_LLM_BASE_URL"),
                "https://dashscope.aliyuncs.com/compatible-mode/v1");
        String model = firstNonBlank(System.getenv("SHOPPILOT_LLM_MODEL"), env.get("SHOPPILOT_LLM_MODEL"), "qwen-plus");

        GatewayProperties.Llm config = new GatewayProperties.Llm("dev", baseUrl, apiKey, model, 0.2d,
                Duration.ofSeconds(3), Duration.ofSeconds(60), 0L,
                "http://127.0.0.1:11434", "qwen2.5:3b",
                Duration.ofMillis(300), Duration.ofMillis(500), 1024, "", "", "");
        PromptCatalog catalog = new PromptCatalog();
        Path root = cassetteRoot();

        LlmClient real = new OpenAiCompatibleLlmClient(HttpClient.newHttpClient(), MAPPER, config);
        RecordingLlmClient recorder = new RecordingLlmClient(real, MAPPER, root, model,
                catalog.version(), "dev", config.maxOutputTokens(), config.temperature(), true);

        List<Map<String, Object>> entries = new ArrayList<>();
        for (Fixture fixture : FIXTURES) {
            List<Map<String, Object>> tools = fixture.expectedTool() == null
                    ? List.of() : ToolContracts.actionDescriptors();
            LlmTypes.Request request = new LlmTypes.Request(List.of(
                    LlmTypes.Message.system(catalog.systemPrompt()),
                    LlmTypes.Message.user(fixture.query())), tools, 0.0);
            LlmTypes.Reply reply = recorder.complete(request);
            String key = CassetteKey.of(MAPPER, model, catalog.version(), request,
                    config.maxOutputTokens(), config.temperature());

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", key);
            entry.put("query", fixture.query());
            entry.put("expect", Map.of("tool", fixture.expectedTool() == null ? List.of() : fixture.expectedTool()));
            entry.put("cassette", modelSegment(model) + "/" + catalog.version() + "/" + key + ".json");
            entry.put("observedTool", reply.toolCalls() == null || reply.toolCalls().isEmpty()
                    ? null : reply.toolCalls().get(0).name());
            entries.add(entry);
        }

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("generatedAt", Instant.now().toString());
        index.put("model", model);
        index.put("promptVersion", catalog.version());
        index.put("source", "dev");
        index.put("entries", entries);
        Files.createDirectories(root);
        MAPPER.writer().with(PRINTER).writeValue(root.resolve("index.json").toFile(), index);
    }

    private static String modelSegment(String model) {
        return CassetteKey.modelSegment(model);
    }

    private static Path cassetteRoot() {
        Path moduleRelative = Path.of("..", "eval", "llm-cassettes").normalize();
        if (Files.isDirectory(Path.of("..", "eval"))) {
            return moduleRelative;
        }
        return Path.of("eval", "llm-cassettes");
    }

    private static Map<String, String> loadEnv() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Path candidate : List.of(Path.of("..", ".env"), Path.of(".env"))) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(candidate, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                        continue;
                    }
                    int at = trimmed.indexOf('=');
                    String key = trimmed.substring(0, at).trim();
                    String value = trimmed.substring(at + 1).trim();
                    if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                            || value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    values.putIfAbsent(key, value);
                }
            } catch (Exception unreadable) {
                // .env 读不了就退化到真实环境变量；缺 key 时下面的 assume 会显式跳过。
            }
        }
        return values;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
