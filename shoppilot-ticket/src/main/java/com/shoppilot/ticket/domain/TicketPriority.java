package com.shoppilot.ticket.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 分流优先级（round23 票 70 / ADR 0055）：{@code URGENT_EMOTION > MONEY > NORMAL}。
 *
 * <p><b>领域名与存储字面量故意不同</b>，这不是笔误：
 * <ul>
 *   <li>领域侧叫 {@code URGENT_EMOTION}，读代码时一眼知道是哪种优先；</li>
 *   <li>存储侧仍是 {@code "high"}——{@code tickets.priority} 的字面量是 {@code verify-emotion.ps1}
 *       的活体判据（逐字断言情绪升级单 {@code priority=high}，ADR 0034）。改字面量就是改判据面；</li>
 *   <li>所以换代的是这一列能表达什么（从二值变三档），不是它对既有消费者的说法。</li>
 * </ul>
 */
public enum TicketPriority {
    URGENT_EMOTION("high", 0),
    MONEY("money", 1),
    NORMAL("normal", 2);

    /** 缺省优先级：入参为空或读不懂时的落点。宁可低标也不虚标。 */
    public static final TicketPriority DEFAULT = NORMAL;

    private final String literal;
    private final int rank;

    TicketPriority(String literal, int rank) {
        this.literal = literal;
        this.rank = rank;
    }

    public String literal() {
        return literal;
    }

    public int rank() {
        return rank;
    }

    public boolean outranks(TicketPriority other) {
        return other == null || rank < other.rank;
    }

    /** 取两档里更靠前的那档；规则表只能把工单「抬高」，不能把情绪升级或资金动作压下去。 */
    public static TicketPriority highest(TicketPriority a, TicketPriority b) {
        if (a == null) {
            return b == null ? DEFAULT : b;
        }
        if (b == null) {
            return a;
        }
        return a.rank <= b.rank ? a : b;
    }

    public static TicketPriority of(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(priority -> priority.name().equals(normalized) || priority.literal.equalsIgnoreCase(raw.trim()))
                .findFirst()
                .orElse(DEFAULT);
    }

    public static List<String> literals() {
        return Arrays.stream(values()).map(TicketPriority::literal).toList();
    }
}