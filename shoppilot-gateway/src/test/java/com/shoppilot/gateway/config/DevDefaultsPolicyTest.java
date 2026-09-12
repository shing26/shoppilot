package com.shoppilot.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dev 默认凭证的合法性判据（ADR 0029）：回环上合法但必须自报，非回环上一处都不许留。
 *
 * <p>三条阻断项各自单独测一遍：合取谓词最怕是「摘掉一条没人红」，这里每摘一条都有格子当场判错。
 */
class DevDefaultsPolicyTest {

    private static final String ALL_DEFAULTS_JWT = DevDefaultsPolicy.JWT_SECRET;

    private static DevDefaultsPolicy at(String address, String jwt, String internal, String opsToken, boolean opsEnabled) {
        return new DevDefaultsPolicy(address, jwt, internal, opsToken, opsEnabled);
    }

    private static DevDefaultsPolicy allDefaultsAt(String address) {
        return at(address, ALL_DEFAULTS_JWT, DevDefaultsPolicy.INTERNAL_TOKEN, DevDefaultsPolicy.OPS_TOKEN, true);
    }

    @ParameterizedTest
    @CsvSource({
            "127.0.0.1, true",
            "127.0.0.53, true",
            "localhost, true",
            "::1, true",
            "'[::1]', true",
            "0.0.0.0, false",
            "192.168.1.10, false",
            "'', false",
    })
    @DisplayName("只有回环地址算回环；没填地址算监听所有网卡，fail-closed")
    void loopbackMatrix(String address, boolean expected) {
        assertThat(DevDefaultsPolicy.isLoopback(address)).isEqualTo(expected);
    }

    @Test
    @DisplayName("地址缺失（null）也按非回环处理")
    void missingAddressIsNotLoopback() {
        assertThat(DevDefaultsPolicy.isLoopback(null)).isFalse();
    }

    @Test
    @DisplayName("回环 + 全默认：不阻断，但三处都要自报出来")
    void loopbackKeepsDefaultsButReportsThem() {
        DevDefaultsPolicy policy = allDefaultsAt("127.0.0.1");

        assertThat(policy.startupBlockers()).isEmpty();
        assertThat(policy.mockIdentityAvailable()).isTrue();
        assertThat(policy.devDefaultsInUse()).containsExactly(
                "SHOPPILOT_JWT_SECRET", "SHOPPILOT_INTERNAL_TOKEN", "SHOPPILOT_OPS_TOKEN");
    }

    @Test
    @DisplayName("非回环 + 全默认：三条阻断项齐，且逐条点环境变量名")
    void externalBindWithAllDefaultsIsBlocked() {
        List<String> blockers = allDefaultsAt("0.0.0.0").startupBlockers();

        assertThat(blockers).hasSize(3);
        assertThat(String.join("；", blockers)).contains(
                "SHOPPILOT_JWT_SECRET", "SHOPPILOT_INTERNAL_TOKEN", "SHOPPILOT_OPS_TOKEN");
    }

    @Test
    @DisplayName("非回环：只漏签名密钥时，红的就是那一条，另外两条不许顺带被算进去")
    void onlyMissingJwtSecretIsOnlyThatBlocker() {
        List<String> blockers = at("0.0.0.0", "", "an-overridden-internal-token", "real-ops-token", true)
                .startupBlockers();

        assertThat(blockers).hasSize(1);
        assertThat(blockers.get(0)).contains("SHOPPILOT_JWT_SECRET");
    }

    @Test
    @DisplayName("非回环：只漏内部令牌时同上")
    void onlyMissingInternalTokenIsOnlyThatBlocker() {
        List<String> blockers = at("0.0.0.0", "real-jwt-secret-long-enough-1234567890", "  ", "real-ops-token", true)
                .startupBlockers();

        assertThat(blockers).hasSize(1);
        assertThat(blockers.get(0)).contains("SHOPPILOT_INTERNAL_TOKEN");
    }

    @Test
    @DisplayName("非回环：运维端点显式关闭就不要求令牌；令牌换成非默认值也放行")
    void opsBlockedOnlyWhenEnabledAndDefault() {
        assertThat(at("0.0.0.0", "real-jwt", "real-internal", DevDefaultsPolicy.OPS_TOKEN, false)
                .startupBlockers()).isEmpty();
        assertThat(at("0.0.0.0", "real-jwt", "real-internal", "real-ops-token", true)
                .startupBlockers()).isEmpty();
        assertThat(at("0.0.0.0", "real-jwt", "real-internal", DevDefaultsPolicy.OPS_TOKEN, true)
                .startupBlockers()).hasSize(1);
    }

    @Test
    @DisplayName("非回环 + 三处全覆盖：一条都不阻断，但 mock 身份签发仍然不注册")
    void fullyOverriddenExternalBindStartsWithoutMockIdentity() {
        DevDefaultsPolicy policy = at("0.0.0.0", "real-jwt", "real-internal", "real-ops", true);

        assertThat(policy.startupBlockers()).isEmpty();
        assertThat(policy.devDefaultsInUse()).isEmpty();
        assertThat(policy.mockIdentityAvailable()).isFalse();
    }

    @Test
    @DisplayName("空串与未设置同义：都算仍在吃默认值，不许被当成「已覆盖」")
    void blankCountsAsDefault() {
        assertThat(at("127.0.0.1", "", "", "", true).devDefaultsInUse()).containsExactly(
                "SHOPPILOT_JWT_SECRET", "SHOPPILOT_INTERNAL_TOKEN", "SHOPPILOT_OPS_TOKEN");
    }
}
