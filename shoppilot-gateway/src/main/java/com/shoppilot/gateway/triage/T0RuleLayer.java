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

    /**
     * 显式转人工词表。
     *
     * <p>这类诉求必须在最便宜的规则层定案，不能依赖向量检索。理由不是省钱：
     * 转人工是可用性兜底，而 T1 质心层依赖 embedding 服务，大促期间它一旦超时，
     * 判定会按 fail-closed 降级成"不确定"并交给模型定案（2026-09-09 压测后
     * 验收脚本第 7 条就是这样失败的——用户明确喊转人工，却先花了 25 秒等大模型）。
     * "人要不要来"这件事不该由一个会抖的外部依赖决定。
     */
    private static final List<String> ESCALATE_WORDS = List.of("转人工", "转个人", "人工客服", "真人客服", "人工服务", "转接人工", "人工介入");
    /** 紧邻关键词前的否定/取消词：用于"别转人工""不想转人工"这类反义提问。 */
    private static final List<String> ESCALATE_NEGATIONS = List.of("不", "别", "莫", "没", "无需", "取消", "拒绝", "退出");
    /** 否定词只看关键词前这么长的窗口，单位是字符。2 足够覆盖"不想/不要/别再/别"。 */
    private static final int ESCALATE_NEGATION_WINDOW = 2;

    public Optional<TriageResult> classify(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        if (explicitEscalation(query)) {
            // 先于实体与第一人称判定："90001 这单搞错了，转人工" 要的是人，不是订单查询
            return Optional.of(TriageResult.dynamic(Intent.ESCALATE, "T0", false));
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
        // 动作意图内部优先级：改址 > 退款 > 物流 > 订单（"改地址并退款"要的是改址）
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

    /**
     * 是否有动作动词证据（改址 / 退款 / 物流 / 订单）。
     *
     * <p>供 T1 决定候选意图集：T0 未定案但句子里带动作动词时，质心层不允许把这句判成政策咨询，
     * 因为政策意图会进缓存，而"我要退款"这类无实体动作诉求的正确出口是追问槽位。
     */
    public boolean hasActionVerb(String query) {
        return query != null && (containsAny(query, ADDRESS_WORDS) || containsAny(query, REFUND_WORDS)
                || containsAny(query, LOGISTICS_WORDS) || containsAny(query, ORDER_WORDS));
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

    /**
     * 是否显式要求人工：命中词表且紧邻窗口内没有否定词。
     *
     * <p>整句判否定会漏掉"这个不合规，转人工"，而漏判的代价是用户喊了人工却没人来；
     * 只看紧邻窗口则两头都保住。
     *
     * <p>第二个调用方是 {@code AgentStateMachine}（经 {@link TriageEngine#isExplicitEscalation}）：
     * 情绪门的短路要用它判优先级（ADR 0042）——显式转人工不按情绪处理，否则 ADR 0034 会回归掉
     * ADR 0017 的承诺。纯谓词、无状态，故公开。
     */
    public static boolean explicitEscalation(String query) {
        for (String needle : ESCALATE_WORDS) {
            int from = 0;
            int idx;
            while ((idx = query.indexOf(needle, from)) >= 0) {
                if (!negatedEscalation(query, idx)) {
                    return true;
                }
                from = idx + needle.length();
            }
        }
        return false;
    }

    private static boolean negatedEscalation(String query, int keywordStart) {
        int from = Math.max(0, keywordStart - ESCALATE_NEGATION_WINDOW);
        String prefix = query.substring(from, keywordStart);
        for (String negation : ESCALATE_NEGATIONS) {
            if (prefix.contains(negation)) {
                return true;
            }
        }
        return false;
    }
}
