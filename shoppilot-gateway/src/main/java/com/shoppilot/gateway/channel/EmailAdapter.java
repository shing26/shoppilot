package com.shoppilot.gateway.channel;

import com.shoppilot.gateway.identity.TenantContext;
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
        // 票 96：与 webhook 同一派生规则（渠道标签进键；聊天维度显式 sessionId 优先、缺省按买家）。
        String messageId = bounded(payload, "messageId");
        String sessionId = bounded(payload, "sessionId");
        String chatKey = sessionId != null ? sessionId : TenantContext.current().customerId();
        return new NormalizedChat(query, WebSseAdapter.stringOrNull(payload.get("idempotencyToken")),
                WebSseAdapter.stringOrNull(payload.get("from")),
                ChannelAdapter.deriveConversationId(ChannelContext.current(), chatKey),
                messageId == null ? null : ChannelAdapter.deriveClientToken(ChannelContext.current(), messageId));
    }

    /** 平台事件标识的上限 255，与 webhook 适配器同一家法。 */
    private static String bounded(Map<String, Object> payload, String field) {
        String value = WebSseAdapter.stringOrNull(payload.get(field));
        if (value != null && value.length() > 255) {
            throw new IllegalArgumentException(field + " 过长（上限 255）");
        }
        return value;
    }
}
