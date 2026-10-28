package com.shoppilot.gateway.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Web Widget 的公开 key（票 184）：key 换租户/买家身份，不暴露 JWT。
 * 单 key 演示档；多租户可换成 config/widgets.json，接口不变。
 */
@Component
public class WidgetKeyRegistry {

    public record WidgetIdentity(String tenantId, String customerId) {
    }

    private final String key;
    private final String tenantId;
    private final String customerId;

    public WidgetKeyRegistry(@Value("${shoppilot.widget.key:demo-widget-key}") String key,
                             @Value("${shoppilot.widget.tenant:T001}") String tenantId,
                             @Value("${shoppilot.widget.customer:C001}") String customerId) {
        this.key = key;
        this.tenantId = tenantId;
        this.customerId = customerId;
    }

    public WidgetIdentity resolve(String candidate) {
        if (candidate == null || key == null || key.isBlank()) {
            return null;
        }
        boolean matches = MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
        return matches ? new WidgetIdentity(tenantId, customerId) : null;
    }
}
