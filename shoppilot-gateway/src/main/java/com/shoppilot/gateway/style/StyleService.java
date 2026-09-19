package com.shoppilot.gateway.style;

import com.shoppilot.gateway.channel.Channel;
import com.shoppilot.gateway.sentiment.Emotion;
import com.shoppilot.tool.Intent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 风格引擎（ADR 0038）：REPLY 前的"基座 + 注入段"提示词拼装，不做回答后处理改写。
 *
 * <p>输入三元组 channel × emotion × intent → 档位（FORMAL / FRIENDLY / CONCISE），
 * 映射表是配置资产（{@code style/profiles.yml}），改档位不改代码；
 * 档位映射为一段注入提示词拼在版本化基座之后——回答正文仍由同一次 LLM 调用产出，不二次调用。
 *
 * <p>启动期校验：注入段超过 200 字（中文 token 的保守代理）即配置错误，拒绝启动——
 * 风格不该贵过回答。
 */
@Component
public class StyleService {

    private static final Logger log = LoggerFactory.getLogger(StyleService.class);
    private static final String PROFILES = "style/profiles.yml";
    /** 注入段上限：中文按 1 字≈1 token 保守折算，200 字封顶（ADR 0038）。 */
    static final int MAX_INJECTION_CHARS = 200;

    public enum Tier {
        FORMAL,
        FRIENDLY,
        CONCISE
    }

    /** 一条映射规则：列出的维度都必须命中；intent 维度保留（当前映射表未分意图，见票面登记）。 */
    private record Rule(List<String> channels, List<String> emotions, List<String> intents, Tier tier) {

        boolean matches(Channel channel, Emotion emotion, Intent intent) {
            return matchesFacet(channels, channel == null ? null : channel.label())
                    && matchesFacet(emotions, emotion == null ? null : emotion.name())
                    && matchesFacet(intents, intent == null ? null : intent.name());
        }

        private static boolean matchesFacet(List<String> expected, String actual) {
            return expected == null || expected.isEmpty()
                    || (actual != null && expected.stream().anyMatch(value -> value.equalsIgnoreCase(actual)));
        }
    }

    private final List<Rule> rules;
    private final Tier defaultTier;
    private final Map<Tier, String> injections;
    private final Map<Tier, String> fallbackPrefixes;

    public StyleService() {
        this(load());
    }

    /** 解析与 fail-fast 的唯一实现；测试用内存里的 profiles 驱动各类坏配置。 */
    StyleService(Map<String, Object> profiles) {
        this.defaultTier = tierOf(profiles.get("default"), "default");
        this.rules = parseRules(profiles.get("rules"));
        this.injections = parseInjections(profiles.get("injections"));
        this.fallbackPrefixes = parsePrefixes(profiles.get("fallback_prefixes"));
        log.info("风格档位表已加载：{} 条规则、默认 {}、注入段 {} 字（上限 {}）",
                rules.size(), defaultTier, injections.get(defaultTier).length(), MAX_INJECTION_CHARS);
    }

    /** 三元组 → 档位。规则按序匹配，首条命中生效；都不命中用 default。 */
    public Tier tierFor(Channel channel, Emotion emotion, Intent intent) {
        for (Rule rule : rules) {
            if (rule.matches(channel, emotion, intent)) {
                return rule.tier();
            }
        }
        return defaultTier;
    }

    /** 基座 + 注入段：同一次 LLM 调用的 system 消息，不是第二次调用。 */
    public String assemble(String baseSystemPrompt, Tier tier) {
        return baseSystemPrompt + "\n" + injections.get(tier);
    }

    public String injection(Tier tier) {
        return injections.get(tier);
    }

    /** 降级话术的档位联动（ADR 0038）：改的是话术选择，不是话术生成。 */
    public String fallbackPrefix(Tier tier) {
        return fallbackPrefixes.getOrDefault(tier, "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        try (InputStream in = new ClassPathResource(PROFILES).getInputStream()) {
            Map<String, Object> profiles = new Yaml().load(in);
            if (profiles == null) {
                throw new IllegalStateException(PROFILES + " 为空（档位表是风格引擎的必需资产）");
            }
            return profiles;
        } catch (IOException unreadable) {
            throw new IllegalStateException(PROFILES + " 读取失败（档位表是风格引擎的必需资产）", unreadable);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Rule> parseRules(Object rawRules) {
        if (!(rawRules instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException(PROFILES + " 缺 rules（没有映射表的风格引擎只能瞎猜）");
        }
        List<Rule> parsed = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> rule = (Map<String, Object>) item;
            parsed.add(new Rule(toStringList(rule.get("channel")), toStringList(rule.get("emotion")),
                    toStringList(rule.get("intent")), tierOf(rule.get("tier"), "rules[].tier")));
        }
        return parsed;
    }

    @SuppressWarnings("unchecked")
    private Map<Tier, String> parseInjections(Object rawInjections) {
        if (!(rawInjections instanceof Map<?, ?> map)) {
            throw new IllegalStateException(PROFILES + " 缺 injections");
        }
        Map<Tier, String> parsed = new EnumMap<>(Tier.class);
        for (Tier tier : Tier.values()) {
            Object value = ((Map<String, Object>) map).get(tier.name());
            if (value == null || String.valueOf(value).isBlank()) {
                throw new IllegalStateException(PROFILES + " 缺 " + tier + " 的注入段");
            }
            String injection = String.valueOf(value);
            if (injection.length() > MAX_INJECTION_CHARS) {
                throw new IllegalStateException(PROFILES + " 的 " + tier + " 注入段 " + injection.length()
                        + " 字，超过 " + MAX_INJECTION_CHARS + " 字上限——风格不该贵过回答");
            }
            if (injection.chars().anyMatch(Character::isDigit)) {
                throw new IllegalStateException(PROFILES + " 的 " + tier
                        + " 注入段含数字：注入只允许约束形态，不允许携带业务事实");
            }
            parsed.put(tier, injection);
        }
        return parsed;
    }

    @SuppressWarnings("unchecked")
    private Map<Tier, String> parsePrefixes(Object rawPrefixes) {
        Map<Tier, String> parsed = new EnumMap<>(Tier.class);
        if (rawPrefixes == null) {
            return parsed;
        }
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) rawPrefixes).entrySet()) {
            parsed.put(Tier.valueOf(entry.getKey().toUpperCase(Locale.ROOT)), String.valueOf(entry.getValue()));
        }
        return parsed;
    }

    private static Tier tierOf(Object raw, String where) {
        if (raw == null) {
            throw new IllegalStateException(PROFILES + " 的 " + where + " 缺 tier");
        }
        try {
            return Tier.valueOf(String.valueOf(raw).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalStateException(PROFILES + " 的 " + where + " 出现未知档位 " + raw
                    + "（只允许 FORMAL / FRIENDLY / CONCISE）");
        }
    }

    private static List<String> toStringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(raw));
    }
}
