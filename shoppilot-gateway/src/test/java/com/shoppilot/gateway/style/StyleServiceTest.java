package com.shoppilot.gateway.style;

import com.shoppilot.gateway.channel.Channel;
import com.shoppilot.gateway.sentiment.Emotion;
import com.shoppilot.tool.Intent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风格引擎的 0 token 单测（ADR 0038 / 风格票）：档位矩阵与 part7 风格用例逐条对齐、
 * 注入段只允许约束形态（无数字/无业务事实）、四类坏配置启动期拒绝。
 */
class StyleServiceTest {

    private final StyleService service = new StyleService();

    @Test
    @DisplayName("档位矩阵：part7 六条风格用例的三元组逐条对齐")
    void tierMatrixMatchesStyleCases() {
        assertAll(
                () -> assertEquals(StyleService.Tier.FORMAL,
                        service.tierFor(Channel.WEB, Emotion.ANGRY, Intent.ACTION_REFUND), "STY-WEB-ANG-01"),
                () -> assertEquals(StyleService.Tier.CONCISE,
                        service.tierFor(Channel.APP, Emotion.CALM, Intent.POLICY_RETURN), "STY-APP-CAL-01"),
                () -> assertEquals(StyleService.Tier.FORMAL,
                        service.tierFor(Channel.EMAIL, Emotion.CALM, Intent.POLICY_SHIPPING), "STY-EMAIL-CAL-01"),
                () -> assertEquals(StyleService.Tier.FRIENDLY,
                        service.tierFor(Channel.MINIAPP, Emotion.DISSATISFIED, Intent.ACTION_LOGISTICS), "STY-MINI-DIS-01"),
                () -> assertEquals(StyleService.Tier.FORMAL,
                        service.tierFor(Channel.APP, Emotion.ANGRY, Intent.ACTION_ORDER), "STY-APP-ANG-01"),
                () -> assertEquals(StyleService.Tier.FORMAL,
                        service.tierFor(Channel.EMAIL, Emotion.ANGRY, Intent.ACTION_REFUND), "STY-EMAIL-ANG-01"));
    }

    @Test
    @DisplayName("UNCERTAIN 一律回落 FORMAL，不猜不讨好（ADR 0038 明确否决随机档位）")
    void uncertainFallsBackToFormal() {
        assertEquals(StyleService.Tier.FORMAL, service.tierFor(Channel.APP, Emotion.UNCERTAIN, Intent.POLICY_RETURN));
        assertEquals(StyleService.Tier.FORMAL, service.tierFor(Channel.MINIAPP, Emotion.UNCERTAIN, null));
        assertEquals(StyleService.Tier.FORMAL, service.tierFor(Channel.WEB, Emotion.UNCERTAIN, null));
    }

    @Test
    @DisplayName("URGENT 与 ANGRY 同档：克制安抚，不给活泼语气")
    void urgentIsFormalToo() {
        assertEquals(StyleService.Tier.FORMAL, service.tierFor(Channel.MINIAPP, Emotion.URGENT, Intent.ACTION_ADDRESS));
    }

    @Test
    @DisplayName("注入段只允许约束形态：无数字、长度在上限内；拼装 = 基座 + 注入")
    void injectionsCarryNoFacts() {
        for (StyleService.Tier tier : StyleService.Tier.values()) {
            String injection = service.injection(tier);
            assertTrue(injection.length() <= StyleService.MAX_INJECTION_CHARS, tier + " 注入段长度");
            assertFalse(injection.chars().anyMatch(Character::isDigit),
                    tier + " 注入段不许含数字（业务事实只能来自检索或工具结果）：" + injection);
        }
        String assembled = service.assemble("基座正文", StyleService.Tier.CONCISE);
        assertTrue(assembled.startsWith("基座正文"));
        assertTrue(assembled.endsWith(service.injection(StyleService.Tier.CONCISE)));
    }

    @Test
    @DisplayName("降级话术的档位联动来自配置资产：FRIENDLY 有安抚短句，其余为空")
    void fallbackPrefixComesFromProfiles() {
        assertFalse(service.fallbackPrefix(StyleService.Tier.FRIENDLY).isBlank());
        assertEquals("", service.fallbackPrefix(StyleService.Tier.FORMAL));
        assertEquals("", service.fallbackPrefix(StyleService.Tier.CONCISE));
    }

    @Test
    @DisplayName("坏配置启动期拒绝：注入段超限 / 含数字 / 未知档位 / 缺 rules")
    void rejectsBadProfiles() {
        Map<String, Object> injections = Map.of("FORMAL", "正式", "FRIENDLY", "亲切", "CONCISE", "简洁");
        assertThrows(IllegalStateException.class, () -> new StyleService(Map.of(
                "default", "FORMAL", "rules", List.of(Map.of("channel", List.of("app"), "tier", "CONCISE")),
                "injections", Map.of("FORMAL", "正式", "FRIENDLY", "亲切", "CONCISE", "简".repeat(300)))),
                "注入段超 200 字必须拒绝");
        assertThrows(IllegalStateException.class, () -> new StyleService(Map.of(
                "default", "FORMAL", "rules", List.of(Map.of("channel", List.of("app"), "tier", "CONCISE")),
                "injections", Map.of("FORMAL", "正式", "FRIENDLY", "亲切", "CONCISE", "7天内免费退"))),
                "注入段含数字必须拒绝（业务事实型）");
        assertThrows(IllegalStateException.class, () -> new StyleService(Map.of(
                "default", "FORMAL", "rules", List.of(Map.of("channel", List.of("app"), "tier", "FANCY")),
                "injections", injections)), "未知档位必须拒绝");
        assertThrows(IllegalStateException.class, () -> new StyleService(Map.of(
                "default", "FORMAL", "injections", injections)), "缺 rules 必须拒绝");
    }
}
