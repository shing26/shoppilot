package com.shoppilot.gateway.channel;

import java.util.Map;

/**
 * 入站归一层（ADR 0035）：把各来源报文归一为统一入站，并声明该渠道的回包能力。
 * 所有渠道共享同一条状态机、同一套缓存防线与归属校验——渠道差异收敛在适配层，链路本体唯一。
 */
public interface ChannelAdapter {

    Channel channel();

    /** 该渠道能否流式回包。web 的 SSE 为真；webhook/email 为假（整段或异步回执）。 */
    boolean streaming();

    /** 该渠道能否在同一连接上追问（例如槽位追问）。 */
    boolean canFollowUp();

    /**
     * 原始报文 → 统一入站。身份不在这里推导：渠道不参与身份（ADR 0025），
     * 租户与买家一律来自 JWT。
     */
    NormalizedChat normalize(Map<String, Object> payload);

    /**
     * 平台事件的会话 id 派生（票 96 / round31）：渠道标签必须进键——
     * 「同一个人、不同渠道」派生的会话 id 不同，防两个平台共用一个 session 键；
     * 聊天维度（{@code chatKey}）由适配器决定（显式 sessionId 优先，缺省用买家 id），
     * 不带它就会退化成「一个渠道所有人共用一段会话」。
     */
    static String deriveConversationId(Channel channel, String chatKey) {
        return channel.label() + ":chat:" + chatKey;
    }

    /**
     * 平台消息的幂等 token 派生（票 96 / round31）：从平台 message id 派生稳定 token，
     * 让 {@code IdempotencyService} 走 clientToken 那条路（它优先于参数派生），
     * 而不是落到「不含渠道、TTL 6 小时、靠参数猜」的派生路径。渠道前缀同样防跨平台撞 id。
     */
    static String deriveClientToken(Channel channel, String messageId) {
        return channel.label() + ":msg:" + messageId;
    }

    /**
     * @param query            归一后的买家诉求原文
     * @param idempotencyToken 客户端**显式**给的幂等令牌，缺省为 null
     * @param contact          渠道侧回执线索（如邮件发件地址），仅随回执记录，不参与身份
     * @param conversationId   适配器派生的会话 id（票 96）。**适配器有责任派生它**，不能指望调用方
     *                         每次都传 {@code X-Conversation-Id}——没有它 AuthFilter 会现生成随机
     *                         UUID，每条消息都是全新会话、追问必然断。null = 该渠道不派生
     *                         （web 渠道有自己的会话头机制），沿用请求头或随机行为。
     * @param clientToken      适配器从平台 message id 派生的幂等 token（票 96），缺省为 null。
     *                         显式 {@code idempotencyToken} 优先于它。
     */
    record NormalizedChat(String query, String idempotencyToken, String contact,
                          String conversationId, String clientToken) {

        /** 旧三参形态：两格派生为 null（web 渠道与无平台事件标识的调用方）。 */
        public static NormalizedChat of(String query, String idempotencyToken, String contact) {
            return new NormalizedChat(query, idempotencyToken, contact, null, null);
        }
    }
}
