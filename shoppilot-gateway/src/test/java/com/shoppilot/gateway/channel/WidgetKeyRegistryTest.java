package com.shoppilot.gateway.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 票 184：Widget key 常量时间比较，不泄露有效 key。 */
class WidgetKeyRegistryTest {

    private final WidgetKeyRegistry registry = new WidgetKeyRegistry(
            "demo-widget-key", "T001", "C001");

    @Test
    @DisplayName("正确 key 换出固定租户/买家")
    void resolvesCorrectKey() {
        WidgetKeyRegistry.WidgetIdentity identity = registry.resolve("demo-widget-key");

        assertThat(identity).isNotNull();
        assertThat(identity.tenantId()).isEqualTo("T001");
        assertThat(identity.customerId()).isEqualTo("C001");
    }

    @Test
    @DisplayName("错误/空 key 不换身份")
    void rejectsWrongKey() {
        assertThat(registry.resolve("wrong")).isNull();
        assertThat(registry.resolve("")).isNull();
        assertThat(registry.resolve(null)).isNull();
    }
}
