package com.shoppilot.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把 {@link DevDefaultsPolicy} 的本次生效值作为 bean 交出去：观测面要回读「现在是不是在吃默认值」，
 * 回环上用默认值也要在启动日志里留一行响的。
 *
 * <p>这里再判一次启动阻断，不是重复劳动：环境后置处理器只在 {@code SpringApplication} 那条路上跑，
 * 谁绕过它（测试里直接建上下文、或将来换个启动方式）就只能靠这道 bean 兜住。
 */
@Configuration(proxyBeanMethods = false)
public class DevDefaultsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DevDefaultsConfiguration.class);

    @Bean
    DevDefaultsPolicy devDefaultsPolicy(Environment env) {
        DevDefaultsPolicy policy = new DevDefaultsPolicy(env.getProperty("server.address", ""),
                env.getProperty("shoppilot.jwt-secret", ""),
                env.getProperty("shoppilot.bizmock.internal-token", ""),
                env.getProperty("shoppilot.ops.token", ""),
                Boolean.TRUE.equals(env.getProperty("shoppilot.ops.enabled", Boolean.class, Boolean.TRUE)));
        if (!policy.startupBlockers().isEmpty()) {
            // 这道是第二道阻断，防的是绕过环境后置处理器的启动方式；句子与第一道同源
            throw new IllegalStateException(policy.startupBlockerMessage());
        }
        if (!policy.devDefaultsInUse().isEmpty()) {
            log.warn("回环绑定，用着仓库默认凭证 {}；改绑非回环地址会直接拒绝启动（ADR 0029）",
                    policy.devDefaultsInUse());
        }
        return policy;
    }
}
