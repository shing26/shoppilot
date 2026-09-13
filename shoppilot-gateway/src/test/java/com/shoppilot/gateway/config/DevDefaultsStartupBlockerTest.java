package com.shoppilot.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 两道启动阻断说的是同一句话（票 25 承接票 21 的 S2）。
 *
 * <p>缺陷本相：第一道在环境后置处理器里，第二道在 bean 构造里，两处各写一遍句子、措辞还不同，
 * 同一种失败就有两种形状了。第二道删不得——它防的正是绕过第一道的启动方式（测试里直接建上下文），
 * 所以收法不是合并判断，是把句子收进 {@link DevDefaultsPolicy#startupBlockerMessage()} 一处。
 */
class DevDefaultsStartupBlockerTest {

    private static final Map<String, Object> NON_LOOPBACK_DEFAULTS = Map.of("server.address", "0.0.0.0");

    @Test
    @DisplayName("那句话点名到每一处默认凭证、当前监听地址、以及改回回环的出路")
    void messageNamesEveryBlockerAndTheWayOut() {
        String message = policyAt("0.0.0.0").startupBlockerMessage();

        assertThat(message).contains("拒绝启动").contains("0.0.0.0")
                .contains("SHOPPILOT_JWT_SECRET").contains("SHOPPILOT_INTERNAL_TOKEN")
                .contains("SHOPPILOT_OPS_TOKEN").contains("127.0.0.1");
    }

    @Test
    @DisplayName("第一道（环境后置处理器）抛的就是 policy 里那句，不是自己另写一遍")
    void postProcessorUsesTheSharedSentence() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", NON_LOOPBACK_DEFAULTS));

        assertThatThrownBy(() -> new DevDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(env, mock(SpringApplication.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(policyAt("0.0.0.0").startupBlockerMessage());
    }

    @Test
    @DisplayName("第二道（bean 工厂）与第一道逐字相同：一处改措辞，另一处当场跟不上")
    void beanFactoryUsesTheSameSentence() {
        MockEnvironment env = new MockEnvironment().withProperty("server.address", "0.0.0.0");

        assertThatThrownBy(() -> new DevDefaultsConfiguration().devDefaultsPolicy(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(policyAt("0.0.0.0").startupBlockerMessage());
    }

    /** 两处判据都从环境读数建 policy，这里按同一套生效值复现一份，用来取「那句话」。 */
    private static DevDefaultsPolicy policyAt(String address) {
        return new DevDefaultsPolicy(address, "", "", "", true);
    }
}
