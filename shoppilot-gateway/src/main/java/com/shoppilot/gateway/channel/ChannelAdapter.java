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
     * @param query            归一后的买家诉求原文
     * @param idempotencyToken 客户端幂等令牌，缺省为 null
     * @param contact          渠道侧回执线索（如邮件发件地址），仅随回执记录，不参与身份
     */
    record NormalizedChat(String query, String idempotencyToken, String contact) {
    }
}
