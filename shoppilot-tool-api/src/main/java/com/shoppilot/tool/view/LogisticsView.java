package com.shoppilot.tool.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LogisticsView(
        String orderNo,
        String cpCode,
        String cpName,
        String trackingNo,
        List<LogisticsNodeView> nodes) {

    public LogisticsNodeView latest() {
        return nodes == null || nodes.isEmpty() ? null : nodes.get(nodes.size() - 1);
    }
}
