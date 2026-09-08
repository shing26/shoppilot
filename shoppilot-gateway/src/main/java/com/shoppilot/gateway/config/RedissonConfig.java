package com.shoppilot.gateway.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端，与 Spring 的 Lettuce 共用同一个 Redis 但各持一套连接池。
 *
 * <p>只用它做限流与分布式锁，缓存读写仍走 StringRedisTemplate：
 * 引入 Redisson 的代价是多一个客户端实现，把它限定在"需要原子租约的场景"最划算。
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(@Value("${spring.data.redis.host}") String host,
                                         @Value("${spring.data.redis.port}") int port) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(16)
                .setConnectTimeout(3000)
                .setTimeout(2000);
        return Redisson.create(config);
    }
}
