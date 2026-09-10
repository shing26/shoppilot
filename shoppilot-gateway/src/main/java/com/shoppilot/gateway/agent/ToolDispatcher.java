package com.shoppilot.gateway.agent;

import com.shoppilot.gateway.llm.LlmTypes;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.tool.ToolContracts;
import com.shoppilot.tool.ToolName;
import com.shoppilot.tool.schema.ToolSchemaGenerator;
import com.shoppilot.tool.view.ToolStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具执行前的槽位校验与调用分发。
 *
 * <p>缺必填槽位一律交回状态机追问，绝不猜（猜订单号等于拿别人的订单）。
 */
@Component
public class ToolDispatcher {

    private final BizMockClient bizMockClient;
    private final IdempotencyService idempotency;

    public ToolDispatcher(BizMockClient bizMockClient, IdempotencyService idempotency) {
        this.bizMockClient = bizMockClient;
        this.idempotency = idempotency;
    }

    /** 订单号格式与 biz-mock 的造数一致；模型凭空编一个长串时按编造处理，不发起查询。 */
    private static final java.util.regex.Pattern ORDER_NO = java.util.regex.Pattern.compile("\\d{1,12}");

    /**
     * @param fabricated 模型给出的订单号格式非法，等同于凭空编造，不允许发起查询
     */
    public record Dispatch(ToolName tool, ToolStatus status, String json, List<String> missingSlots, String label,
                           boolean fabricated, boolean duplicate) {

        public boolean needsSlot() {
            return missingSlots != null && !missingSlots.isEmpty();
        }

        public boolean degraded() {
            return status == ToolStatus.TIMEOUT || status == ToolStatus.UNAVAILABLE;
        }

        /** 模型编出了不存在的工具名：不能拿 null 工具往下走，直接兜底。 */
        public boolean unknown() {
            return tool == null;
        }
    }

    public Dispatch dispatch(LlmTypes.ToolCall call, String idempotencyToken) {
        ToolName tool = ToolName.fromApiName(call.name());
        if (tool == null) {
            return new Dispatch(null, ToolStatus.UNAVAILABLE,
                    "{\"status\":\"UNAVAILABLE\",\"message\":\"未知工具 " + call.name() + "\"}", List.of(), null,
                    false, false);
        }
        Map<String, Object> arguments = call.arguments() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(call.arguments());
        List<String> missing = missingSlots(tool, arguments);
        if (!missing.isEmpty()) {
            return new Dispatch(tool, null, null, missing, label(tool, arguments), false, false);
        }
        if (isFabricatedOrderNo(arguments.get("orderNo"))) {
            // 宁可回一句"请提供正确订单号"，也不能拿一个编造的单号去撞库：那是越权探测的入口
            return new Dispatch(tool, null, null, List.of("orderNo"), null, true, false);
        }
        if (IdempotencyService.isWrite(tool)) {
            return executeWrite(tool, arguments, idempotencyToken);
        }
        BizMockClient.Outcome outcome = bizMockClient.call(tool, arguments,
                null);
        return new Dispatch(tool, outcome.status(), outcome.json(), List.of(), label(tool, arguments), false, false);
    }

    /** 写操作：先过幂等与业务锁，只有执行成功的结果才落成幂等结果。 */
    private Dispatch executeWrite(ToolName tool, Map<String, Object> arguments, String clientToken) {
        String tenantId = TenantContext.tenantId();
        String customerId = TenantContext.customerId();
        IdempotencyService.Guard guard = idempotency.begin(tenantId, customerId, tool, arguments, clientToken);
        if (guard.duplicate()) {
            return new Dispatch(tool, ToolStatus.IDEMPOTENT_REPLAY, guard.cachedJson(), List.of(),
                    label(tool, arguments), false, true);
        }
        if (guard.lockBusy()) {
            return new Dispatch(tool, ToolStatus.UNAVAILABLE,
                    "{\"status\":\"UNAVAILABLE\",\"message\":\"该订单正在处理中，请稍后重试\"}",
                    List.of(), null, false, false);
        }
        try {
            BizMockClient.Outcome outcome = bizMockClient.call(tool, arguments, guard.token());
            if (outcome.status() == ToolStatus.OK) {
                idempotency.complete(tenantId, customerId, tool, guard, outcome.json());
            } else {
                // 业务拒绝不能固化：状态改好了以后用户有权再试一次
                idempotency.abandon(tenantId, customerId, tool, guard);
            }
            return new Dispatch(tool, outcome.status(), outcome.json(), List.of(), label(tool, arguments),
                    false, false);
        } catch (RuntimeException failure) {
            idempotency.abandon(tenantId, customerId, tool, guard);
            throw failure;
        } finally {
            guard.release();
        }
    }

    private static boolean isFabricatedOrderNo(Object orderNo) {
        return orderNo != null && !ORDER_NO.matcher(String.valueOf(orderNo).trim()).matches();
    }

    public List<String> missingSlots(ToolName tool, Map<String, Object> arguments) {
        List<String> missing = new ArrayList<>();
        for (String param : ToolSchemaGenerator.requiredParams(ToolContracts.requestType(tool))) {
            Object value = arguments.get(param);
            if (value == null || String.valueOf(value).isBlank()) {
                missing.add(param);
            }
        }
        return missing;
    }

    /** 追问文案：只问缺的那一个，不重复问已给的。金额与退款原因、地址四段都是可选项，不会走到追问。 */
    public String question(ToolName tool, List<String> missingSlots) {
        if (missingSlots.contains("orderNo")) {
            return "请提供您的订单号（例如 10023），我需要它才能为您查询或办理。";
        }
        if (tool == ToolName.MODIFY_DELIVERY_ADDRESS) {
            return "还需要您补充收件信息：" + String.join("、", missingSlots) + "。";
        }
        return "还需要您补充：" + String.join("、", missingSlots) + "。";
    }

    private String label(ToolName tool, Map<String, Object> arguments) {
        String orderNo = String.valueOf(arguments.getOrDefault("orderNo", ""));
        return switch (tool) {
            case QUERY_ORDER_DETAIL -> "正在查询订单 " + orderNo + " 的状态...";
            case QUERY_LOGISTICS -> "正在查询订单 " + orderNo + " 的实时物流轨迹...";
            case MODIFY_DELIVERY_ADDRESS -> "正在为您修改订单 " + orderNo + " 的收货地址...";
            case APPLY_REFUND -> "正在为订单 " + orderNo + " 提交退款申请...";
        };
    }

}
