package com.shoppilot.gateway.config;

import com.shoppilot.gateway.agent.PromptCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 第二份提示词资产的装配（ADR 0037 的同一套纪律）：情绪分类器的提示词也是版本文件，
 * 与 agent-system 基座各自独立演进、各自 fail-fast。具名 Bean 让按类型注入时以参数名消歧。
 */
@Configuration
public class PromptAssetsConfig {

    /** 情绪分类器提示词基座：prompts/sentiment-classifier/{meta.json,<version>.md}。 */
    @Bean
    public PromptCatalog sentimentClassifierCatalog() {
        return new PromptCatalog("prompts/sentiment-classifier/");
    }
}
