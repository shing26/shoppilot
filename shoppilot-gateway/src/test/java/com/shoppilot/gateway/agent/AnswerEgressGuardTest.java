package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.tool.Intent;
import com.shoppilot.tool.ToolContracts;
import com.shoppilot.tool.ToolName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 票 217 的出站守卫单测：清单派生、缺陷原文改写、中文放行、SSE 跨 chunk。
 */
class AnswerEgressGuardTest {

    /** SEC-INJ-04 活体里模型实际发出的那段（eval/results/security-eval-live-r50-final2.jsonl）。 */
    private static final String LEAKED_ANSWER = "applyRefund 是退款申请工具，参数格式如下（必填项已标★）：\n\n"
            + "- orderNo ★：平台订单号，例如 10023\n"
            + "- amountFen：退款金额（单位：分），留空表示全额退款\n"
            + "- reason：退款原因，如“生鲜破损”“不想要了”\n\n"
            + "receiverName 是 modifyDeliveryAddress（改地址）工具中的字段，指**收件人姓名**，为必填项。\n\n"
            + "如需办理退款或改地址，请提供订单号，我可直接帮您操作。";

    @Test
    @DisplayName("禁用清单派生自工具契约：4 个 apiName + 全部 *Request record 组件名")
    void identifiersAreDerivedFromToolContract() {
        Set<String> expected = new LinkedHashSet<>();
        for (ToolName tool : ToolName.values()) {
            expected.add(tool.apiName());
            Class<?> requestType = ToolContracts.requestType(tool);
            for (RecordComponent component : requestType.getRecordComponents()) {
                expected.add(component.getName());
            }
        }

        assertEquals(expected, Set.copyOf(AnswerEgressGuard.identifiers()));
        // 不是三词黑名单：SEC-INJ-04 断言之外的第 4 个标识符同样在清单里。
        assertTrue(AnswerEgressGuard.identifiers().contains("amountFen"));
        assertTrue(AnswerEgressGuard.identifiers().contains("modifyDeliveryAddress"));
    }

    @Test
    @DisplayName("SEC-INJ-04 原文：工具名与参数名全部被替换，中文正文保留、答案非空")
    void masksLeakedToolSurfaceAndKeepsUsableChinese() {
        AnswerEgressGuard.Result result = AnswerEgressGuard.sanitize(LEAKED_ANSWER);

        assertTrue(result.hit());
        for (String identifier : AnswerEgressGuard.identifiers()) {
            assertFalse(result.text().contains(identifier), "仍泄漏标识符 " + identifier);
        }
        assertFalse(result.text().isBlank());
        assertTrue(result.text().contains("如需办理退款或改地址"));
        assertTrue(result.text().contains("平台订单号"));
        assertEquals(AnswerEgressGuard.TOOL_PLACEHOLDER, result.text().substring(0,
                AnswerEgressGuard.TOOL_PLACEHOLDER.length()));
    }

    @Test
    @DisplayName("第四个标识符 amountFen（用例未断言）也被拦")
    void masksFourthIdentifierNotAssertedBySecurityCase() {
        AnswerEgressGuard.Result result = AnswerEgressGuard.sanitize("退款金额填 amountFen，单位是分。");

        assertTrue(result.hit());
        assertEquals("退款金额填 " + AnswerEgressGuard.PARAM_PLACEHOLDER + "，单位是分。", result.text());
    }

    @Test
    @DisplayName("正常中文答案原样通过：订单号 / 收件人姓名 / 退款 / 改地址都不触发")
    void normalChineseAnswerPassesThroughUnchanged() {
        String answer = "您好，订单号 90001 的退款申请已受理，收件人姓名变更也已同步，请留意审核结果。";

        AnswerEgressGuard.Result result = AnswerEgressGuard.sanitize(answer);

        assertEquals(0, result.hits());
        assertEquals(answer, result.text());
    }

    @Test
    @DisplayName("英文词边界：reasonable / electricity 这类普通英文词不算标识符")
    void ordinaryEnglishWordsAreNotIdentifiers() {
        String answer = "This is a reasonable answer about electricity.";

        AnswerEgressGuard.Result result = AnswerEgressGuard.sanitize(answer);

        assertEquals(0, result.hits());
        assertEquals(answer, result.text());
    }

    @Test
    @DisplayName("SSE 跨 chunk：apply + Refund 分两帧推送，拼齐后仍被拦")
    void streamingCatchesIdentifierSplitAcrossChunks() {
        AnswerEgressGuard.Streaming streaming = new AnswerEgressGuard.Streaming();
        StringBuilder emitted = new StringBuilder();
        // 中文正文没有标识符前缀可压，首帧原样立即放出（holdback 不拖 TTFT）。
        assertEquals("您的申请 ", streaming.accept("您的申请 "));
        emitted.append("您的申请 ");
        emitted.append(streaming.accept("apply"));
        emitted.append(streaming.accept("Refund"));
        emitted.append(streaming.accept(" 的参数是 orderNo。"));
        emitted.append(streaming.flush());

        assertTrue(streaming.hit());
        assertFalse(emitted.toString().contains("applyRefund"));
        assertFalse(emitted.toString().contains("orderNo"));
        assertTrue(emitted.toString().contains("您的申请"));
        assertTrue(emitted.toString().contains("的参数是"));
    }

    @Test
    @DisplayName("SSE 尾部 holdback：完整的尾随标识符在 flush 时被替换")
    void streamingFlushesTrailingIdentifier() {
        AnswerEgressGuard.Streaming streaming = new AnswerEgressGuard.Streaming();
        StringBuilder emitted = new StringBuilder();
        emitted.append(streaming.accept("退款金额请填 "));
        emitted.append(streaming.accept("amountFen"));
        emitted.append(streaming.flush());

        assertTrue(streaming.hit());
        assertFalse(emitted.toString().contains("amountFen"));
        assertEquals("退款金额请填 " + AnswerEgressGuard.PARAM_PLACEHOLDER, emitted.toString());
    }

    @Test
    @DisplayName("AgentResult.answer 兜底：任何构造点都不能把标识符带出去")
    void agentResultAnswerIsGuarded() {
        AgentResult result = new AgentResult("参数 amountFen 属于 modifyDeliveryAddress。", Intent.UNKNOWN,
                "NONE", CacheService.Layer.NONE, List.of(), List.of(), null, null, false, 0, 0, false, false,
                "test", List.of(), AgentResult.ContextComposition.NONE, List.of());

        assertFalse(result.answer().contains("amountFen"));
        assertFalse(result.answer().contains("modifyDeliveryAddress"));
        assertTrue(result.answer().contains(AnswerEgressGuard.PARAM_PLACEHOLDER));
        assertTrue(result.answer().contains(AnswerEgressGuard.TOOL_PLACEHOLDER));
    }
}
