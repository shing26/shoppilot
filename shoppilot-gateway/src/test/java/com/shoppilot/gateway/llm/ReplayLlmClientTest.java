package com.shoppilot.gateway.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * round48 票 201 / ADR 0083：回放必须"同请求同结果、缺卡即红、改卡即红"。
 *
 * <p>这里用假模型录制而不是真模型：本用例钉的是录放机制本身（key、落盘、fail-closed、
 * 变异对照）。真实模型 cassette 由 {@code scripts/record_llm_cassettes.py} 录到
 * {@code eval/llm-cassettes/}，再由 {@code scripts/verify_llm_replay.py} 在 CI 里 0 token 复核。
 */
class ReplayLlmClientTest {

    private static final String MODEL = "test-model";
    private static final String PROMPT_VERSION = "v-test";
    private static final int MAX_TOKENS = 1024;
    private static final double TEMPERATURE = 0.2;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("录制 → 回放：工具选择、参数、正文、token 数逐字段一致")
    void recordThenReplayIsByteIdentical(@TempDir Path dir) {
        LlmTypes.Request request = request("帮我退款，订单 90001");
        LlmTypes.Reply recorded = new LlmTypes.Reply(null,
                List.of(new LlmTypes.ToolCall("call-1", "applyRefund", Map.of("orderNo", "90001"))),
                120, 18, null);

        record(dir, request, recorded);
        ReplayLlmClient replay = replay(dir);
        LlmTypes.Reply replayed = replay.complete(request);

        assertThat(replayed.toolCalls()).hasSize(1);
        assertThat(replayed.toolCalls().get(0).name()).isEqualTo("applyRefund");
        assertThat(replayed.toolCalls().get(0).arguments()).containsEntry("orderNo", "90001");
        assertThat(replayed.promptTokens()).isEqualTo(120);
        assertThat(replayed.completionTokens()).isEqualTo(18);
    }

    @Test
    @DisplayName("流式回放：sink 拼接结果与录制正文逐字相同")
    void streamReplaysTheSameText(@TempDir Path dir) {
        LlmTypes.Request request = request("七天无理由怎么算");
        String answer = "签收后七天内可无理由退货，商品需保持完好。";
        record(dir, request, new LlmTypes.Reply(answer, List.of(), 80, 20, null));

        List<String> chunks = new ArrayList<>();
        LlmTypes.Reply replayed = replay(dir).stream(request, chunks::add);

        assertThat(replayed.content()).isEqualTo(answer);
        assertThat(String.join("", chunks)).isEqualTo(answer);
        assertThat(chunks.size()).as("流式契约要真的分段回调，而不是一次性塞完").isGreaterThan(1);
    }

