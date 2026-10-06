package com.shoppilot.ticket.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非回环姿势守卫（round29 票 104 / ADR 0062）。判据形状与 biz-mock 侧同一家法，
 * 属性键是本服务的（{@code shoppilot.ticket.internal-token}）。
 */
class PostureGuardConfigurationTest {

    @Test
    @DisplayName("非回环 + 仓库默认内部令牌：拒启，句子点名环境变量、监听地址与 127.0.0.1 的出路")
    void nonLoopbackWithDefaultTokenIsBlocked() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("server.address", "0.0.0.0")
                .withProperty("shoppilot.ticket.internal-token", "dev-internal-token-change-me");

        assertThatThrownBy(() -> new PostureGuardConfiguration(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝启动").hasMessageContaining("0.0.0.0")
                .hasMessageContaining("SHOPPILOT_INTERNAL_TOKEN").hasMessageContaining("127.0.0.1");
    }

    @Test
    @DisplayName("持久档 + 空口令：拒启；默认档（H2）不受影响")
    void emptyDbPasswordBlockedOnlyOnPostgresDatasource() {
        MockEnvironment pg = new MockEnvironment()
                .withProperty("server.address", "0.0.0.0")
                .withProperty("shoppilot.ticket.internal-token", "covered")
                .withProperty("spring.datasource.url", "jdbc:postgresql://postgres:5432/ticket")
                .withProperty("spring.datasource.password", "");
        assertThatThrownBy(() -> new PostureGuardConfiguration(pg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据库口令未设置");

        MockEnvironment h2 = new MockEnvironment()
                .withProperty("server.address", "0.0.0.0")
                .withProperty("shoppilot.ticket.internal-token", "covered")
                .withProperty("spring.datasource.url", "jdbc:h2:mem:shoppilot-ticket;DB_CLOSE_DELAY=-1")
                .withProperty("spring.datasource.password", "");
        assertThatCode(() -> new PostureGuardConfiguration(h2)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非回环 + 全部覆盖：放行；回环 + 默认值：no-op")
    void coveredPassesAndLoopbackIsNoOp() {
        MockEnvironment covered = new MockEnvironment()
                .withProperty("server.address", "0.0.0.0")
                .withProperty("shoppilot.ticket.internal-token", "covered")
                .withProperty("spring.datasource.url", "jdbc:postgresql://postgres:5432/ticket")
                .withProperty("spring.datasource.password", "real-password");
        assertThatCode(() -> new PostureGuardConfiguration(covered)).doesNotThrowAnyException();

        MockEnvironment loopback = new MockEnvironment().withProperty("server.address", "127.0.0.1");
        assertThatCode(() -> new PostureGuardConfiguration(loopback)).doesNotThrowAnyException();
    }
}
