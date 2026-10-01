package com.shoppilot.bizmock.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 工单来源（round23 票 69 / ADR 0055）。分流规则表的第一个维度就是它。
 *
 * <p>「需要人工介入」在本仓原先是三个混血体——降级工单、反馈复核队列、退款审批队列——
 * 三套机制三张表。统一成一张工单表之后，来源就是它们各自的来路，
 * 也因此这个枚举是**分流规则表的一级键**：来源错了，队列就错了。
 *
 * <p>{@link #CHANNEL_RECEIPT} 不在 ADR 0055 枚举的三种里，是实现期发现的第四种：
 * 邮件渠道没有实时回包通道，回执以工单为交付形态（{@code EmailReceiptWriter}，
 * ADR 0035），它早就和降级单共用这张 tickets 表、只靠 {@code reason=EMAIL_REPLY} 区分。
 * 折进 {@link #DEGRADE} 会把「渠道异步回执」和「买家转人工」混进同一个队列——
 * 而那正是原注释里「人工队列按 reason 一看就知道这条不是升级单」要防的事。
 */
public enum TicketSource {
    /** 降级链路的终点（ADR 0009 的九种降级 + 情绪升级）。 */
    DEGRADE,
    /** 满意度点踩进复核队列（ADR 0039）。 */
    FEEDBACK_REVIEW,
    /** 退款审批闸门待人工放行（ADR 0047）。 */
    REFUND_APPROVAL,
    /** 渠道回执工单（ADR 0035）：邮件/webhook 这类无回包通道的渠道。 */
    CHANNEL_RECEIPT;

    /** 渠道回执在 reason 上的既有取值，来源与它的映射靠它保持。 */
    public static final String CHANNEL_RECEIPT_REASON = "EMAIL_REPLY";

    public static TicketSource parse(String raw) {
        return Arrays.stream(values())
                .filter(source -> source.name().equalsIgnoreCase(raw == null ? "" : raw.trim()))
                .findFirst()
                .orElse(null);
    }

    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /**
     * 由既有 {@code reason} 反推来源。
     *
     * <p>跨模块契约不因本票而改：网关只发 reason（{@code EmailReceiptWriter} 就只发
     * {@code reason=EMAIL_REPLY}），工单来源在业务侧推导。未知 reason 一律算 DEGRADE——
     * 降级是这张表的默认来路，把没见过的 reason 猜成别的来源才是危险的默认。
     */
    public static TicketSource ofReason(String reason) {
        return CHANNEL_RECEIPT_REASON.equalsIgnoreCase(reason == null ? "" : reason.trim().toUpperCase(Locale.ROOT))
                ? CHANNEL_RECEIPT
                : DEGRADE;
    }
}
