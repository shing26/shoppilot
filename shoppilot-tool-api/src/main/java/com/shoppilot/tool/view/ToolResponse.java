package com.shoppilot.tool.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 统一的工具响应信封。
 *
 * @param allowedActions 当 status 为 STATE_NOT_ALLOWED 时，给出该订单当前允许的动作，供模型解释原因
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolResponse<T>(
        String tool,
        ToolStatus status,
        T payload,
        String message,
        List<String> allowedActions) {

    public static <T> ToolResponse<T> ok(String tool, T payload) {
        return new ToolResponse<>(tool, ToolStatus.OK, payload, null, List.of());
    }

    public static <T> ToolResponse<T> failure(String tool, ToolStatus status, String message, List<String> allowedActions) {
        return new ToolResponse<>(tool, status, null, message, allowedActions == null ? List.of() : allowedActions);
    }

    public boolean succeeded() {
        return status == ToolStatus.OK || status == ToolStatus.IDEMPOTENT_REPLAY;
    }
}
