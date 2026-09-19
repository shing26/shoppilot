package com.shoppilot.gateway.channel;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * web 渠道（既有 /chat 与 /chat/stream 的形态搬运，行为零变更）：能流式、能追问。
 * 归一逻辑就是把既有请求体原样翻译，不引入任何新字段。
 */
@Component
public class WebSseAdapter implements ChannelAdapter {

    @Override
    public Channel channel() {
        return Channel.WEB;
    }

    @Override
    public boolean streaming() {
        return true;
    }

    @Override
    public boolean canFollowUp() {
        return true;
    }

    @Override
    public NormalizedChat normalize(Map<String, Object> payload) {
        String query = stringOrNull(payload.get("query"));
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        return new NormalizedChat(query, stringOrNull(payload.get("idempotencyToken")), null);
    }

    static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
