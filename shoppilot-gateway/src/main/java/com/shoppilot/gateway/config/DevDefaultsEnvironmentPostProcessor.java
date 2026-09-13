package com.shoppilot.gateway.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 回环绑定时把 dev 默认凭证兜进环境（{@code application.yml} 里那三处只留 {@code ${ENV:}}），
 * 非回环时一处都不兜，并直接拒绝启动。
 *
 * <p>放在 EnvironmentPostProcessor 而不是某个 bean 的构造里，是为了抢在任何 bean 之前：
 * 验签那边也查密钥，但它只看长度——那个默认值恰好长到能过长度关，让先跑一步就会抛出
 * 一个指不到根因的错。这层先跑，报的就是「哪一处默认值在非回环地址上不许用」。
 */
public class DevDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String SOURCE = "shoppilotDevDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String address = environment.getProperty("server.address", "");
        DevDefaultsPolicy policy = new DevDefaultsPolicy(address,
                environment.getProperty("shoppilot.jwt-secret", ""),
                environment.getProperty("shoppilot.bizmock.internal-token", ""),
                environment.getProperty("shoppilot.ops.token", ""),
                Boolean.TRUE.equals(environment.getProperty("shoppilot.ops.enabled", Boolean.class, Boolean.TRUE)));

        if (!policy.startupBlockers().isEmpty()) {
            // 句子由 policy 给：第二道阻断（DevDefaultsConfiguration）出同一句，别长成两种形状
            throw new IllegalStateException(policy.startupBlockerMessage());
        }
        if (!policy.loopback()) {
            return;
        }
        Map<String, Object> fallbacks = new LinkedHashMap<>();
        putWhenBlank(environment, fallbacks, "shoppilot.jwt-secret", DevDefaultsPolicy.JWT_SECRET);
        putWhenBlank(environment, fallbacks, "shoppilot.bizmock.internal-token", DevDefaultsPolicy.INTERNAL_TOKEN);
        putWhenBlank(environment, fallbacks, "shoppilot.ops.token", DevDefaultsPolicy.OPS_TOKEN);
        if (!fallbacks.isEmpty()) {
            // 为什么是 addFirst：yml 里那三处是 `${ENV:}` 形状，没设环境变量时它解析成空串而不是缺失，
            // 兜底排在后面就永远被那个空串遮住——第一次实跑就栽在这里（单测全绿，起不来的是进程）。
            // 这不构成优先级倒置：putWhenBlank 只看**生效值**，只要命令行、.env 或 profile 真给了值，
            // 这个键根本不会进 fallbacks，也就没得遮。
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE, fallbacks));
        }
    }

    private static void putWhenBlank(ConfigurableEnvironment env, Map<String, Object> target, String key, String fallback) {
        String value = env.getProperty(key, "");
        if (value == null || value.isBlank()) {
            target.put(key, fallback);
        }
    }
}
