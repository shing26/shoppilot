package com.shoppilot.gateway.triage;

import com.shoppilot.gateway.knowledge.Domain;
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

    /**
     * 生鲜词表只收**行业对象词**（round33 / ADR 0067）。
     *
     * <p>旧表里的"坏了 / 破损 / 变质 / 理赔"是任何商品都能套的泛词，实机复现过"手机坏了怎么赔"被判成
     * {@code POLICY_FRESH}。收窄的代价照登：不带对象词的生鲜问句（例如"理赔审核要多久"）不再由 T0
     * 定案，改由 T1 质心层兜——这正是"宁可少拦 10% 流量，也不串一次号"的既定取舍（ADR 0007）。
     */
    private static final List<String> FRESH_WORDS = List.of(
            "生鲜", "水果", "蔬菜", "水产", "海鲜", "活鲜", "大闸蟹", "死蟹", "蟹", "保鲜", "腐烂", "烂了");
    private static final List<String> PROMO_WORDS = List.of("满减", "定金", "膨胀", "优惠券", "跨店", "凑单", "立减");
    private static final List<String> RETURN_WORDS = List.of("无理由", "退货", "换货", "退换", "七天", "7天", "退款政策", "能退吗", "可以退吗");
    private static final List<String> SHIPPING_POLICY_WORDS = List.of("发什么快递", "哪家快递", "多久发货", "几天发货", "什么时候发货", "包邮", "偏远");

    /**
     * 酒店域词表（round32）。它是**另一个行业**的词汇，与电商四条政策词表零重叠——
     * 这也正是它为什么是「换知识库就换场景」的合格证据：同一套代码，换一套语料与一份词表。
     */
    private static final List<String> HOTEL_WORDS = List.of(
            "入住", "退房", "退订", "房型", "大床房", "双床", "加床", "延时退房", "延迟退房",
            "钟点房", "押金", "前台", "免费取消", "取消政策", "提前退房", "续住", "房费",
            "含早");

    /**
     * 早餐问句（round33 修 HTL-BKF-01）：把「早餐」收进酒店域，但用负向断言排除「早餐机」这类家电词。
     *
     * <p>旧表直接列「酒店早餐 / 早餐时间」，导致「早餐是几点到几点」这种**不带前缀**的时间问句掉出 T0
     * （活体跑批 HTL-BKF-01 实测判成 {@code UNKNOWN}）；而 round33 收紧词表又要挡住「早餐机坏了怎么赔」。
     * 负向断言同时满足两头：餐饮语境的「早餐」命中，家电名里的「早餐机」不命中。
     */
    private static final Pattern HOTEL_BREAKFAST = Pattern.compile("早餐(?!机)");

    /**
     * 显式办理动作短语（round33 / ADR 0067）：句子里直接说了"我要办这件事"，动作优先于政策。
     *
     * <p>刻意写成**带第一人称意愿的完整短语**而不是"退款 / 改地址"这类名词：后者会把
     * "退款政策是什么""申请退款要多久"这类纯咨询也判成动作，等于把政策问题拖进工具循环。
     * 这条修的是 D4——"生鲜坏了我要退款"既没有实体也没有"我的"，旧实现直接落到政策词表。
     */
    private static final List<String> EXPLICIT_ACTION_PHRASES = List.of(
            "我要退款", "我要退钱", "我想退款", "帮我退款", "帮我退钱", "给我退款",
            "我要申请退款", "帮我申请退款", "我要办理退款", "帮我办退款",
            "我要改地址", "帮我改地址", "我要改收货地址", "帮我改收货地址",
            "我要查订单", "帮我查订单", "我要查物流", "帮我查物流");

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

    /** 默认按电商口径判定：给不关心行业的调用方（测试、离线脚本）保持旧行为。 */
    public Optional<TriageResult> classify(String query) {
        return classify(query, Domain.ECOMMERCE);
    }

    /**
     * 行业感知的 T0 判定（round33 / ADR 0067）。顺序：
     * 显式转人工 → 行业门 → 实体/第一人称动作 → 显式办理动作 → 酒店词表 → 电商政策词表。
     */
    public Optional<TriageResult> classify(String query, Domain domain) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        if (explicitEscalation(query)) {
            // 先于实体与第一人称判定："90001 这单搞错了，转人工" 要的是人，不是订单查询
            return Optional.of(TriageResult.dynamic(Intent.ESCALATE, "T0", false));
        }
        if (domain == Domain.HOTEL) {
            return classifyHotel(query);
        }
        return classifyEcommerce(query);
    }

    /**
     * 酒店行业门（round33 / ADR 0067）：**纯咨询域**。
     *
     * <p>酒店租户只认酒店词表：电商的政策词表与动作词表在这里一律不成立，判定结果要么是
     * {@code POLICY_HOTEL}，要么交给下一级（T1 或带零工具的模型）。这一格是 round32 那版
     * "把酒店词表硬插在第一人称判定之前"的替代品——旧写法只保护了"带酒店词"的句子，
     * 而"我要取消订单"这类**不带酒店词**的办理类诉求照样会走到 {@code ACTION_REFUND}，
     * 再被挂上全套电商工具。
     */
    private Optional<TriageResult> classifyHotel(String query) {
        if (containsHotelWord(query)) {
            return Optional.of(TriageResult.policy(Intent.POLICY_HOTEL, "T0", 0.9d));
        }
        return Optional.empty();
    }

    private Optional<TriageResult> classifyEcommerce(String query) {
        boolean hasEntity = ORDER_NO.matcher(query).find()
                || TRACKING_NO.matcher(query).find()
                || PHONE.matcher(query).find();
        boolean personalCase = containsAny(query, POSSESSIVE);

        if (hasEntity || personalCase) {
            Intent action = actionIntent(query);
            // 扫到实体或第一人称却认不出是哪个动作时，仍按业务办理处理：宁可多打模型
            return Optional.of(TriageResult.dynamic(action, "T0", hasEntity));
        }

        if (containsAny(query, EXPLICIT_ACTION_PHRASES)) {
            // 显式办理动作优先于政策词表（ADR 0067 第 5 条）："生鲜坏了我要退款"要的是退款，
            // 不是一条生鲜理赔条款。纯政策问法（"退款政策是什么"）不含意愿短语，仍走下面。
            return Optional.of(TriageResult.dynamic(actionIntent(query), "T0", false));
        }

        if (containsHotelWord(query)) {
            // 酒店是另一个行业的词表（round32）。电商租户问酒店问题属于越界咨询：T0 仍给
            // POLICY_HOTEL，检索侧按 domain 过滤后拿不到酒店条款，最终落到转人工——
            // 不会串成电商答案。酒店租户走的是上面那道行业门，与本分支无关。
            return Optional.of(TriageResult.policy(Intent.POLICY_HOTEL, "T0", 0.9d));
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

    /** 酒店词判定：词表 + 早餐负向断言（「早餐」命中、「早餐机」不命中）。 */
    private static boolean containsHotelWord(String query) {
        return containsAny(query, HOTEL_WORDS) || HOTEL_BREAKFAST.matcher(query).find();
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
