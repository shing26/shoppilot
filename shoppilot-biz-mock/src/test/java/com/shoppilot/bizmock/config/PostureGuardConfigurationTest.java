package com.shoppilot.bizmock.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非回环姿势守卫（round29 票 104 / ADR 0062）。
 *
 * <p>判据的形状与网关 {@code DevDefaultsStartupBlockerTest} 同一家法：非回环 + 默认值 = 拒启，
 * 句子点名环境变量、监听地址与出路；回环上 no-op（本机演示与全部活体判据的运行前提）。
 * 直接构造守卫而不起整个上下文——它防的正是「绕过环境处理的启动方式」，测试也走同一道门。
 */
class PostureGuardConfigurationTest {

    @Test
    @DisplayName("非回环 + 仓库默认内部令牌：拒启，句子点名环境变量、监听地址与 127.0.0.1 的出路")
    void nonLoopbackWithDefaultTokenIsBlocked() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("server.address", "0.0.0.0")
                .withProperty("shoppilot.bizmock.internal-token", "dev-internal-token-change-me");

        assertThatThrownBy(() -> new PostureGuardConfiguration(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝启动").hasMessageContaining("0.0.0.0")
                .hasMessageContaining("SHOPPILOT_INTERNAL_TOKEN").hasMessageContaining("127.0.0.1");
    }

    @Test
    @DisplayName("server.address 未设置视同监听所有网卡：默认令牌同样拒启（fail-closed）")
    void blankAddressIsFailClosed() {
        MockEnvironment env = new MockEnvironment(); // 不设 server.address

        assertThatThrownBy(() -> new PostureGuardConfiguration(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("所有网卡").hasMessageContaining("SHOPPILOT_INTERNAL_TOKEN");
    }

    @Test
    @DisplayName("持久档 + 空口令：即使令牌已覆盖也拒启，且不误伤默认档（无 PG 连接串时口令不进账本）")
    void emptyDbPasswordBlockedOnlyOnPostgresDatasource() {
        MockEnvironment pg = new MockEnvironment()
                .withProperty("server.address", "0.0.0.0")
                .withProperty("shoppilot.bizmock.internal-token", "covered")
                .withProperty("spring.datasource.url", "jdbc:postgresql://postgres:5432/bizmock")
                .withProperty("spring.datasource.password", "");
        assertThatThrownBy(() -> new PostureGuardConfiguration(pg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据库口令未设置");

        MockEnvironment h2 = new MockEnvironment()
                .withProperty("server.address", "0.0.0.0")
                .withProperty("shoppilot.bizmock.internal-token", "covered")
                .withProperty("spring.datasource.url", "jdbc:h2:mem:shoppilot;DB_CLOSE_DELAY=-1")
                .withProperty("spring.datasource.password", "");
        assertThatCode(() -> new PostureGuardConfiguration(h2)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非回环 + 全部覆盖：放行")
    void coveredCredentialsPass() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("server.address", "0.0.0.0")
                .withProperty("shoppilot.bizmock.internal-token", "covered")
                .withProperty("spring.datasource.url", "jdbc:postgresql://postgres:5432/bizmock")
                .withProperty("spring.datasource.password", "real-password");

        assertThatCode(() -> new PostureGuardConfiguration(env)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("回环 + 仓库默认值：no-op（本机演示与活体判据的运行前提）")
    void loopbackIsNoOp() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("server.address", "127.0.0.1");

        assertThatCode(() -> new PostureGuardConfiguration(env)).doesNotThrowAnyException();
    }
}
