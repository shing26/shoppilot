package com.shoppilot.tool.view;

/**
 * 退款到账边界（round63 票 235）：只表达「钱在哪里继续走」，不表达退款终态。
 *
 * <p>本仓能确认的是退款审核结论；资金到账的权威在支付渠道，所以有退款时固定返回
 * {@link #PAYMENT_CHANNEL}，让验收与前端不必再依赖模型措辞。
 */
public enum PayoutBoundary {
    /** 到账由支付渠道处理；本仓不承诺到账时间，也不伪造到账时间。 */
    PAYMENT_CHANNEL
}
