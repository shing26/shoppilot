package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.llm.LlmTypes;
import com.shoppilot.tool.ToolName;
import com.shoppilot.tool.view.ToolStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单号溯源守卫（ADR 0045 票 56）。
 *
 * <p>背景：本地 3B 模型会把工具 schema 描述里的示例值 `平台订单号，例如 10023` 直接填进
 * `orderNo`。`10023` **格式完全合法**，所以原来只查格式的守卫拦不住，工具真的带着一个没人报过的
 * 单号去撞库了——`verify-action-loop.ps1` 的第 2 段（不给单号、只问「帮我查下物流轨迹」）
 * 因此从"该追问"变成"直接派发"。
 *
 * <p>本用例钉三件事：① 格式合法但对话里没有 → 按编造拦截，转追问；② 对话里出现过 → 照常派发
 * （正对照，否则守卫很容易写成"一律拒掉"）；③ 格式非法的老行为不受影响。
 *
 * <p>「判据面只算买家说过的」那条不在这里测——它是状态机构造 `dialogueText` 时的过滤，
 * 本类拿到的已经是过滤后的文本；那条由 `GatewayMainPathJvmTest` 端到端钉住。
 */
class ToolDispatcherOrderNoProvenanceTest {

    private static final String ORDER_NO = "10023";
    private static final String ASK = "帮我查下物流轨迹";

    @Test
    @DisplayName("格式非法：按编造拦截（原有行为不受影响）")
    void malformedOrderNoIsStillBlocked() {
        Fixture fixture = fixture();

        ToolDispatcher.Dispatch dispatch = fixture.dispatcher()
                .dispatch(call("abc"), null, ASK);

        assertThat(dispatch.fabricated()).isTrue();
        assertThat(dispatch.missingSlots()).containsExactly("orderNo");
        verify(fixture.bizMock(), never()).call(any(), anyMap(), any());
    }

    @Test
    @DisplayName("格式合法但买家没说过：按编造拦截，转追问而不是派发（票 56 要修的那条）")
    void formatValidButAbsentFromDialogueIsBlocked() {
        Fixture fixture = fixture();

        ToolDispatcher.Dispatch dispatch = fixture.dispatcher()
                .dispatch(call(ORDER_NO), null, ASK);

        assertThat(dispatch.fabricated())
                .as("10023 格式合法，但买家只问了「%s」——它只能来自 schema 示例值", ASK)
                .isTrue();
        assertThat(dispatch.missingSlots()).containsExactly("orderNo");
        verify(fixture.bizMock(), never()).call(any(), anyMap(), any());
    }

    @Test
    @DisplayName("正对照：订单号出自买家的问句 → 照常派发（守卫不是「一律拒掉」）")
    void orderNoFromTheCurrentQueryIsTrusted() {
        Fixture fixture = fixture();

        ToolDispatcher.Dispatch dispatch = fixture.dispatcher()
                .dispatch(call(ORDER_NO), null, "帮我查下订单 " + ORDER_NO + " 的物流");

        assertThat(dispatch.fabricated()).isFalse();
        assertThat(dispatch.status()).isEqualTo(ToolStatus.OK);
        verify(fixture.bizMock()).call(any(), anyMap(), any());
    }

    @Test
    @DisplayName("历史买家轮次里出现过也算可信（多轮对话里补过一次单号，后面不必再报）")
    void orderNoFromAnEarlierUserTurnIsTrusted() {
        Fixture fixture = fixture();

        ToolDispatcher.Dispatch dispatch = fixture.dispatcher()
                .dispatch(call(ORDER_NO), null, "刚才那单呢\n订单号是 " + ORDER_NO);

        assertThat(dispatch.fabricated()).isFalse();
        verify(fixture.bizMock()).call(any(), anyMap(), any());
    }

    private LlmTypes.ToolCall call(String orderNo) {
        return new LlmTypes.ToolCall("call-1", ToolName.QUERY_LOGISTICS.apiName(), Map.of("orderNo", orderNo));
    }

    private Fixture fixture() {
        BizMockClient bizMock = mock(BizMockClient.class);
        when(bizMock.call(any(), anyMap(), any()))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.OK, "{\"status\":\"OK\"}", false));
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, mock(IdempotencyService.class),
                new SimpleMeterRegistry());
        return new Fixture(dispatcher, bizMock);
    }

    private record Fixture(ToolDispatcher dispatcher, BizMockClient bizMock) {
    }
}
