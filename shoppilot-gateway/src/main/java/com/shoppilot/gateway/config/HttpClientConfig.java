package com.shoppilot.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 共享 JDK HttpClient。
 *
 * <p>全网关只用一个实例：HttpClient 内部持有连接池与选择器线程，每处 new 一个
 * 等于把连接复用白白丢掉。虚拟线程下阻塞 send 不占平台线程，所以不需要异步 API。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
