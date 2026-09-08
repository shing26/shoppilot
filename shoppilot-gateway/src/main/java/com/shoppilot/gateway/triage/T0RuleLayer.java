package com.shoppilot.gateway.triage;

import com.shoppilot.tool.Intent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * T0 规则层：实体正则 + 关键词表，&lt;1ms，多数流量在这一层分流完（ADR 0007）。
 *
 * <p>判定不确定时返回 empty，交给下一级；绝不"猜一个政策意图然后缓存起来"。
 * 宁可少拦 10% 流量，也不能串一次号。
 */
@Component
public class T0RuleLayer {

    private static final Pattern ORDER_NO = Pattern.compile("(?<!\\d)\\d{5,8}(?!\\d)");
    private static final Pattern TRACKING_NO = Pattern.compile("(?i)\\b(?:[A-Z]{2}\\d{10,13}|\\d{12,15})\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /**
     * 第一人称限定词：出现即说明答案取决于"这一单"的具体状态，属于动态诉求。
     *
     * <p>刻意不再要求同时出现动作动词。"我的退货为什么被拒"没有任何动作词，
     * 但它要的是自己那一单的结论，缓存一条通用政策话术回去就是串号。
     * 代价是少拦一部分流量，这正是 ADR 0003 选的取舍方向。
     */
    private static final List<String> POSSESSIVE = List.of("我的", "我买的", "我订的", "帮我", "给我", "我这单", "这单", "该单", "我这边");
    private static final List<String> LOGISTICS_WORDS = List.of("物流", "快递到哪", "到哪", "签收", "运单", "轨迹", "发货没", "发了没");
    private static final List<String> REFUND_WORDS = List.of("退款", "退钱", "返款", "退回到");
    private static final List<String> ADDRESS_WORDS = List.of("地址", "收货人", "收件地址", "改址");
    private static final List<String> ORDER_WORDS = List.of("订单", "单子", "这单");

    private static final List<String> FRESH_WORDS = List.of("生鲜", "保鲜", "烂了", "坏了", "破损", "变质", "死蟹", "理赔");
    private static final List<String> PROMO_WORDS = List.of("满减", "定金", "膨胀", "优惠券", "跨店", "凑单", "立减");
    private static final List<String> RETURN_WORDS = List.of("无理由", "退货", "换货", "退换", "七天", "7天", "退款政策", "能退吗", "可以退吗");
    private static final List<String> SHIPPING_POLICY_WORDS = List.of("发什么快递", "哪家快递", "多久发货", "几天发货", "什么时候发货", "包邮", "偏远");

    public Optional<TriageResult> classify(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        boolean hasEntity = ORDER_NO.matcher(query).find()
                || TRACKING_NO.matcher(query).find()
                || PHONE.matcher(query).find();
        boolean personalCase = containsAny(query, POSSESSIVE);

        if (hasEntity || personalCase) {
            Intent action = actionIntent(query);
            // 扫到实体或第一人称却认不出是哪个动作时，仍按业务办理处理：宁可多打模型
            return Optional.of(TriageResult.dynamic(action, "T0", hasEntity));
        }

        Intent policy = policyIntent(query);
        if (policy != null) {
            return Optional.of(TriageResult.policy(policy, "T0", 0.9d));
        }
        return Optional.empty();
    }

    private Intent actionIntent(String query) {
        if (containsAny(query, ADDRESS_WORDS)) {
            return Intent.ACTION_ADDRESS;
        }
        if (containsAny(query, REFUND_WORDS)) {
            return Intent.ACTION_REFUND;
        }
        if (containsAny(query, LOGISTICS_WORDS)) {
            return Intent.ACTION_LOGISTICS;
        }
        return Intent.ACTION_ORDER;
    }

    private Intent policyIntent(String query) {
        if (containsAny(query, FRESH_WORDS)) {
            return Intent.POLICY_FRESH;
        }
        if (containsAny(query, PROMO_WORDS)) {
            return Intent.POLICY_PROMO;
        }
        if (containsAny(query, RETURN_WORDS)) {
            return Intent.POLICY_RETURN;
        }
        if (containsAny(query, SHIPPING_POLICY_WORDS)) {
            return Intent.POLICY_SHIPPING;
        }
        return null;
    }

    private static boolean containsAny(String text, List<String> needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
