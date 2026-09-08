package com.shoppilot.tool;

import java.util.Arrays;

/** 四个业务动作工具，名字即下发给模型的 function name。 */
public enum ToolName {
    QUERY_ORDER_DETAIL("queryOrderDetail", Intent.ACTION_ORDER, "查询指定订单的状态、金额、品类与服务标"),
    QUERY_LOGISTICS("queryLogistics", Intent.ACTION_LOGISTICS, "查询指定订单的物流公司与实时轨迹节点"),
    MODIFY_DELIVERY_ADDRESS("modifyDeliveryAddress", Intent.ACTION_ADDRESS, "修改指定订单的收货地址，仅未发货订单允许"),
    APPLY_REFUND("applyRefund", Intent.ACTION_REFUND, "为指定订单发起退款申请");

    private final String apiName;
    private final Intent intent;
    private final String description;

    ToolName(String apiName, Intent intent, String description) {
        this.apiName = apiName;
        this.intent = intent;
        this.description = description;
    }

    public String apiName() {
        return apiName;
    }

    public Intent intent() {
        return intent;
    }

    public String description() {
        return description;
    }

    public static ToolName fromApiName(String name) {
        return Arrays.stream(values())
                .filter(t -> t.apiName.equals(name))
                .findFirst()
                .orElse(null);
    }
}