    @Test
    @DisplayName("入仓的真实模型卡：同一请求回放出同样的工具选择、正文与 token 数")
    void committedRealCassetteReplaysIdentical() throws Exception {
        Path root = Path.of("..", "eval", "llm-cassettes").normalize();
        List<Path> cassettes = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("index.json"))
                    .filter(path -> !path.getFileName().toString().equals("baseline.json"))
                    .filter(path -> path.getParent().getParent().getParent().equals(root))
                    .forEach(cassettes::add);
        }
        assertThat(cassettes).as("至少要有一张真实模型 cassette（ADR 0083 验收项）").isNotEmpty();

        for (Path file : cassettes) {
            LlmCassette cassette = mapper.readValue(file.toFile(), LlmCassette.class);
            LlmTypes.Request request = new LlmTypes.Request(cassette.request().messages(),
                    cassette.request().tools(), cassette.request().temperature());
            ReplayLlmClient replay = new ReplayLlmClient(mapper, root, cassette.model(),
                    cassette.promptVersion(), cassette.request().maxTokens(), cassette.request().temperature());

            LlmTypes.Reply complete = replay.complete(request);
            assertThat(complete.toolCalls()).as(file.toString()).isEqualTo(cassette.response().toolCalls());
            assertThat(complete.content()).as(file.toString()).isEqualTo(cassette.response().content());
            assertThat(complete.promptTokens()).isEqualTo(cassette.response().promptTokens());
            assertThat(complete.completionTokens()).isEqualTo(cassette.response().completionTokens());

            List<String> chunks = new ArrayList<>();
            replay.stream(request, chunks::add);
            assertThat(String.join("", chunks)).isEqualTo(cassette.response().content() == null
                    ? "" : cassette.response().content());
        }
    }

    @Test
    @DisplayName("缺 cassette：fail-closed，错误指到具体 key 与请求摘要")
    void missingCassetteFailsClosed(@TempDir Path dir) {
        LlmTypes.Request request = request("查一下订单 90002 到哪了");
        String key = CassetteKey.of(mapper, MODEL, PROMPT_VERSION, request, MAX_TOKENS, TEMPERATURE);

        assertThatThrownBy(() -> replay(dir).complete(request))
                .isInstanceOfSatisfying(LlmException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(LlmException.Kind.UNAVAILABLE);
                    assertThat(failure.getMessage())
                            .contains("回放缺 cassette")
                            .contains(key)
                            .contains("user:查一下订单 90002 到哪了");
                });
    }

    @Test
    @DisplayName("请求变一个字节就不再命中：key 对 messages 敏感")
    void keyChangesWithMessages(@TempDir Path dir) {
        LlmTypes.Request recordedRequest = request("订单 90001 到哪了");
        record(dir, recordedRequest, new LlmTypes.Reply("已发货。", List.of(), 10, 5, null));

        assertThatThrownBy(() -> replay(dir).complete(request("订单 90001 到哪了？")))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("回放缺 cassette");
    }

    @Test
    @DisplayName("变异对照：改掉 cassette 的 key 字段 → 回放立即红，不是恒绿")
    void mutatedCassetteKeyIsRejected(@TempDir Path dir) throws Exception {
        LlmTypes.Request request = request("帮我退款，订单 90001");
        record(dir, request, new LlmTypes.Reply("好的，已提交退款申请。", List.of(), 30, 9, null));
        Path cassette = cassettePath(dir, request);

        ObjectNode mutated = (ObjectNode) mapper.readTree(cassette.toFile());
        mutated.put("key", "0".repeat(64));
        Files.writeString(cassette, mapper.writeValueAsString(mutated));

        assertThatThrownBy(() -> replay(dir).complete(request))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("cassette key 与请求不一致");
    }

    private void record(Path dir, LlmTypes.Request request, LlmTypes.Reply reply) {
        LlmClient fake = new LlmClient() {
            @Override
            public LlmTypes.Reply complete(LlmTypes.Request ignored) {
                return reply;
            }

            @Override
            public LlmTypes.Reply stream(LlmTypes.Request ignored, Consumer<String> tokenSink) {
                if (reply.content() != null) {
                    tokenSink.accept(reply.content());
                }
                return reply;
            }

            @Override
            public String mode() {
                return "test";
            }
        };
        new RecordingLlmClient(fake, mapper, dir, MODEL, PROMPT_VERSION, "test", MAX_TOKENS, TEMPERATURE, false)
                .complete(request);
    }

    private ReplayLlmClient replay(Path dir) {
        return new ReplayLlmClient(mapper, dir, MODEL, PROMPT_VERSION, MAX_TOKENS, TEMPERATURE);
    }

    private Path cassettePath(Path dir, LlmTypes.Request request) {
        String key = CassetteKey.of(mapper, MODEL, PROMPT_VERSION, request, MAX_TOKENS, TEMPERATURE);
        return dir.resolve(CassetteKey.modelSegment(MODEL)).resolve(PROMPT_VERSION).resolve(key + ".json");
    }

    private LlmTypes.Request request(String userText) {
        return new LlmTypes.Request(List.of(
                LlmTypes.Message.system("你是客服助手。"),
                LlmTypes.Message.user(userText)), List.of(), 0.0);
    }
}
