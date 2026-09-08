package com.shoppilot.tool;

import com.shoppilot.tool.request.ApplyRefundRequest;
import com.shoppilot.tool.request.ModifyDeliveryAddressRequest;
import com.shoppilot.tool.request.QueryLogisticsRequest;
import com.shoppilot.tool.request.QueryOrderDetailRequest;
import com.shoppilot.tool.schema.ToolSchemaGenerator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具契约登记表：网关下发给模型的 tools 数组与调用 biz-mock 的签名都从这里来。
 */
public final class ToolContracts {

    private static final Map<ToolName, Class<?>> REQUEST_TYPES = Map.of(
            ToolName.QUERY_ORDER_DETAIL, QueryOrderDetailRequest.class,
            ToolName.QUERY_LOGISTICS, QueryLogisticsRequest.class,
            ToolName.MODIFY_DELIVERY_ADDRESS, ModifyDeliveryAddressRequest.class,
            ToolName.APPLY_REFUND, ApplyRefundRequest.class);

    private ToolContracts() {
    }

    public static Class<?> requestType(ToolName tool) {
        return REQUEST_TYPES.get(tool);
    }

    /** 全量 tools 描述，随每次模型请求下发（ADR 0007：不为分类单开一次请求）。 */
    public static List<Map<String, Object>> functionDescriptors() {
        List<Map<String, Object>> descriptors = new java.util.ArrayList<>();
        for (ToolName tool : ToolName.values()) {
            descriptors.add(ToolSchemaGenerator.functionDescriptor(tool, requestType(tool)));
        }
        return descriptors;
    }

    /** 按意图裁剪工具集：政策咨询链路不下发任何工具。 */
    public static List<Map<String, Object>> functionDescriptorsFor(com.shoppilot.tool.Intent intent) {
        List<Map<String, Object>> descriptors = new java.util.ArrayList<>();
        if (intent == null || !intent.isAction()) {
            return descriptors;
        }
        for (ToolName tool : ToolName.values()) {
            if (tool.intent() == intent) {
                descriptors.add(ToolSchemaGenerator.functionDescriptor(tool, requestType(tool)));
            }
        }
        return descriptors;
    }

    public static Map<String, Object> emptyArgs() {
        return new LinkedHashMap<>();
    }
}
