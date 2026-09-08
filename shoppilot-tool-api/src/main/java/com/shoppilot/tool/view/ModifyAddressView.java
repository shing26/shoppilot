package com.shoppilot.tool.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ModifyAddressView(
        String orderNo,
        AddressView before,
        AddressView after,
        int addressVersion) {
}
