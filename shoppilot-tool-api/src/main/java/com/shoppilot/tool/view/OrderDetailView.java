package com.shoppilot.tool.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * 买家订单详情（round44 票 189 / ADR 0079）。
 *
 * <p>与 {@link OrderView} 的区别是「给谁看」：{@code OrderView} 喂给模型做工具回执，
 * 这个给买家页面画深度卡，所以多了 {@code deliveredAt}（签收时间，用来点亮状态推进条）
 * 与 {@code addressVersion}（地址改过几次，买家能对上自己改的那次）。
 *
 * <p>**{@code address.receiverPhone} 在这里已是脱敏值**（{@code 138****5678}），由 biz-mock 在
 * 出站前完成。脱敏放前端等于原文照样过网、照样进浏览器内存，那条链路只是看起来安全。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderDetailView(
        String orderNo,
        long amountFen,
        OrderStatus status,
        String categoryName,
        List<String> serviceFlags,
        AddressView address,
        int addressVersion,
        Instant createdAt,
        Instant paidAt,
        Instant shippedAt,
        Instant deliveredAt,
        RefundReviewState refundReview) {

    public String amountYuan() {
        return String.format("%.2f", amountFen / 100.0d);
    }
}
