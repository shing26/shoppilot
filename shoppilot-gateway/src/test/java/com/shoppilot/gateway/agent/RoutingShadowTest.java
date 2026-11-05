package com.shoppilot.gateway.agent;

import com.shoppilot.tool.Intent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage3 影子路由（round48 补口 / ADR 0083）。
 *
 * <p>这一层的判据只有两条：**关着的时候什么都不做**，**开着的时候只记录、不发生任何行为改变**。
 * 它不证明路由表正确——那是 180 gold 的离线结论；这里守的是「影子不会偷偷变成执行」。
 */
class RoutingShadowTest {

    private static Counter counter(SimpleMeterRegistry registry, String intentClass, String suggested,
                                   String actual, boolean agree) {
        return registry.find(RoutingShadow.METRIC)
                .tags("intent_class", intentClass, "suggested", suggested, "actual", actual,
                        "agree", Boolean.toString(agree))
                .counter();
    }

    @Test
    @DisplayName("默认关闭：一次 observe 不登记任何指标（零开销）")
    void disabledRegistersNothing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RoutingShadow shadow = new RoutingShadow(false, "local", registry);

        shadow.observe(Intent.POLICY_RETURN);
        shadow.observe(Intent.ACTION_ORDER);

        assertThat(registry.find(RoutingShadow.METRIC).counters()).isEmpty();
    }

    @Test
    @DisplayName("local 档：政策一致、动作建议 dev 记为不一致、转人工不需要模型")
    void localModeAgreement() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RoutingShadow shadow = new RoutingShadow(true, "local", registry);

        shadow.observe(Intent.POLICY_RETURN);
        shadow.observe(Intent.ACTION_ORDER);
        shadow.observe(Intent.ESCALATE);

        assertThat(counter(registry, "policy", "local", "local", true).count()).isEqualTo(1.0d);
        assertThat(counter(registry, "action", "dev", "local", false).count()).isEqualTo(1.0d);
        assertThat(counter(registry, "policy", "none", "local", true).count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("dev 档：动作一致、政策建议 local 记为不一致；同一格重复观测累加")
    void devModeAgreementAccumulates() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RoutingShadow shadow = new RoutingShadow(true, "DEV", registry);

        shadow.observe(Intent.ACTION_ORDER);
        shadow.observe(Intent.ACTION_ORDER);
        shadow.observe(Intent.POLICY_RETURN);

        assertThat(counter(registry, "action", "dev", "dev", true).count()).isEqualTo(2.0d);
        assertThat(counter(registry, "policy", "local", "dev", false).count()).isEqualTo(1.0d);
    }
}
