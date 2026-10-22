package com.shoppilot.gateway.config;

import com.shoppilot.gateway.web.OpsAuthorizationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 级守卫注册。AuthFilter 先写 {@link com.shoppilot.gateway.identity.TenantContext}，
 * interceptor 后判角色/运维凭证，顺序由 Spring 的 filter → interceptor 保证。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final OpsAuthorizationInterceptor opsAuthorization;

    public WebMvcConfig(OpsAuthorizationInterceptor opsAuthorization) {
        this.opsAuthorization = opsAuthorization;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(opsAuthorization).addPathPatterns("/api/v1/support/ops/**");
    }
}
