package com.shoppilot.gateway.agent;

import java.util.List;

/**
 * 票 233：写意图在文字纠偏后仍未派发写工具时，判断模型正文是在追问缺失参数，
 * 还是在冒充“已经办成”。
 *
 * <p>纯函数，不读状态机事实。{@code expectedWriteDone} 仍是唯一“办成了”的判据；
 * 本类只决定正文能不能作为正常答案返回。
 */
final class WriteClaimGuard {

    enum Verdict {
        /** 既没追问，也没声称办成：例如如实说明办不了。 */
        NONE,
        /** 在追问缺失参数：合法，原样返回。 */
        ASK,
        /** 声称已经办理 / 正在办理：没有工具事实时不得透出。 */
        CLAIM
    }

    /**
     * 追问标记。不要把“订单号”“收货地址”这类名词单独当标记——完成声明里也会出现，
     * 例如“已为您修改收货地址”。这里只认明确的提问形态。
     */
    private static final List<String> ASK_MARKERS = List.of(
            "？", "?",
            "请提供", "请告知", "请确认", "请补充", "请说明", "请回复", "请告诉我",
            "麻烦提供", "麻烦您提供",
            "需要您提供", "需要您确认", "需要您补充",
            "能否提供", "可以告诉我",
            "哪一笔", "哪一单",
            "订单号是", "收货地址是", "手机号是",
            "您要退", "您想退", "吗？", "吗?"
    );

    /**
     * 如实拒绝 / 能力边界。先于完成声明判定，避免把“无法为您发起退款”当成谎报。
     */
    private static final List<String> REFUSAL_MARKERS = List.of(
            "无法为您", "不能为您", "无法直接", "不能直接",
            "无法发起", "无法提交", "无法办理", "无法处理", "无法退款",
            "不能办理", "不能发起", "不能提交", "不支持", "办不了"
    );

    /**
     * 完成 / 进行中的动作声明。只收写动作相关措辞，不收“已为您查询”这类读动作，
     * 避免把正常的查询回答误判成谎报。
     */
    private static final List<String> CLAIM_MARKERS = List.of(
            "已为您发起", "已为您提交", "已为您申请", "已为您办理", "已为您处理",
            "已帮您发起", "已帮您提交", "已帮您申请", "已帮您办理", "已帮您处理",
            "已替您发起", "已替您提交", "已替您申请", "已替您办理", "已替您处理",
            "已经为您发起", "已经为您提交", "已经为您申请", "已经为您办理", "已经为您处理",
            "已发起退款", "已提交退款", "已申请退款", "已办理退款", "已处理退款",
            "退款申请已", "退款已", "地址已", "订单已取消", "已取消订单",
            "已改好", "改好了", "已修改", "已更改",
            "已收到您的退款申请", "已收到您的申请",
            "正在为您提交", "正在为您申请", "正在为您办理", "正在为您处理",
            "正在为您退款", "正在为您修改", "正在为您更改",
            "这就为您提交", "这就为您申请", "这就为您办理", "这就为您处理",
            "马上为您提交", "马上为您申请", "马上为您办理", "马上为您处理",
            "为您发起退款", "为您提交退款", "为您申请退款", "为您办理退款", "为您处理退款",
            "帮您发起退款", "帮您提交退款", "帮您申请退款", "帮您办理退款",
            "我将为您发起", "我将为您提交", "我将为您申请", "我将为您办理", "我将为您处理",
            "我帮您发起", "我帮您提交", "我帮您申请", "我帮您办理", "我帮您处理",
            "我给您发起", "我给您提交", "我给您申请", "我给您办理", "我给您处理",
            "将为您发起", "将为您提交", "将为您申请", "将为您办理", "将为您处理",
            "已成功提交", "已成功办理", "已成功退款", "已原路退回"
    );

    private WriteClaimGuard() {
    }

    static Verdict classify(String content) {
        if (content == null || content.isBlank()) {
            return Verdict.NONE;
        }
        String text = content.strip();
        // 追问优先：同一个回复里既说“已收到请求”又在要订单号时，按合法追问返回。
        if (containsAny(text, ASK_MARKERS)) {
            return Verdict.ASK;
        }
        if (containsAny(text, REFUSAL_MARKERS)) {
            return Verdict.NONE;
        }
        if (containsAny(text, CLAIM_MARKERS)) {
            return Verdict.CLAIM;
        }
        return Verdict.NONE;
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
