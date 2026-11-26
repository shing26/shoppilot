package com.shoppilot.gateway.agent;

import java.util.List;
import java.util.regex.Pattern;

import com.shoppilot.tool.ToolName;

/**
 * 票 233：写意图在文字纠偏后仍未派发写工具时，判断模型正文是在追问缺失参数、
 * 如实拒绝，还是在冒充“已经办成 / 马上办成”。
 *
 * <p>纯函数，不读状态机事实。{@code expectedWriteDone} 仍是唯一“办成了”的判据；
 * 本类只决定正文能不能作为正常答案返回。
 *
 * <p>判定顺序是刻意的：
 * <ol>
 *   <li>已完成声明优先——「已为您发起退款，请问还需要其他帮助吗？」不能因为
 *       带问号就放行；</li>
 *   <li>再认追问——「请提供订单号，我需要核实后为您处理」是合法追问，不能被
 *       里面的「为您处理」误伤；</li>
 *   <li>再认如实拒绝；</li>
 *   <li>最后才是没有追问的未来承诺（「我这就为您提交」）。</li>
 * </ol>
 */
final class WriteClaimGuard {

    enum Verdict {
        /** 既没追问，也没声称办成：例如如实说明办不了。 */
        NONE,
        /** 在追问缺失参数：合法，原样返回。 */
        ASK,
        /** 声称已经办理 / 正在办理 / 马上办理：没有工具事实时不得透出。 */
        CLAIM
    }

    /**
     * 追问标记。不要把「订单号是」「您要退」这类陈述片段单独当标记——
     * 「订单号是90001，我已经为您提交退款申请」里的「订单号是」不是提问。
     */
    private static final List<String> ASK_MARKERS = List.of(
            "？", "?",
            "请提供", "请告知", "请确认", "请补充", "请说明", "请回复", "请告诉我",
            "麻烦提供", "麻烦您提供",
            "需要您提供", "需要您确认", "需要您补充",
            "能否提供", "可以告诉我",
            "哪一笔", "哪一单",
            "吗？", "吗?"
    );

    /**
     * 如实拒绝 / 能力边界。用于排除「无法为您发起退款」这类不算谎报的正文。
     */
    private static final List<String> REFUSAL_MARKERS = List.of(
            "无法为您", "不能为您", "无法直接", "不能直接",
            "无法发起", "无法提交", "无法办理", "无法处理", "无法退款",
            "不能办理", "不能发起", "不能提交", "不支持", "办不了", "做不到",
            "尚未", "还没有", "还没", "没有为您", "未为您", "并未为您", "暂未"
    );

    /**
     * 已完成 / 正在办理的声明。只认「已 / 已经 / 正在」等完成态；
     * 不含裸「为您处理」，否则会把「我需要核实后为您处理」误判成谎报。
     */
    private static final List<String> COMPLETED_PREFIXES = List.of(
            "已为您", "已帮您", "已替您", "已经为您", "已给",
            "已给您", "已向您", "已对您", "已经给您", "已经替您", "已经向您",
            "正在为您", "正在帮您", "正在给您", "正在替您",
            "已成功为您", "已成功帮您", "已经成功为您"
    );

    /**
     * 没有追问的未来承诺。放在 ASK / REFUSAL 之后判定：
     * 「请提供订单号，我这就为您提交」仍是合法追问；
     * 「我这就为您提交退款申请」没有追问，必须拦。
     */
    private static final List<String> PROMISE_PREFIXES = List.of(
            "这就为您", "马上为您", "我将为您", "我帮您", "我给您",
            "将为您", "为您", "帮您", "我马上", "我这就"
    );

    /**
     * 带明确时态/意志的强承诺，允许「直接 / 立即 / 尽快」等副词插在中间。
     * 它比其后的追问更强：「我将直接为您发起退款……请告诉我订单号」不能被
     * 后面的「请告诉我」放行；但「请提供订单号，我这就为您提交」仍是先追问，
     * 按位置规则走 ASK。
     */
    private static final Pattern STRONG_PROMISE = Pattern.compile(
            "(我将|我会|我现在就|我这就|我马上|我立即|我立刻|这就|马上|立即|立刻)"
                    + "[^，。；！？,.;!?]{0,8}"
                    + "(发起|提交|申请|办理|处理|退款|取消|修改|更改|完成|成功)");

