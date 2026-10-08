package com.shoppilot.gateway.channel;

/**
 * 飞书消息发送接口（round31 票 113 / ADR 0065）。
 *
 * <p>抽象回复发送，让 {@link FeishuLongConnectionClient} 可以在测试中注入 mock 实现。
 */
public interface FeishuReplySender {

    /**
     * 发送文本消息到指定飞书会话。
     *
     * @param chatId  飞书会话 ID
     * @param message 消息正文
     */
    void send(String chatId, String message);
}
