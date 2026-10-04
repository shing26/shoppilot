package com.shoppilot.tool.audit;

import java.time.Instant;

/**
 * 渠道出站事件（round23 只落契约 → **round26 票 87 落生产端**、票 88 落消费端）。
 *
 * <p>形状定在 round23 是因为它跨进程（工单服务产出、网关消费），两边必须编译同一个形状；
 * 但**只有契约不等于功能在**——round23 的裁定 C 明确禁止为没有生产者的 topic 造代码。
 *
 * <p><b>第一生产者是工单结单</b>（ADR 0059）：聊天答案是同步 HTTP 返回的，不需要出站；
 * 真的异步答复是「坐席处理完之后结论怎么送回买家」。
 */
public record ChannelOutboundEvent(
        String eventId,
        int schemaVersion,
        String tenantId,
        /** webhook / email / web 之类的渠道标识。 */
        String channel,
        /** 收件目标（webhook URL 或邮件地址）——出站适配器据此投递。 */
        String target,
        String body,
        Instant occurredAt,
        /**
         * 指回那张工单（round26 票 87，**追加在尾部**）。
         *
         * <p>为什么必须加：消费端对账、失败落回执工单时都要指名是哪一张单的事。
         * 只有 {@code eventId} 时，「这条结论来自哪张单」只能靠 body 里的文本猜。
         * 旧七参构造保留，落到 null。
         */
        String ticketId) {

    public static final int CURRENT_SCHEMA = 1;

    /** 票 87 之前的七参构造，落到「不指回工单」。 */
    public ChannelOutboundEvent(String eventId, int schemaVersion, String tenantId, String channel, String target,
                                String body, Instant occurredAt) {
        this(eventId, schemaVersion, tenantId, channel, target, body, occurredAt, null);
    }
}