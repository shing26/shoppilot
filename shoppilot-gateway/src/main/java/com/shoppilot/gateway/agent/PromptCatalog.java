package com.shoppilot.gateway.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * 提示词版本的唯一入口（ADR 0037）：正文外置为 {@code prompts/agent-system/<版本>.md}，
 * {@code meta.json} 的 {@code current} 字段指认生效版本。历史版本文件只增不删；
 * meta 缺字段、JSON 损坏或指向不存在的版本都在启动期炸掉——静默回退旧版等于让
 * "改一句 prompt"重新变成不可追踪（票 28/21 的 fail-fast 同取向）。
 *
 * <p>风格引擎（ADR 0038）以 {@link #systemPrompt()} 为基座拼接注入段，
 * {@link #version()} 与风格档位共同构成提示词形态的完整归因。
 */
@Component
public class PromptCatalog {

    private static final Logger log = LoggerFactory.getLogger(PromptCatalog.class);
    private static final String BASE = "prompts/agent-system/";

    private final String version;
    private final String systemPrompt;

    /** 生产入口：从 classpath 读 meta.json 与版本正文（默认 agent-system 基座）。 */
    public PromptCatalog() {
        this(BASE);
    }

    /**
     * 任意提示词基座（ADR 0037 的同一套纪律）：情绪分类器等第二份提示词资产各有自己的
     * {@code prompts/<name>/meta.json + <version>.md}，由配置类以具名 Bean 提供。
     */
    public PromptCatalog(String base) {
        this(readClasspath(base + "meta.json"),
                v -> readClasspathOrNull(base + v + ".md"));
    }

    /**
     * 解析与 fail-fast 的唯一实现，测试用字节流直接驱动三种失败形态。
     *
     * @param versionLoader 返回 null 表示该版本没有正文文件
     */
    PromptCatalog(byte[] metaBytes, Function<String, byte[]> versionLoader) {
        String current;
        try {
            current = new ObjectMapper().readTree(metaBytes).path("current").asText("");
        } catch (IOException malformed) {
            throw new IllegalStateException("prompts meta.json 解析失败（prompt 版本必须可解析）", malformed);
        }
        if (current.isBlank()) {
            throw new IllegalStateException("prompts meta.json 缺 current 字段（不指认版本的 prompt 不可追踪）");
        }
        byte[] body = versionLoader.apply(current);
        if (body == null) {
            throw new IllegalStateException("prompt 版本不存在: " + current
                    + "（meta.json 指向的版本必须有正文文件，历史版本只增不删）");
        }
        this.version = current;
        this.systemPrompt = new String(body, StandardCharsets.UTF_8);
        log.info("Prompt 版本 {} 已加载（{} 字符）", current, systemPrompt.length());
    }

    public String version() {
        return version;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    private static byte[] readClasspath(String location) {
        byte[] body = readClasspathOrNull(location);
        if (body == null) {
            throw new IllegalStateException("classpath 缺 " + location + "（prompt 资产必须入库）");
        }
        return body;
    }

    private static byte[] readClasspathOrNull(String location) {
        try (InputStream in = PromptCatalog.class.getClassLoader().getResourceAsStream(location)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException broken) {
            throw new IllegalStateException("读取 " + location + " 失败", broken);
        }
    }
}
