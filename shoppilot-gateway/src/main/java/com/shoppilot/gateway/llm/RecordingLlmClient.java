package com.shoppilot.gateway.llm;

import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * 录制装饰器（ADR 0083）：包住真实客户端，把每次往返写成 cassette。
 *
 * <p>只在装配层替换实现，状态机与提示词路径零改动。写盘失败只记 warn，不让"录制坏了"
 * 变成"对话坏了"——录制本来就不是生产路径；但缺失的卡会在回放时 fail-closed，不会静默通过。
 */
public class RecordingLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(RecordingLlmClient.class);

    /**
     * 行尾钉死 LF：Jackson 的默认 pretty printer 用 {@code System.lineSeparator()}，
     * 同一份卡在 Windows 录制、在 Linux 复跑会整文件翻成 diff 噪声。卡是入仓的回归基准，
     * 不该随录制机器变化。
     */
    private static final PrettyPrinter PRINTER = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"));

    private final LlmClient delegate;
    private final ObjectMapper mapper;
    private final Path root;
    private final String model;
    private final String promptVersion;
    private final String source;
    private final int maxTokens;
    private final double fallbackTemperature;
    private final boolean overwrite;

    public RecordingLlmClient(LlmClient delegate, ObjectMapper mapper, Path root, String model,
                              String promptVersion, String source, int maxTokens,
                              double fallbackTemperature, boolean overwrite) {
        this.delegate = delegate;
        this.mapper = mapper;
        this.root = root;
        this.model = model;
        this.promptVersion = promptVersion == null ? "" : promptVersion;
        this.source = source;
        this.maxTokens = maxTokens;
        this.fallbackTemperature = fallbackTemperature;
        this.overwrite = overwrite;
    }

    @Override
    public LlmTypes.Reply complete(LlmTypes.Request request) {
        LlmTypes.Reply reply = delegate.complete(request);
        record(request, reply);
        return reply;
    }

    @Override
    public LlmTypes.Reply stream(LlmTypes.Request request, Consumer<String> tokenSink) {
        LlmTypes.Reply reply = delegate.stream(request, tokenSink);
        record(request, reply);
        return reply;
    }

    @Override
    public String mode() {
        return delegate.mode();
    }

    @Override
    public String tokenSource() {
        return delegate.tokenSource();
    }

    void record(LlmTypes.Request request, LlmTypes.Reply reply) {
        String key = CassetteKey.of(mapper, model, promptVersion, request, maxTokens, fallbackTemperature);
        Path target = root.resolve(CassetteKey.modelSegment(model)).resolve(promptVersion).resolve(key + ".json");
        if (!overwrite && Files.exists(target)) {
            return;
        }
        LlmCassette cassette = new LlmCassette(key, model, promptVersion, Instant.now().toString(), source,
                new LlmCassette.Request(
                        request.messages() == null ? List.of() : request.messages(),
                        request.tools() == null ? List.of() : request.tools(),
                        CassetteKey.effectiveTemperature(request, fallbackTemperature),
                        maxTokens),
                new LlmCassette.Response(reply.content(),
                        reply.toolCalls() == null ? List.of() : reply.toolCalls(),
                        reply.promptTokens(), reply.completionTokens()));
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            mapper.writer().with(PRINTER).writeValue(tmp.toFile(), cassette);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception failure) {
            log.warn("cassette 写入失败 {}: {}", target, failure.getMessage());
        }
    }
}
