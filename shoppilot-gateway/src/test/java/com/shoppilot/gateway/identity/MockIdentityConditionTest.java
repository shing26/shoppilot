package com.shoppilot.gateway.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 注册条件本身：非回环时 mock 身份签发这个 bean 压根不该存在。 */
class MockIdentityConditionTest {

    private final MockIdentityCondition condition = new MockIdentityCondition();

    @ParameterizedTest
    @CsvSource({
            "127.0.0.1, true",
            "localhost, true",
            "::1, true",
            "0.0.0.0, false",
            "10.0.0.8, false",
    })
    @DisplayName("回环注册地址，非回环不注册")
    void registersOnlyOnLoopback(String address, boolean expected) {
        assertThat(condition.matches(contextWithAddress(address), metadata())).isEqualTo(expected);
    }

    @Test
    @DisplayName("完全没配 server.address 时不注册：那是监听所有网卡")
    void absentAddressDoesNotRegister() {
        assertThat(condition.matches(context(new StandardEnvironment()), metadata())).isFalse();
    }

    private static ConditionContext contextWithAddress(String address) {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> values = new HashMap<>();
        values.put("server.address", address);
        environment.getPropertySources().addFirst(new MapPropertySource("mockIdentityTest", values));
        return context(environment);
    }

    private static ConditionContext context(StandardEnvironment environment) {
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return context;
    }

    private static AnnotatedTypeMetadata metadata() {
        // 本条件不读注解元数据，mock 一个即可（仓库里 FallbackReasonTest 同用法）
        return mock(AnnotatedTypeMetadata.class);
    }
}
