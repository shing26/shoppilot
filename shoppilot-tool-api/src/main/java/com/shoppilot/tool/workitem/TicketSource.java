package com.shoppilot.tool.workitem;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 工单来源（round23 票 69 / ADR 0055）。分流规则表的一级维度就是它。
 *
 * <p>它住在共享库里而不是工单服务里，因为**四个服务都要说这个词**：工单服务按它分派，
 * 网关按 reason 推它，业务与工具服务在落退款审批单与复核单时要指名它。
 * 跨进程共享的东西放在契约库里，是 ADR 0053「跨域只走 API 与事件」的另一半——
 * 连**枚举名**都不该有两个副本。
 *
 * <p>{@link #CHANNEL_RECEIPT} 不在 ADR 0055 枚举的三种里，是实现期发现的第四种：
 * 邮件渠道没有实时回包通道，回执以工单为交付形态（{@code EmailReceiptWriter}，ADR 0035），
 * 它早就和降级单共用那张工单表、只靠 {@code reason=EMAIL_REPLY} 区分。
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
     * 由既有 {@code reason} 反推来源。网关只发 reason 不发 source，
     * 所以这条推导是跨模块契约的一部分（票 72 明确不让拆服务顺手改契约）。
     *
     * <p>未知 reason 一律算 DEGRADE：降级是这张表的默认来路，把没见过的 reason 猜成别的来源才是危险的默认。
     */
    public static TicketSource ofReason(String reason) {
        return CHANNEL_RECEIPT_REASON.equalsIgnoreCase(reason == null ? "" : reason.trim().toUpperCase(Locale.ROOT))
                ? CHANNEL_RECEIPT
                : DEGRADE;
    }
}