package com.shoppilot.tool.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 买家端物流轨迹（round44 票 189 / ADR 0079）。
 *
 * <p>为什么不复用 {@link LogisticsView}：那个喂给模型，节点按 {@code seq} 升序，
 * {@code latest()} 取的是**末元素**。买家端要把最新节点放在时间轴顶部，即 {@code nodes} 倒序、
 * {@code latest()} 取首元素——同一形状两种排序语义，混用会让工具回执悄悄变旧。
 *
 * <p>订单存在但没有轨迹时返回空 {@code nodes} 与 {@code null} 承运商字段，**不是 404**：
 * 说"查不到这单"会让顾客以为下错了店。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderLogisticsView(
        String orderNo,
        String cpCode,
        String cpName,
        String trackingNo,
        List<LogisticsNodeView> nodes) {

    /** 倒序下最新节点是第一个。没有轨迹时为 {@code null}。 */
    public LogisticsNodeView latest() {
        return nodes == null || nodes.isEmpty() ? null : nodes.get(0);
    }
}
