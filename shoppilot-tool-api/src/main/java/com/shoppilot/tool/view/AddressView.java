package com.shoppilot.tool.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AddressView(
        String receiverName,
        String receiverPhone,
        String province,
        String city,
        String district,
        String detailAddress) {

    public String format() {
        return province + city + district + " " + detailAddress;
    }
}
