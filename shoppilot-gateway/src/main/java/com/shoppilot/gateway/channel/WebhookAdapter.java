package com.shoppilot.gateway.channel;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 通用 HTTP JSON 入口（模拟 App / 小程序 / 三方回调用）：整段 JSON 回包、无流式。
 * 渠道标签由调用路径给（app / miniapp / webhook），归一规则本身渠道无关。
 */
@Component
public class WebhookAdapter implements ChannelAdapter {

    @Override
    public Channel channel() {
        return Channel.WEBHOOK;
    }

    @Override
    public boolean streaming() {
        return false;
    }

    @Override
    public boolean canFollowUp() {
        return false;
    }

    @Override
    public NormalizedChat normalize(Map<String, Object> payload) {
        String query = WebSseAdapter.stringOrNull(payload.get("query"));
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        return new NormalizedChat(query, WebSseAdapter.stringOrNull(payload.get("idempotencyToken")), null);
    }
}
