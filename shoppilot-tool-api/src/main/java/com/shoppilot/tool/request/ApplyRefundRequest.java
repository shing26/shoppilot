package com.shoppilot.tool.request;

import com.shoppilot.tool.schema.ToolParam;

public record ApplyRefundRequest(
        @ToolParam(description = "平台订单号，例如 10023") String orderNo,
        @ToolParam(description = "退款原因，例如 生鲜破损、不想要了") String reason,
        @ToolParam(description = "退款金额（分）；留空表示按订单实付全额退款", required = false) Long amountFen) {
}
