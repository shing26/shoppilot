package com.shoppilot.gateway.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 只校验 server 段的格式与范围；端口是否可绑定由启动器负责。 */
@Validated
@ConfigurationProperties(prefix = "server")
public record ValidatedServerProperties(
        @Min(value = 1, message = "server.port must be between 1 and 65535")
        @Max(value = 65535, message = "server.port must be between 1 and 65535")
        Integer port) {
}
