package com.shoppilot.gateway.agent;

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
        assertEquals(WriteClaimGuard.Verdict.NONE, WriteClaimGuard.classify(null));
        assertEquals(WriteClaimGuard.Verdict.NONE, WriteClaimGuard.classify("   "));
    }
}
