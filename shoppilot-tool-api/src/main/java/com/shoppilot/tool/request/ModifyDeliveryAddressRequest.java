package com.shoppilot.tool.request;

import com.shoppilot.tool.schema.ToolParam;

public record ModifyDeliveryAddressRequest(
        @ToolParam(description = "平台订单号，例如 10023") String orderNo,
        @ToolParam(description = "收件人姓名") String receiverName,
        @ToolParam(description = "收件人手机号，11 位数字") String receiverPhone,
        @ToolParam(description = "省级行政区，例如 浙江省；直辖市填 北京市 这类全称。用户没提到就留空，留空表示这一项不改",
                required = false) String province,
        @ToolParam(description = "地级市，例如 杭州市；直辖市与省同名时同样填写。用户没提到就留空，留空表示这一项不改",
                required = false) String city,
        @ToolParam(description = "区县，例如 西湖区。用户没提到就留空，留空表示这一项不改", required = false) String district,
        @ToolParam(description = "街道门牌详址，例如 文三路100号；把用户原话里省市区之外剩下的部分整体填入，"
                + "已经给过就不要追问。用户没提到就留空，留空表示这一项不改", required = false) String detailAddress) {
}
