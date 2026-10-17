package com.shoppilot.gateway.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 版本化的门（ADR 0037 / 票 35）：生产资源加载、11 条规则结构钉住、
 * 三种坏 meta 形态（缺 current、指向缺失版本、JSON 损坏）都必须在启动期炸掉。
 */
class PromptCatalogTest {

    @Test
    @DisplayName("生产资源：加载 v1.1.0，11 条规则结构不变")
    void loadsProductionResources() {
        PromptCatalog catalog = new PromptCatalog();
        assertEquals("v1.1.0", catalog.version());
        var rulePattern = Pattern.compile("(?m)^\\d+\\.");
        assertEquals(11, rulePattern.matcher(catalog.systemPrompt()).results().count(),
                "11 条规则一条都不能少：多一条或少一条都是行为语义变更，必须走新版本文件");
        assertTrue(catalog.systemPrompt().startsWith("你是商家的在线客服助手。请遵守：\n"));
        assertTrue(catalog.systemPrompt().endsWith("11. 用简体中文，口语、简洁，不超过 200 字。\n"));
    }

    @Test
    @DisplayName("round33：底座行业无关，且写明「没有可用工具时办理类诉求照实说明并转人」（ADR 0067）")
    void basePromptIsIndustryNeutralWithConsultOnlyFallback() {
        String prompt = new PromptCatalog().systemPrompt();

        assertFalse(prompt.contains("电商店铺"), "酒店租户拿到电商人设就是跨行业串味的源头："
                + "底座必须是行业无关的客服人设，行业差异由语料与工具表决定，不写死在提示词里");
        assertTrue(prompt.contains("没有任何可用工具"), "纯咨询行业（酒店）没有办理工具，"
                + "底座必须给模型一条明确的兜底：办不了就说明并转人，不能凭空承诺已经办理");
    }

    @Test
    @DisplayName("正常路径：meta 指认的版本被解析，正文按版本取回")
    void resolvesCurrentVersion() {
        AtomicInteger loaderCalls = new AtomicInteger();
        PromptCatalog catalog = new PromptCatalog(
                "{\"current\": \"v2.0.0\"}".getBytes(StandardCharsets.UTF_8),
                version -> {
                    loaderCalls.incrementAndGet();
                    assertEquals("v2.0.0", version);
                    return "版本二的正文".getBytes(StandardCharsets.UTF_8);
                });
        assertEquals("v2.0.0", catalog.version());
        assertEquals("版本二的正文", catalog.systemPrompt());
        assertEquals(1, loaderCalls.get(), "只按 current 指认的版本取一次正文");
    }

    @Test
    @DisplayName("fail-fast：meta 缺 current 字段 → 启动失败")
    void rejectsMissingCurrent() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new PromptCatalog("{}".getBytes(StandardCharsets.UTF_8), v -> null));
        assertTrue(failure.getMessage().contains("current"));
    }

    @Test
    @DisplayName("fail-fast：current 指向不存在的版本 → 启动失败且指明版本号")
    void rejectsMissingVersionFile() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new PromptCatalog("{\"current\": \"v9.9.9\"}".getBytes(StandardCharsets.UTF_8),
                        Map.<String, byte[]>of()::get));
        assertTrue(failure.getMessage().contains("v9.9.9"), failure.getMessage());
    }

    @Test
    @DisplayName("fail-fast：meta.json 损坏 → 启动失败")
    void rejectsMalformedMeta() {
        assertThrows(IllegalStateException.class,
                () -> new PromptCatalog("{not-json".getBytes(StandardCharsets.UTF_8), v -> null));
    }
}
