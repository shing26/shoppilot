package com.shoppilot.gateway.config;

import com.shoppilot.gateway.cache.WriteBackPool;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 缓存写回池的装配（ADR 0006：写回是异步的，不能拖慢用户响应；停机与饱和行为见票 27、ADR 0030）。
 *
 * <p>池参数沿用旧形状（2 核 8 峰、60s 存活、2000 深队列），但本体搬进 {@link WriteBackPool}：
 * 队列积压时提交线程代跑并被计数；停机走「宽限 5s → 超时强制 → 丢弃计数」，
 * 与 graceful shutdown 的 HTTP 收尾 30s 合成 35s 总预算（写在 README 运维段）。
 */
@Configuration
@EnableScheduling
public class AsyncConfig {

    @Bean(destroyMethod = "close")
    public WriteBackPool writeBackPool(MeterRegistry registry) {
        return new WriteBackPool(2, 8, 60L, 2000, registry);
    }
}
