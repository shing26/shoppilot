package com.shoppilot.gateway.knowledge;

import java.util.Locale;

/**
 * 租户所属行业（round33 / ADR 0067）。
 *
 * <p>它决定平台级政策是否可见，以及缓存平台桶如何分区。未登记租户 fail-closed 为
 * {@link #UNKNOWN}：只看得到本租户自己的规则，不自动继承电商平台政策。
 */
public enum Domain {
    ECOMMERCE,
    HOTEL,
    UNKNOWN;

    public static Domain from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return UNKNOWN;
        }
    }

    /** 入库时没有显式 domain 时的意图推断；只用于语料缺省，不替代租户登记。 */
    public static Domain ofIntent(String intent) {
        if (intent == null || intent.isBlank()) {
            return UNKNOWN;
        }
        if ("POLICY_HOTEL".equals(intent)) {
            return HOTEL;
        }
        if (intent.startsWith("POLICY_") || intent.startsWith("ACTION_")) {
            return ECOMMERCE;
        }
        return UNKNOWN;
    }

    public boolean platformVisible() {
        return this != UNKNOWN;
    }
}
