package com.shoppilot.tool.request;

import com.shoppilot.tool.schema.ToolParam;

public record ModifyDeliveryAddressRequest(
        @ToolParam(description = "平台订单号，例如 10023") String orderNo,
        @ToolParam(description = "收件人姓名") String receiverName,
        @ToolParam(description = "收件人手机号，11 位数字") String receiverPhone,
        @ToolParam(description = "省级行政区，例如 浙江省") String province,
        @ToolParam(description = "地级市，例如 杭州市") String city,
        @ToolParam(description = "区县，例如 西湖区") String district,
        @ToolParam(description = "街道门牌详址") String detailAddress) {
}
