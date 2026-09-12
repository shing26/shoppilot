package com.shoppilot.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运维凭证判定：开关关闭与令牌不匹配必须是两个值。
 *
 * <p>以前这两件事共用一个布尔，调用点只能写一句「invalid or disabled」，
 * 于是同一个失败在不同端点还各长成一个文案（票 21 的起因）。
 */
class OpsAccessTest {

    @Test
    @DisplayName("开关关着时先报开关，别让人以为是自己令牌填错")
    void disabledWinsOverMismatch() {
        assertThat(OpsAccess.evaluate(false, "right-token", "right-token"))
                .isEqualTo(OpsAccess.DISABLED);
    }

    @Test
    @DisplayName("开关开着：缺令牌与填错都是令牌不匹配")
    void tokenMismatchWhenEnabled() {
        assertThat(OpsAccess.evaluate(true, "right-token", null)).isEqualTo(OpsAccess.TOKEN_MISMATCH);
        assertThat(OpsAccess.evaluate(true, "right-token", "wrong")).isEqualTo(OpsAccess.TOKEN_MISMATCH);
    }

    @Test
    @DisplayName("令牌相等才放行")
    void allowedOnlyOnExactMatch() {
        assertThat(OpsAccess.evaluate(true, "right-token", "right-token")).isEqualTo(OpsAccess.ALLOWED);
        assertThat(OpsAccess.ALLOWED.allowed()).isTrue();
        assertThat(OpsAccess.DISABLED.allowed()).isFalse();
    }

    @Test
    @DisplayName("三个值各有自己的 code 与话术，互不重复")
    void codesAreDistinct() {
        assertThat(OpsAccess.values()).extracting(OpsAccess::code).doesNotHaveDuplicates();
        assertThat(OpsAccess.values()).allSatisfy(access -> {
            assertThat(access.code()).isNotBlank();
            assertThat(access.message()).isNotBlank();
        });
    }

    @Test
    @DisplayName("期望令牌没配（空值不该等于任何输入）时判不匹配，而不是放行")
    void unsetExpectedTokenDenies() {
        assertThat(OpsAccess.evaluate(true, null, null)).isEqualTo(OpsAccess.TOKEN_MISMATCH);
    }
}
