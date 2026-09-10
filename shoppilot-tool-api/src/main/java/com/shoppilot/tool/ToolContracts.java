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

    /**
     * 业务动作工具集：四个工具一起下发，由模型自己选。
     *
     * <p>原先按定案意图裁到单个工具，dev 模式实测（DashScope qwen-plus，180 条）把 T0 的
     * 子意图误判直接放大成"选错工具"——判成 ACTION_ORDER 的改地址请求只拿到 queryOrderDetail，
     * 模型没有犯错的机会。政策意图与转人工仍然一个工具都不给（那是凭空编订单号的来源）。
     */
    public static List<Map<String, Object>> actionDescriptors() {
        List<Map<String, Object>> descriptors = new java.util.ArrayList<>();
        for (ToolName tool : ToolName.values()) {
            if (tool.intent().isAction()) {
                descriptors.add(ToolSchemaGenerator.functionDescriptor(tool, requestType(tool)));
            }
        }
        return descriptors;
    }

    public static Map<String, Object> emptyArgs() {
        return new LinkedHashMap<>();
    }
}
