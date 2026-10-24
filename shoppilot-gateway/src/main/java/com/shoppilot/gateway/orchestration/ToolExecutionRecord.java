package com.shoppilot.gateway.orchestration;

import java.util.Map;

/**
 * 工具执行事实的最小契约。参数可能含订单号等业务标识，出运行时前必须按调用方口径脱敏。
 */
public record ToolExecutionRecord(String tool, String status, long atMillis, Map<String, Object> arguments) {

    public ToolExecutionRecord {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
