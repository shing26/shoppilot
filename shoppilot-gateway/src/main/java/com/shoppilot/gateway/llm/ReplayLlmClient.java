package com.shoppilot.gateway.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * 回放模式（ADR 0083）：只读 cassette，**缺卡即失败**，不回退真实模型、不回退 Mock。
 *
 * <p>"fail-closed" 是这张票的核心判据：如果缺卡时悄悄降级，门禁会绿着通过——
 * 那正是仓库里反复拒绝的那类"绿了但没证明"。
 */
public class ReplayLlmClient implements LlmClient {

    private final ObjectMapper mapper;
    private final Path root;
    private final String model;
    private final String promptVersion;
    private final int maxTokens;
    private final double fallbackTemperature;

    public ReplayLlmClient(ObjectMapper mapper, Path root, String model, String promptVersion,
                           int maxTokens, double fallbackTemperature) {
        this.mapper = mapper;
        this.root = root;
        this.model = model;
        this.promptVersion = promptVersion == null ? "" : promptVersion;
        this.maxTokens = maxTokens;
        this.fallbackTemperature = fallbackTemperature;
    }

    @Override
    public LlmTypes.Reply complete(LlmTypes.Request request) {
        return load(request).response().toReply();
    }

    @Override
    public LlmTypes.Reply stream(LlmTypes.Request request, Consumer<String> tokenSink) {
        LlmTypes.Reply reply = load(request).response().toReply();
        String content = reply.content();
        if (content != null && !content.isEmpty()) {
            // 回放不模拟真实网络分片，但要保留"流式回调确实被调用"这一契约；
            // 按固定大小切块，调用方收到的拼接结果与录制正文逐字相同。
            for (int from = 0; from < content.length(); from += 16) {
                tokenSink.accept(content.substring(from, Math.min(content.length(), from + 16)));
            }
        }
        return reply;
    }

    @Override
    public String mode() {
        return "replay";
    }

    /** 录下来的 token 数是录制时供应商回报的真值，不是本次运行重新估的。 */
    @Override
    public String tokenSource() {
        return "provider";
    }

    LlmCassette load(LlmTypes.Request request) {
        String key = CassetteKey.of(mapper, model, promptVersion, request, maxTokens, fallbackTemperature);
        Path file = path(key);
        if (!Files.isRegularFile(file)) {
            throw LlmException.unavailable(
                    "回放缺 cassette：" + file + "（key=" + key + "，model=" + model
                            + "，promptVersion=" + promptVersion + "，请求摘要=" + summary(request) + "）",
                    null);
        }
        LlmCassette cassette;
        try {
            cassette = mapper.readValue(file.toFile(), LlmCassette.class);
        } catch (IOException malformed) {
            throw LlmException.unavailable("cassette 无法解析：" + file + "（" + malformed.getMessage() + "）", malformed);
        }
        if (cassette.key() == null || !cassette.key().equals(key)) {
            throw LlmException.unavailable(
                    "cassette key 与请求不一致：" + file + "（文件 key=" + cassette.key() + "，请求 key=" + key + "）", null);
        }
        if (!model.equals(cassette.model()) || !promptVersion.equals(cassette.promptVersion())) {
            throw LlmException.unavailable(
                    "cassette 归因不一致：" + file + "（文件 model=" + cassette.model()
                            + "/promptVersion=" + cassette.promptVersion() + "）", null);
        }
        if (cassette.response() == null) {
            throw LlmException.unavailable("cassette 缺 response：" + file, null);
        }
        return cassette;
    }

    Path path(String key) {
        return root.resolve(CassetteKey.modelSegment(model)).resolve(promptVersion).resolve(key + ".json");
    }

    private static String summary(LlmTypes.Request request) {
        List<LlmTypes.Message> messages = request.messages();
        if (messages == null || messages.isEmpty()) {
            return "<empty>";
        }
        LlmTypes.Message last = messages.get(messages.size() - 1);
        String text = last.content() == null ? "" : last.content().replaceAll("\\s+", " ");
        return last.role() + ":" + (text.length() <= 80 ? text : text.substring(0, 80) + "…");
    }
}
