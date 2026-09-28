package com.shoppilot.gateway.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.tool.ToolName;

/**
 * 确定性话术渲染：把「上一次已经发生过的事实」说给买家，不打模型（ADR 0046 票 58）。
 *
 * <p>为什么要它：请求级幂等回放发生在模型之前（{@link IdempotencyService#lookupByClientToken}），
 * 那一刻没有模型输出可用，所以答案必须由网关自己按工具结果写出来。它同时承载「这一次没有重复执行」
 * 这件事的对外表达——回放与首次执行在买家侧必须能分辨，否则"回放"与"又办了一次"看起来一样。
 *
 * <p>解析不出结果时给一句保守的通用话，**不编具体字段**：宁可少说，也不把凭空拼的申请号说给买家。
 */
public final class ReplayReply {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 解析不出结果、或调了读类工具时的话术：宁可少说，不编字段。 */
    private static final String GENERIC = "这次请求已经处理过了，本次没有重复执行。";

    private ReplayReply() {
    }

    public static String render(ToolName tool, String resultJson) {
        if (tool == null) {
            return GENERIC;
        }
        JsonNode payload = payloadOf(resultJson);
        return switch (tool) {
            case APPLY_REFUND -> {
                String orderNo = text(payload, "orderNo");
                String refundId = text(payload, "refundId");
                String status = refundStatusText(text(payload, "status"));
                yield "这笔退款申请已经提交过了"
                        + (orderNo.isEmpty() ? "" : "（订单 " + orderNo + "）")
                        + (refundId.isEmpty() ? "" : "，申请编号 " + refundId)
                        + (status.isEmpty() ? "" : "，当前状态 " + status)
                        + "。本次没有重复提交。";
            }
            case MODIFY_DELIVERY_ADDRESS -> {
                String orderNo = text(payload, "orderId");
                yield "这个订单的收货地址已经改过了"
                        + (orderNo.isEmpty() ? "" : "（订单 " + orderNo + "）")
                        + "。本次没有重复修改。";
            }
            default -> GENERIC;
        };
    }

    /** 受理话术：资金动作已受理、等待人工审核（ADR 0047）。确定性、不打第二跳模型。 */
    private static final String PENDING_GENERIC = "您的请求已受理，正在等待人工审核。";

    /**
     * 退款审批闸门的受理话术（ADR 0047 票 59）：把"已受理但资金还没动"这件事说给买家，
     * 并**明确写出到账边界**——审核通过后到账由支付渠道处理，不在本客服承诺范围内。
     * 同一句话也是票 60 买家读回要表达的边界。
     */
    public static String pendingApproval(ToolName tool, String resultJson) {
        if (tool == null) {
            return PENDING_GENERIC;
        }
        JsonNode payload = payloadOf(resultJson);
        return switch (tool) {
            case APPLY_REFUND -> {
                String orderNo = text(payload, "orderNo");
                String refundId = text(payload, "refundId");
                yield "您的退款申请已受理"
                        + (orderNo.isEmpty() ? "" : "（订单 " + orderNo + "）")
                        + (refundId.isEmpty() ? "" : "，申请编号 " + refundId)
                        + "，正在等待人工审核。审核结果会再通知您；审核通过后到账由支付渠道处理，不在本客服承诺范围内。";
            }
            default -> PENDING_GENERIC;
        };
    }

    /**
     * 退款单状态是**内部枚举**（`Refund.status` 落库的值），不能原样说给买家：
     * `PENDING_REVIEW` 这种串对买家没有意义（黑盒 QA 实测它在回放话术里被直接念出来）。
     * 未识别的值落空串——宁可不说，也不把一个内部代号念给买家（与 GENERIC 同一条纪律）。
     */
    private static String refundStatusText(String status) {
        return switch (status) {
            case "PENDING_REVIEW" -> "待人工审核";
            case "PROCESSING" -> "已放行、资金处理中";
            case "REJECTED" -> "已被驳回";
            default -> "";
        };
    }

    private static JsonNode payloadOf(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode payload = MAPPER.readTree(resultJson).path("payload");
            return payload.isObject() ? payload : MAPPER.createObjectNode();
        } catch (Exception unparsable) {
            return MAPPER.createObjectNode();
        }
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }
}
