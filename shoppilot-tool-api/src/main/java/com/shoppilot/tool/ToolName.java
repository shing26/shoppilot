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

    /**
     * 该动作是否需要人工审批（ADR 0047）。判据是「**不可逆或涉及资金**」，不是「是否写库」——
     * 所以 {@link #MODIFY_DELIVERY_ADDRESS}（写 {@code address_history}、有版本、可再改回）不入闸门，
     * 而 {@link #APPLY_REFUND} 入闸门。声明式挂在这里（与 {@link #intent()} 同层），强制点在 biz-mock
     * （状态真相的所有者）；下次给别的动作加闸门是加一行分类，不是重设计。
     *
     * <p>先例是 {@code IdempotencyService.isWrite(ToolName)} —— 但那是"是否写库"的判据，与本节不同。
     */
    public boolean requiresApproval() {
        return this == APPLY_REFUND;
    }

    public static ToolName fromApiName(String name) {
        return Arrays.stream(values())
                .filter(t -> t.apiName.equals(name))
                .findFirst()
                .orElse(null);
    }

    /** 意图到工具的反向映射：网关需要在不依赖模型发 function call 时自己派生工具。 */
    public static ToolName forIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(t -> t.intent == intent)
                .findFirst()
                .orElse(null);
    }
}
