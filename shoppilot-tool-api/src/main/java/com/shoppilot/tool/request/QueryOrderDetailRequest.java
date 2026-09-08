package com.shoppilot.tool.request;

import com.shoppilot.tool.schema.ToolParam;

public record QueryOrderDetailRequest(
        @ToolParam(description = "平台订单号，例如 10023；用户未提供时必须追问而非猜测") String orderNo) {
}
