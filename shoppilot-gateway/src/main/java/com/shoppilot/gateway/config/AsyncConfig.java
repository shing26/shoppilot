package com.shoppilot.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 缓存写回线程池（ADR 0006：写回是异步的，不能拖慢用户响应）。
 *
 * <p>队列有界 + CallerRuns：写回积压时让请求线程自己补一刀，宁可慢一点也不把答案悄悄丢掉。
 */
@Configuration
@EnableScheduling
public class AsyncConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService writeBackExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 8, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2000),
                runnable -> {
                    Thread thread = new Thread(runnable, "cache-write-back");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
