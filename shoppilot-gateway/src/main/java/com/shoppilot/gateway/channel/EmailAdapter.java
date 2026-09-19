package com.shoppilot.gateway.channel;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 邮件渠道（ADR 0035：同步收件 → 全链路 → 结果落工单/回执，不做邮件服务器）。
 * 无流式：结果以回执工单为交付形态（见 {@link EmailReceiptWriter}）。
 */
@Component
public class EmailAdapter implements ChannelAdapter {

    @Override
    public Channel channel() {
        return Channel.EMAIL;
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
        String body = WebSseAdapter.stringOrNull(payload.get("body"));
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body 不能为空");
        }
        String subject = WebSseAdapter.stringOrNull(payload.get("subject"));
        String query = subject == null || subject.isBlank() ? body : "【主题】" + subject + "\n" + body;
        return new NormalizedChat(query, WebSseAdapter.stringOrNull(payload.get("idempotencyToken")),
                WebSseAdapter.stringOrNull(payload.get("from")));
    }
}
