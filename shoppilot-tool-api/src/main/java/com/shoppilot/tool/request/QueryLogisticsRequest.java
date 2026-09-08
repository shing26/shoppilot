package com.shoppilot.tool.request;

import com.shoppilot.tool.schema.ToolParam;

public record QueryLogisticsRequest(
        @ToolParam(description = "平台订单号，例如 10023") String orderNo) {
}
