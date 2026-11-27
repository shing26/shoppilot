package com.shoppilot.gateway.agent;

import com.shoppilot.tool.ToolName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 票 233：完成声明与合法追问的 0-token 判据。
 */
class WriteClaimGuardTest {

    @Test
    @DisplayName("完成声明：已发起 / 已提交 / 已修改地址都算 CLAIM")
    void completionClaimsAreBlocked() {
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("我已为您发起退款申请"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("退款申请已提交，请放心"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已帮您把收货地址改好了"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("退款已原路退回"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("正在为您提交退款申请"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("我这就为您申请退款"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("我将为您发起退款申请"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("我帮您发起退款申请"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM,
                WriteClaimGuard.classify("退款已提交，是否到账以银行为准"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已为您完成退款"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已为您退款"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已为您取消订单"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已为您修改收货地址"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已经退款，请查收"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM,
                WriteClaimGuard.classify("已为您发起退款申请，请问还需要其他帮助吗？"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM,
                WriteClaimGuard.classify("订单号是90001，我已经为您提交退款申请"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM,
                WriteClaimGuard.classify("退款已提交，请确认到账"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM,
                WriteClaimGuard.classify("没关系，已为您发起退款申请"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM,
                WriteClaimGuard.classify("已收到您的退款申请"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM,
                WriteClaimGuard.classify("我将直接为您发起退款申请。请告诉我订单号，我来帮您处理。"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM,
                WriteClaimGuard.classify("我现在就为您提交退款申请，请告诉我订单号"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("正在帮您处理退款"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已为您安排退款"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("退款正在处理中"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已退款"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已完成退款"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已完成"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已取消"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已处理完毕"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已给您退款"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已经给您退款"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已经替您退款"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已向您退款"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已为您全额退款"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已为您原路退回"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已为您加急处理"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已提交"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已办理"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已处理"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已全额退款"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("已为您优先处理"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("操作成功"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("退款处理完成"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("退款办理完成"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("退款处理成功"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("退款申请成功"));
        assertEquals(WriteClaimGuard.Verdict.CLAIM, WriteClaimGuard.classify("退款完成"));
    }

    @Test
    @DisplayName("合法追问：要订单号 / 要确认地址 / 问哪一笔，都算 ASK")
    void missingParameterQuestionsAreAllowed() {
        assertEquals(WriteClaimGuard.Verdict.ASK, WriteClaimGuard.classify("请提供订单号"));
        assertEquals(WriteClaimGuard.Verdict.ASK, WriteClaimGuard.classify("需要您确认收货地址"));
        assertEquals(WriteClaimGuard.Verdict.ASK, WriteClaimGuard.classify("请问您要退哪一笔订单？"));
        assertEquals(WriteClaimGuard.Verdict.ASK, WriteClaimGuard.classify("订单号是多少？"));
        assertEquals(WriteClaimGuard.Verdict.ASK, WriteClaimGuard.classify("已收到您的请求，请提供订单号"));
        assertEquals(WriteClaimGuard.Verdict.ASK,
                WriteClaimGuard.classify("请提供订单号，我需要核实后为您处理"));
        assertEquals(WriteClaimGuard.Verdict.ASK,
                WriteClaimGuard.classify("请提供订单号，我这就为您提交退款申请"));
        assertEquals(WriteClaimGuard.Verdict.ASK,
                WriteClaimGuard.classify("请提供订单号，我将为您发起退款申请"));
        assertEquals(WriteClaimGuard.Verdict.ASK, WriteClaimGuard.classify("订单已提交了吗？"));
        assertEquals(WriteClaimGuard.Verdict.ASK, WriteClaimGuard.classify("已退款了吗？"));
        assertEquals(WriteClaimGuard.Verdict.NONE, WriteClaimGuard.classify("您已提交过退款申请吗？"));
        assertEquals(WriteClaimGuard.Verdict.NONE, WriteClaimGuard.classify("请确认订单已提交"));
        assertEquals(true, WriteClaimGuard.isSpuriousAsk("订单 90001 我要申请退款", ToolName.APPLY_REFUND));
        assertEquals(false, WriteClaimGuard.isSpuriousAsk("我要申请退款", ToolName.APPLY_REFUND));
        assertEquals(false,
                WriteClaimGuard.isSpuriousAsk("订单 90001 改地址", ToolName.MODIFY_DELIVERY_ADDRESS));
    }

    @Test
    @DisplayName("既没追问也没声称办成：如实说明办不了 / 只读查询，都不触发")
    void nonClaimsAreNotBlocked() {
        assertEquals(WriteClaimGuard.Verdict.NONE, WriteClaimGuard.classify("抱歉，我无法直接办理退款"));
        assertEquals(WriteClaimGuard.Verdict.NONE, WriteClaimGuard.classify("抱歉，我无法为您发起退款申请"));
        assertEquals(WriteClaimGuard.Verdict.NONE, WriteClaimGuard.classify("这个动作需要您自行在订单页操作"));
        assertEquals(WriteClaimGuard.Verdict.NONE, WriteClaimGuard.classify("已为您查询到订单 90001，当前状态是已发货"));
        assertEquals(WriteClaimGuard.Verdict.NONE, WriteClaimGuard.classify("尚未为您办理退款"));
        assertEquals(WriteClaimGuard.Verdict.NONE, WriteClaimGuard.classify("我们还没有为您办理退款"));
        assertEquals(WriteClaimGuard.Verdict.NONE, WriteClaimGuard.classify("我无法马上为您办理退款"));
        assertEquals(WriteClaimGuard.Verdict.NONE,
                WriteClaimGuard.classify("这个动作我办不了，我会为您说明退款政策"));
        assertEquals(WriteClaimGuard.Verdict.NONE,
                WriteClaimGuard.classify("抱歉，我无法为您办理退款，我会为您说明退款政策"));
        assertEquals(WriteClaimGuard.Verdict.NONE,
                WriteClaimGuard.classify("退款成功后款项会原路退回"));
        assertEquals(WriteClaimGuard.Verdict.NONE,
                WriteClaimGuard.classify("提交成功后系统会通知您"));
        assertEquals(WriteClaimGuard.Verdict.NONE,
                WriteClaimGuard.classify("如果退款成功，系统会通知您"));
        assertEquals(WriteClaimGuard.Verdict.NONE,
                WriteClaimGuard.classify("如果已为您退款，请查收"));
        assertEquals(WriteClaimGuard.Verdict.NONE,
                WriteClaimGuard.classify("已提交的退款申请会尽快处理"));
        assertEquals(WriteClaimGuard.Verdict.NONE,
                WriteClaimGuard.classify("已办理退款的订单不能重复申请"));
        assertEquals(WriteClaimGuard.Verdict.NONE,
                WriteClaimGuard.classify("订单已提交的话，请耐心等待"));
        assertEquals(WriteClaimGuard.Verdict.NONE,
                WriteClaimGuard.classify("已退款后款项会到账"));
        assertEquals(WriteClaimGuard.Verdict.NONE,
                WriteClaimGuard.classify("已发起退款后请耐心等待"));
        assertEquals(WriteClaimGuard.Verdict.NONE, WriteClaimGuard.classify(null));
        assertEquals(WriteClaimGuard.Verdict.NONE, WriteClaimGuard.classify("   "));
    }
}
