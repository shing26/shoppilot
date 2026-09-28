package com.shoppilot.tool.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundView(
        String refundId,
        String orderNo,
        long amountFen,
        String status,
        Instant createdAt,
        /**
         * 申请理由（票 61 的面板补的）。审核者要判断「这笔钱该不该放」，光有金额是拍不了板的；
         * 它本来就落在 {@code refunds.reason} 里，只是此前没有对外出口。
         */
        String reason) {
}
