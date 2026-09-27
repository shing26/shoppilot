package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.llm.LlmTypes;
import com.shoppilot.tool.ToolName;
import com.shoppilot.tool.view.ToolStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR 0047 决策九：受理态（{@code PENDING_APPROVAL}）必须与 {@code OK} 一样落成幂等结果。
 *
 * <p>不落这行，同 token 的第二次请求会落到 biz-mock 的 {@code IDEMPOTENT_REPLAY} 而不经网关的
 * {@code duplicate} 分支，{@code duplicate_submit} 事件消失、{@code verify-idempotency.ps1} 转红。
 */
class ToolDispatcherApprovalTest {

    @AfterEach
    void clearIdentity() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("受理态视为首次执行成功：写幂等结果，不当业务拒绝放弃")
    void pendingApprovalIsCompletedAsFirstResult() {
        TenantContext.set(new TenantContext.Identity("T001", "C001", "conv-approval"));
        IdempotencyService idempotency = mock(IdempotencyService.class);
        IdempotencyService.Guard guard =
                new IdempotencyService.Guard(false, false, null, "tok", null, null, () -> {
                });
        when(idempotency.begin(anyString(), anyString(), any(ToolName.class), anyMap(), any(), any()))
                .thenReturn(guard);

        BizMockClient bizMock = mock(BizMockClient.class);
        when(bizMock.call(eq(ToolName.APPLY_REFUND), anyMap(), eq("tok")))
                .thenReturn(new BizMockClient.Outcome(ToolStatus.PENDING_APPROVAL,
                        "{\"status\":\"PENDING_APPROVAL\",\"payload\":{\"refundId\":\"RF-1\"}}", false));
        ToolDispatcher dispatcher = new ToolDispatcher(bizMock, idempotency, new SimpleMeterRegistry());

        LlmTypes.ToolCall call = new LlmTypes.ToolCall("c1", ToolName.APPLY_REFUND.apiName(),
                Map.of("orderNo", "90001"));
        ToolDispatcher.Dispatch dispatch = dispatcher.dispatch(call, "tok", "订单90001申请退款", "fp");

        assertEquals(ToolStatus.PENDING_APPROVAL, dispatch.status());
        verify(idempotency).complete(eq("T001"), eq("C001"), eq(ToolName.APPLY_REFUND), eq(guard), anyString());
        verify(idempotency, never()).abandon(any(), any(), any(), any());
    }
}
