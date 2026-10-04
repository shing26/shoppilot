package com.shoppilot.gateway.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * webhook 入站的可选 {@code callbackUrl}（round26 票 88 / ADR 0059 第 4 条）。
 *
 * <p>在这一格之前 webhook 归一出来的 {@code contact} 恒为 null，于是 webhook 来源的工单
 * **永远没有投递目标**——整条出站路径对它来说是死的。这组用例钉住「有就给、没有也算数、
 * 形状不对就当场拒」。
 */
class WebhookCallbackUrlTest {

    private final WebhookAdapter adapter = new WebhookAdapter();

    @Test
    @DisplayName("给了 callbackUrl 就当投递目标带走")
    void callbackUrlBecomesTheDeliveryTarget() {
        ChannelAdapter.NormalizedChat normalized =
                adapter.normalize(Map.of("query", "查一下", "callbackUrl", "http://127.0.0.1:9999/hook"));

        assertThat(normalized.contact()).isEqualTo("http://127.0.0.1:9999/hook");
    }

    @Test
    @DisplayName("不给 callbackUrl 也能正常问——只是没有目标，不假装有")
    void missingCallbackUrlIsStillAValidRequest() {
        ChannelAdapter.NormalizedChat normalized = adapter.normalize(Map.of("query", "查一下"));

        assertThat(normalized.contact()).isNull();
    }

    @Test
    @DisplayName("只接受带主机名的 http/https 绝对地址：file:// 与相对地址当场拒")
    void nonHttpOrRelativeTargetsAreRejected() {
        assertThatThrownBy(() -> adapter.normalize(payload("file:///etc/passwd")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http/https");
        assertThatThrownBy(() -> adapter.normalize(payload("/relative/hook")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.normalize(payload("not a url at all")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("过长的目标当场拒，而不是落库时截断成半截地址")
    void overlyLongTargetIsRejected() {
        String tooLong = "http://127.0.0.1/" + "x".repeat(300);

        assertThatThrownBy(() -> adapter.normalize(payload(tooLong)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过长");
    }

    @Test
    @DisplayName("query 仍然是必填——新增可选字段没有放松既有校验")
    void queryIsStillRequired() {
        assertThatThrownBy(() -> adapter.normalize(Map.of("callbackUrl", "http://127.0.0.1/hook")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    private static Map<String, Object> payload(String callbackUrl) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("query", "查一下");
        payload.put("callbackUrl", callbackUrl);
        return payload;
    }
}