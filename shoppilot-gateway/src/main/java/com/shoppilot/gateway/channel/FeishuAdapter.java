package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 飞书单聊文本事件的归一（round31 票 97 / ADR 0065）：吃**平台原样事件 JSON**（v2 事件格式，
 * {@code im.message.receive_v1}），归一进 {@link ChannelAdapter} 契约。
 *
 * <p>与 webhook/email 适配器的两个真实差异：
 * <ul>
 *   <li>**不读 {@code ChannelContext} / {@code TenantContext}**：飞书事件从长连接进来
 *       （票 113），那个线程上没有 HTTP 请求、没有 AuthFilter——渠道标签取自
 *       {@link #channel()}，会话键的聊天维度取事件里的 {@code chat_id}（平台侧天然隔离，
 *       与买家映射无关——平台 id 与 customerId 的映射是 R3，明确不做）。真实链路里
 *       AuthFilter 恒先于 webhook/email 归一，对飞书不成立。</li>
 *   <li>**contact 恒为 null**：回复走长连接出站 API（SDK），不存在「送回哪儿」这个问题。</li>
 * </ul>
 *
 * <p>范围（round31 spec §5）：**仅单聊（p2p）文本**。群聊、富媒体、卡片登记不做；
 * 非本范围的形状当场拒（IllegalArgumentException → 上层 400 语义），不静默吞。
 */
@Component
public class FeishuAdapter implements ChannelAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Channel channel() {
        return Channel.FEISHU;
    }

    @Override
    public boolean streaming() {
        return false;
    }

    @Override
    public boolean canFollowUp() {
        // 单聊同一段会话内可以追问；「流式」没有（长连接出站是整段发送）
        return true;
    }

    @Override
    public NormalizedChat normalize(Map<String, Object> payload) {
        JsonNode event = MAPPER.valueToTree(payload).path("event");
        JsonNode message = event.path("message");
        if (message.isMissingNode() || message.path("message_id").asText("").isBlank()) {
            throw new IllegalArgumentException("不是飞书 im.message.receive_v1 事件形状（缺 event.message.message_id）");
        }
        String chatType = message.path("chat_type").asText("");
        if (!"p2p".equals(chatType)) {
            throw new IllegalArgumentException("round31 仅支持单聊（p2p）消息，收到 chat_type=" + chatType);
        }
        String messageType = message.path("message_type").asText("");
        if (!"text".equals(messageType)) {
            throw new IllegalArgumentException("round31 仅支持 text 消息，收到 message_type=" + messageType);
        }
        // content 是**字符串化的 JSON**（飞书事件契约）："{\"text\":\"你好\"}"——二次解析取文本
        String text = extractText(message.path("content").asText(""));
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text 消息的 content 里没有可用的 text 字段");
        }
        // 两格派生（票 96 辅助）：渠道标签来自 channel()（长连接线程没有 ChannelContext）；
        // 聊天维度 = chat_id（单聊里与该联系人一一对应，群聊将来也是它，形状不变）
        String messageId = message.path("message_id").asText();
        String chatId = message.path("chat_id").asText();
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("飞书事件缺少 chat_id");
        }
        return new NormalizedChat(text, null, null,
                ChannelAdapter.deriveConversationId(channel(), chatId),
                ChannelAdapter.deriveClientToken(channel(), messageId));
    }

    private static String extractText(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(content).path("text").asText(null);
        } catch (Exception malformed) {
            return null;
        }
    }
}