    /**
     * 「我会为您说明退款政策」「我会为您查询退款进度」不是办理承诺；
     * 中间出现这些词时，强承诺不算数。
     */
    private static final List<String> NON_ACTION_CONTEXT = List.of(
            "说明", "解释", "介绍", "查询", "核实", "确认", "告知",
            "政策", "规则", "流程"
    );

    private static final List<String> CLAIM_VERBS = List.of(
            "发起", "提交", "申请", "办理", "处理", "退款", "取消",
            "修改", "更改", "完成", "成功", "受理", "登记", "备注", "退回", "关闭",
            "安排", "转接", "操作", "催促", "反馈", "优先"
    );

    /**
     * 不带「为您」前缀、或动词被宾语隔开的完成声明。
     */
    private static final List<String> COMPLETED_PHRASES = List.of(
            "已发起退款", "已提交退款", "已申请退款", "已办理退款", "已处理退款",
            "退款申请已", "退款已", "地址已", "订单已取消", "已取消订单",
            "已改好", "改好了", "已修改", "已更改",
            "已成功提交", "已成功办理", "已成功退款", "已原路退回",
            "已将您的订单取消", "已将您的退款", "已进入审核", "已进入退款",
            "已受理您的", "已经退款", "已经提交", "已经办理", "已经处理",
            "已经取消", "已经修改", "已经更改", "已经完成", "已经改好",
            "退款成功", "提交成功", "办理成功", "修改成功", "取消成功", "订单取消",
            "已经在处理", "退款正在", "正在处理中", "已转人工", "已转接人工",
            "已退款", "已完成退款", "已完成", "已取消", "已处理完毕",
            "已为您全额退款", "已为您原路退回", "已为您加急处理",
            "已办好", "已退回", "已全额退款", "已加急处理", "已优先处理",
            "已为您优先处理", "已为您催促", "已为您反馈", "操作成功", "已操作",
            "退款处理完成", "退款办理完成", "退款处理成功", "退款申请成功", "退款完成"
    );

    /**
     * 裸动作词（「已提交」「已办理」）只有在句末或后接非动作宾语时才算完成声明。
     * 「已提交退款」「已办理退款的订单」由更长的短语或定语规则处理，裸词不能先命中。
     */
    private static final List<String> BARE_COMPLETIONS = List.of(
            "已提交", "已办理", "已处理", "已申请", "已发起"
    );

    private static final List<String> BARE_OBJECT_PREFIXES = List.of(
            "退款", "申请", "提交", "办理", "处理", "取消", "修改", "更改",
            "安排", "转接", "操作", "催促", "反馈", "优先"
    );

    /**
     * 弱确认：只承认收到请求，没有可核对的业务事实。单独出现时仍然是谎报，
     * 但与「请提供订单号」同句时按追问放行。
     */
    private static final List<String> ACK_MARKERS = List.of(
            "已收到您的退款申请", "已收到您的申请", "已收到您的请求"
    );

    /**
     * 否定词必须紧贴在声明前（允许少量非标点字符），避免「没关系，已为您发起」
     * 被「没」误判成否定。
     */
    private static final Pattern NEGATED_BEFORE = Pattern.compile(
            "(尚未|还没有|还没|并未|没有|未曾|无法|不能|不支持|办不了|做不到|未|没)"
                    + "[^，。；！？,.;!?]{0,4}$");

    private static final Pattern ORDER_NO = Pattern.compile("\\d{5,}");

    private WriteClaimGuard() {
    }

    static Verdict classify(String content) {
        if (content == null || content.isBlank()) {
            return Verdict.NONE;
        }
        String text = content.strip();
        if (hasCompletedClaim(text)) {
            return Verdict.CLAIM;
        }
        int strongPromise = earliestStrongPromise(text);
        int ask = earliestMarker(text, ASK_MARKERS);
        if (strongPromise >= 0 && (ask < 0 || strongPromise < ask)) {
            return Verdict.CLAIM;
        }
        if (ask >= 0) {
            return Verdict.ASK;
        }
        if (containsAny(text, REFUSAL_MARKERS)) {
            return Verdict.NONE;
        }
        if (hasPromiseClaim(text)) {
            return Verdict.CLAIM;
        }
        if (containsAny(text, ACK_MARKERS)) {
            return Verdict.CLAIM;
        }
        return Verdict.NONE;
    }

