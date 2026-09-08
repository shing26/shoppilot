package com.shoppilot.bizmock.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 工单状态机（ticket 14）：{@code OPEN -> ASSIGNED -> RESOLVED}。
 *
 * <p>状态值必须校验：客服工作台允许把 status 写成任意字符串的话，
 * 一个拼错的 RESOLVED 就让工单永远进不了队列，而"已结单"是降级链路的终点证据。
 */
public enum TicketStatus {
    OPEN,
    ASSIGNED,
    RESOLVED;

    /** OPEN 可直接结单：小问题没必要强制过一次 ASSIGNED。RESOLVED 是终态。 */
    public boolean canTransitionTo(TicketStatus target) {
        return switch (this) {
            case OPEN -> target == ASSIGNED || target == RESOLVED;
            case ASSIGNED -> target == RESOLVED;
            case RESOLVED -> false;
        };
    }

    public static TicketStatus parse(String raw) {
        return Arrays.stream(values())
                .filter(status -> status.name().equalsIgnoreCase(raw == null ? "" : raw.trim()))
                .findFirst()
                .orElse(null);
    }

    public static List<String> names() {
        return Arrays.stream(values()).map(TicketStatus::name).toList();
    }

    static String normalize(String raw) {
        TicketStatus parsed = parse(raw);
        return parsed == null ? raw : parsed.name();
    }
}
