package com.shoppilot.gateway.channel;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.shoppilot.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 飞书 API 消息发送实现（round31 票 113 / ADR 0065）。
 *
 * <p>使用飞书官方 SDK 的 {@link Client} 发送消息。
 * 凭据从 {@link GatewayProperties.Feishu} 获取，不硬编码。
 */
@Component
public class FeishuApiReplySender implements FeishuReplySender {

    private static final Logger log = LoggerFactory.getLogger(FeishuApiReplySender.class);

    private final Client client;

    public FeishuApiReplySender(GatewayProperties properties) {
        GatewayProperties.Feishu feishu = properties.feishu();
        this.client = Client.newBuilder(feishu.appId(), feishu.appSecret()).build();
    }

    @Override
    public void send(String chatId, String message) {
        try {
            String content = "{\"text\":\"" + escapeJson(message) + "\"}";
            CreateMessageReq req = CreateMessageReq.newBuilder()
                    .receiveIdType("chat_id")
                    .createMessageReqBody(CreateMessageReqBody.newBuilder()
                            .receiveId(chatId)
                            .msgType("text")
                            .content(content)
                            .build())
                    .build();
            CreateMessageResp resp = client.im().message().create(req);
            if (!resp.success()) {
                log.error("飞书消息发送失败: code={} msg={}", resp.getCode(), resp.getMsg());
            }
        } catch (Exception e) {
            log.error("飞书消息发送异常: chatId={} error={}", chatId, e.getMessage());
        }
    }

    /**
     * 转义 JSON 字符串中的特殊字符。
     */
    private static String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