    private static boolean hasCompletedClaim(String text) {
        return hasMarker(text, COMPLETED_PHRASES)
                || hasBareCompletion(text)
                || hasPrefixVerb(text, COMPLETED_PREFIXES);
    }

    private static boolean hasBareCompletion(String text) {
        for (String marker : BARE_COMPLETIONS) {
            int from = 0;
            int index;
            while ((index = text.indexOf(marker, from)) >= 0) {
                if (!negated(text, index) && !conditionalCompletion(text, index, marker)) {
                    String after = text.substring(index + marker.length());
                    if (BARE_OBJECT_PREFIXES.stream().noneMatch(after::startsWith)) {
                        return true;
                    }
                }
                from = index + 1;
            }
        }
        return false;
    }

    private static boolean hasPromiseClaim(String text) {
        return hasPrefixVerb(text, PROMISE_PREFIXES);
    }

    private static int earliestStrongPromise(String text) {
        var matcher = STRONG_PROMISE.matcher(text);
        int from = 0;
        while (matcher.find(from)) {
            if (!negated(text, matcher.start())) {
                String middle = text.substring(
                        matcher.start() + matcher.group(1).length(),
                        matcher.end() - matcher.group(2).length());
                if (!containsAny(middle, NON_ACTION_CONTEXT)) {
                    return matcher.start();
                }
            }
            from = matcher.start() + 1;
        }
        return -1;
    }

    private static int earliestMarker(String text, List<String> markers) {
        int earliest = -1;
        for (String marker : markers) {
            int index = text.indexOf(marker);
            if (index >= 0 && (earliest < 0 || index < earliest)) {
                earliest = index;
            }
        }
        return earliest;
    }

    private static boolean hasMarker(String text, List<String> markers) {
        for (String marker : markers) {
            int from = 0;
            int index;
            while ((index = text.indexOf(marker, from)) >= 0) {
                if (!negated(text, index) && !conditionalCompletion(text, index, marker)) {
                    return true;
                }
                from = index + 1;
            }
        }
        return false;
    }

    private static boolean hasPrefixVerb(String text, List<String> prefixes) {
        for (String prefix : prefixes) {
            for (String verb : CLAIM_VERBS) {
                int from = 0;
                int index;
                while ((index = text.indexOf(prefix + verb, from)) >= 0) {
                    if (!negated(text, index)
                            && !conditionalCompletion(text, index, prefix + verb)) {
                        return true;
                    }
                    from = index + 1;
                }
            }
        }
        return false;
    }

    private static boolean negated(String text, int index) {
        String before = text.substring(Math.max(0, index - 8), index);
        return NEGATED_BEFORE.matcher(before).find();
    }

    private static boolean conditionalCompletion(String text, int index, String marker) {
        String before = text.substring(Math.max(0, index - 4), index);
        if (before.contains("如果") || before.contains("若") || before.contains("一旦")
                || before.contains("只要") || before.contains("假如") || before.contains("要是")) {
            return true;
        }
        String after = text.substring(index + marker.length());
        if (after.startsWith("的") || after.startsWith("了吗") || after.startsWith("了么")
                || after.startsWith("吗") || after.startsWith("？") || after.startsWith("?")
                || after.startsWith("的话")) {
            return true;
        }
        if (marker.endsWith("成功") || marker.endsWith("完成")) {
            return after.startsWith("后") || after.startsWith("之后")
                    || after.startsWith("即可") || after.startsWith("才能");
        }
        return false;
    }

    /**
     * 票 233：固定退款问句已经带订单号时，再追问订单号/收件人不是“缺失参数”，
     * 而是没有派发写工具。只对 `applyRefund` 生效（它唯一必填参数是 orderNo）；
     * 地址修改等其它写工具仍允许合理的补参追问。
     */
    static boolean isSpuriousAsk(String query, ToolName tool) {
        return tool == ToolName.APPLY_REFUND
                && query != null
                && ORDER_NO.matcher(query).find();
    }

    private static boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
