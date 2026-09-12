package com.shoppilot.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 环境后置处理器这一层管两件事：回环上把 dev 默认值兜进来（并且输给任何真配置），
 * 非回环上抢在所有 bean 之前拒绝启动。默认值在 {@link DevDefaultsPolicy} 里只有一份，
 * 配置文件那三处是空的——所以「兜进来的就是真正在用的」这句话得在这里被钉住。
 */
class DevDefaultsEnvironmentPostProcessorTest {

    private final DevDefaultsEnvironmentPostProcessor postProcessor = new DevDefaultsEnvironmentPostProcessor();

    @Test
    @DisplayName("回环 + 什么都没配：三处默认值兜进来，且这个来源具名可查")
    void loopbackFillsTheThreeDefaults() {
        ConfigurableEnvironment env = envWith(Map.of("server.address", "127.0.0.1"));

        run(env);

        assertThat(env.getProperty("shoppilot.jwt-secret")).isEqualTo(DevDefaultsPolicy.JWT_SECRET);
        assertThat(env.getProperty("shoppilot.bizmock.internal-token")).isEqualTo(DevDefaultsPolicy.INTERNAL_TOKEN);
        assertThat(env.getProperty("shoppilot.ops.token")).isEqualTo(DevDefaultsPolicy.OPS_TOKEN);
        assertThat(env.getPropertySources().contains(DevDefaultsEnvironmentPostProcessor.SOURCE)).isTrue();
    }

    @Test
    @DisplayName("「键存在但值是空串」这个 yml 形状也必须兜住：兜底不排在那个空串之上就等于没兜")
    void blankValueFromYamlShapeIsStillFilled() {
        // 真实形状是 application.yml 里的 `${SHOPPILOT_JWT_SECRET:}`：没设环境变量时键在、值为空串。
        // 这条用例是实跑第一次栽过之后补的——当时单测全绿，起不来的是进程。
        ConfigurableEnvironment env = envWith(Map.of(
                "server.address", "127.0.0.1",
                "shoppilot.jwt-secret", "",
                "shoppilot.bizmock.internal-token", "",
                "shoppilot.ops.token", ""));

        run(env);

        assertThat(env.getProperty("shoppilot.jwt-secret")).isEqualTo(DevDefaultsPolicy.JWT_SECRET);
        assertThat(env.getProperty("shoppilot.bizmock.internal-token")).isEqualTo(DevDefaultsPolicy.INTERNAL_TOKEN);
        assertThat(env.getProperty("shoppilot.ops.token")).isEqualTo(DevDefaultsPolicy.OPS_TOKEN);
    }

    @Test
    @DisplayName("显式配置赢过兜底：给了值的键根本不会进兜底表，所以不存在谁压谁")
    void explicitValuesWinOverFallback() {
        Map<String, Object> seed = new HashMap<>();
        seed.put("server.address", "127.0.0.1");
        seed.put("shoppilot.jwt-secret", "an-overridden-secret-that-is-long-123456");
        ConfigurableEnvironment env = envWith(seed);

        run(env);

        assertThat(env.getProperty("shoppilot.jwt-secret")).isEqualTo("an-overridden-secret-that-is-long-123456");
        assertThat(env.getProperty("shoppilot.ops.token")).isEqualTo(DevDefaultsPolicy.OPS_TOKEN);
    }

    @Test
    @DisplayName("非回环 + 仍吃默认值：拒绝启动，且 message 逐条点名环境变量")
    void externalBindWithDefaultsRefusesToStart() {
        ConfigurableEnvironment env = envWith(Map.of("server.address", "0.0.0.0"));

        assertThatThrownBy(() -> run(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHOPPILOT_JWT_SECRET")
                .hasMessageContaining("SHOPPILOT_INTERNAL_TOKEN")
                .hasMessageContaining("SHOPPILOT_OPS_TOKEN");
    }

    @Test
    @DisplayName("非回环 + 三处都覆盖：放行，且不注入那份兜底")
    void externalBindWithOverridesStartsWithoutFallback() {
        Map<String, Object> seed = new HashMap<>();
        seed.put("server.address", "0.0.0.0");
        seed.put("shoppilot.jwt-secret", "an-overridden-secret-that-is-long-123456");
        seed.put("shoppilot.bizmock.internal-token", "an-overridden-internal-token");
        seed.put("shoppilot.ops.token", "an-overridden-ops-token");
        ConfigurableEnvironment env = envWith(seed);

        assertThatCode(() -> run(env)).doesNotThrowAnyException();
        assertThat(env.getPropertySources().contains(DevDefaultsEnvironmentPostProcessor.SOURCE)).isFalse();
    }

    @Test
    @DisplayName("压根没配 server.address 按非回环处理：message 要说清是「没设置」而不是某个地址")
    void absentAddressFailsClosedAndSaysSo() {
        ConfigurableEnvironment env = envWith(Map.of());

        assertThatThrownBy(() -> run(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.address 未设置");
    }

    @Test
    @DisplayName("非回环 + 只关掉运维端点：令牌仍默认也不再是阻断项，另外两条仍然要点名")
    void disablingOpsRemovesOnlyThatBlocker() {
        Map<String, Object> seed = new HashMap<>();
        seed.put("server.address", "0.0.0.0");
        seed.put("shoppilot.ops.enabled", "false");
        ConfigurableEnvironment env = envWith(seed);

        assertThatThrownBy(() -> run(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("SHOPPILOT_OPS_TOKEN")
                .hasMessageContaining("SHOPPILOT_JWT_SECRET");
    }

    private void run(ConfigurableEnvironment env) {
        postProcessor.postProcessEnvironment(env, mock(SpringApplication.class));
    }

    private static ConfigurableEnvironment envWith(Map<String, Object> values) {
        StandardEnvironment env = new StandardEnvironment();
        if (!values.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource("ticket21", values));
        }
        return env;
    }
}
